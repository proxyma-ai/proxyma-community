package ai.proxyma.core.update;

import ai.proxyma.core.system.parts.PartsFetcher;
import ai.proxyma.core.system.parts.PartsManifest;
import ai.proxyma.core.system.parts.PartsRelease;
import ai.proxyma.core.system.parts.PartsStore;
import ai.proxyma.core.system.parts.PartsTransport;
import ai.proxyma.core.system.parts.Platform;
import ai.proxyma.core.system.parts.ReleaseSignatures;
import ai.proxyma.core.system.parts.Versions;
import ai.proxyma.core.system.util.Failures;
import ai.proxyma.core.settings.dto.UpdatesSettings;
import ai.proxyma.core.settings.service.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

// Checks for new releases on startup and on a configurable schedule via the GitHub Releases API.
// On a desktop install it then downloads the PARTS of the new release that are not installed yet,
// verifies each against the release's signed checksums, and stages the release for the launcher to
// switch to at the next restart (POST /v1/update/apply exits with code 3 for the watchdog).
//
// THE SHA-256 CHECK AND THE SIGNATURE ARE BOTH REFUSED RATHER THAN WARNED ABOUT when missing. This
// class claimed a digest check for months before it computed one - the failure class
// .claude/rules/security.md names in its own heading - and it downloads code that the launcher then
// runs. See PartsRelease, ReleaseSignatures, and SEC-34 in .claude/memory/security-open-findings.md.
//
// ONE CHANNEL FOR THE DESKTOP, BOTH PLATFORMS, since the installers became small (2026-09-26). The
// Windows installer and the .deb carry only the launcher and a Java runtime; everything else is a
// part in the user's data directory (PartsStore), which the running app can write to on either
// platform. That retired the two channels this class used to have - extracting an update ZIP over
// the install root on Windows, and handing a .deb to the system installer on Linux. An update now
// downloads what changed, not the whole product.
//
// Anything not started by the launcher - the Home Server, a jar run by hand - only reports that a
// newer release exists.
//
// NOT LOADED ON ENTERPRISE SERVER (editions-assembly-20). No releases API is configured there, so
// every check answered "up to date", the server's SPA has no Updates page, and its routes sat under
// the blanket authenticated rule where any viewer could call them. A server is upgraded by its
// operator, from the image.
@Service
@Profile("!server")
public class UpdateService {

    private static final Logger log = LoggerFactory.getLogger(UpdateService.class);

    /** The release asset listing one SHA-256 per published file, in `sha256sum` format. */
    private static final String CHECKSUMS_ASSET = ReleaseSignatures.CHECKSUMS_ASSET;

    @Value("${spring.application.version:0.0.0}")
    private String currentVersion;

    // e.g. https://api.github.com/repos/myorg/proxyma-releases
    @Value("${proxyma.updates.releases-api-url:}")
    private String releasesApiUrl;

    private final RestClient restClient;
    private final SettingsService settingsService;
    private final UpdateHistory history;
    /** The public keys an update must be signed by. See ReleaseSignatures. */
    private final ReleaseSignatures signatures = ReleaseSignatures.fromClasspath();

    private volatile UpdateStatus status = UpdateStatus.UP_TO_DATE;
    private volatile String availableVersion;
    /** The release the last check found, kept so a manual download does not have to look again. */
    private volatile Release pending;
    /** When a check last completed, for the schedule below and for the Updates page to show. */
    private volatile Instant lastCheckAt;
    /** Why the last check or download failed, in one line, for the page to show. Null when fine. */
    private volatile String lastError;

    // DOWNLOAD PROGRESS, for the Updates page's bar. Written by the one thread doing the download and
    // read by status polls, so volatile is enough - a poll that sees a slightly stale count is fine.
    //
    // The total is the size of the parts still to fetch, from the release's own manifest - an
    // update that changed one part downloads that part, and the bar measures that.
    private volatile long downloadedBytes;
    private volatile long downloadTotalBytes;
    private volatile DownloadStage downloadStage = DownloadStage.PREPARING;

    // CANCELLING A DOWNLOAD. The flag is read between every chunk of the transfer and between the
    // stages after it, so a cancel lands within one 64 KB read. `cancelledVersion` stops auto-update
    // from starting the same download again at the next scheduled check - the user said no to that
    // version; the Download button still fetches it if they change their mind.
    private volatile boolean cancelRequested;
    private volatile String cancelledVersion;
    // The thread running the download, so a cancel can INTERRUPT it. The flag alone is read only
    // between chunks of a body already arriving; on a slow network the thread spends minutes
    // before that - connecting, waiting for a response, following a redirect - and a cancel then
    // did nothing until a timeout (feedback, 2026-09-29). The JDK HttpClient gives up a send and a
    // body read on interrupt.
    private volatile Thread downloadThread;

    // Downloads run HERE, not on the request thread. POST /update/check and /update/download used to
    // hold their request open for the whole transfer, so the page showed "checking" for the length of
    // a several-hundred-megabyte download and had no way to stop it. One thread: two downloads at
    // once would write the same staging directory.
    private final ExecutorService downloader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "update-download");
        t.setDaemon(true);
        return t;
    });

    public UpdateService(@Qualifier("externalRestClientBuilder") RestClient.Builder builder,
                         SettingsService settingsService,
                         UpdateHistory history) {
        this.restClient      = builder.build();
        this.settingsService = settingsService;
        this.history         = history;
    }

    public enum UpdateStatus { UP_TO_DATE, CHECKING, AVAILABLE, DOWNLOADING, READY, ERROR }

    // What a DOWNLOADING status is doing now. PREPARING is reading and verifying the release, before
    // the size of the download is known - the page shows a moving bar and no byte count for it,
    // because "0 B" beside a bar that has already moved reads as broken. TRANSFERRING is the parts
    // themselves, each checked and unpacked as it arrives.
    public enum DownloadStage { PREPARING, TRANSFERRING }

    public UpdateStatus getStatus() { return status; }
    public String getAvailableVersion() { return availableVersion; }
    public long getDownloadedBytes() { return downloadedBytes; }
    public long getDownloadTotalBytes() { return downloadTotalBytes; }
    public DownloadStage getDownloadStage() { return downloadStage; }

    /**
     * The scheduler's tick, which is NOT the check interval.
     *
     * <p>Fixed at 15 minutes, because {@code @Scheduled} is read once at startup and cannot consult
     * a setting somebody changes at runtime. The tick asks whether enough time has passed for the
     * configured frequency and usually does nothing, which costs a comparison. Setting the
     * frequency to 0 stops automatic checks entirely and leaves the manual one working - that
     * separation is the point of the setting.
     */
    @Scheduled(fixedRate = 900_000, initialDelay = 60_000)
    public void checkForUpdates() {
        int everyHours = settings().getCheckFrequencyHours();
        if (everyHours == 0) return;
        Instant last = lastCheckAt;
        if (last != null && Duration.between(last, Instant.now()).toHours() < everyHours) return;
        runCheck();
    }

    /**
     * The check at startup, OFF the boot thread (editions-assembly-10). A ready listener runs on
     * main, in no particular order with the others - the one that opens the window among them - and
     * this one calls GitHub through a client that waits out a Retry-After.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void checkWhenReady() {
        Thread.ofVirtual().name("update-check").start(this::checkForUpdates);
    }

    /** A check the user asked for, from the Updates page. Ignores the schedule; never throws. */
    public void checkNow() {
        runCheck();
    }

    private synchronized void runCheck() {
        // A scheduled check landing mid-download would flip the status to CHECKING under a running
        // transfer, and the page would lose its progress bar.
        if (status == UpdateStatus.DOWNLOADING) return;
        lastCheckAt = Instant.now();
        if (releasesApiUrl == null || releasesApiUrl.isBlank()) {
            // Not an error: a build with no releases API configured is a development build, and
            // saying ERROR there would send somebody looking for a fault that is a missing setting.
            status = UpdateStatus.UP_TO_DATE;
            return;
        }

        // Read BEFORE the line below overwrites it. The ALREADY STAGED guard further down compared
        // the live status to READY after it had been set to CHECKING, so it never fired.
        boolean wasReady = status == UpdateStatus.READY;
        status = UpdateStatus.CHECKING;
        lastError = null;
        try {
            Release latest = fetchLatestRelease(releasesApiUrl, settings().isDevBuilds());
            if (latest == null || latest.tag == null || !isNewer(latest.tag, currentVersion)) {
                status = UpdateStatus.UP_TO_DATE;
                pending = null;
                history.record(UpdateHistory.Event.CHECKED, currentVersion, currentVersion,
                        "Up to date");
                return;
            }
            // A RELEASE THAT ALREADY FAILED TO START HERE. The launcher rolled back from it; offering
            // it again would put the user one click from the same failure, on every check.
            PartsStore parts = store();
            if (parts != null && parts.rejectedVersion().map(v -> Versions.stripV(v).equals(Versions.stripV(latest.tag))).orElse(false)) {
                status = UpdateStatus.UP_TO_DATE;
                pending = null;
                lastError = "Proxyma " + latest.tag + " did not start on this computer, so it is not offered again.";
                return;
            }
            // ALREADY STAGED. Without this every scheduled check with auto-update on fetched the same
            // release again and staged it over itself - and Apply was refused while it did. READY is
            // restored, since the check itself set CHECKING.
            if (wasReady && latest.tag.equals(availableVersion)) {
                status = UpdateStatus.READY;
                return;
            }
            availableVersion = latest.tag;
            pending = latest;
            status = UpdateStatus.AVAILABLE;
            history.record(UpdateHistory.Event.FOUND, currentVersion, latest.tag,
                    detectOnly() ? "Available to download" : "Available");
            if (detectOnly()) {
                // AVAILABLE, never READY - READY is what enables the Apply button, and there is
                // nothing here to apply. The UI reads this as "a new version exists, go and get
                // it", which is the whole of what this platform supports.
                log.info("Proxyma {} is available. This installation updates by downloading the new "
                        + "release rather than in place", latest.tag);
                return;
            }
            // Downloading is now a SEPARATE decision from checking. With auto-update off the check
            // still reports what exists and stops here; the user presses Download when they want
            // it. That is the whole reason the two settings were split.
            if (!settings().isAutoUpdate()) return;
            if (latest.tag.equals(cancelledVersion)) return;
            startDownload(latest, false);
        } catch (Exception e) {
            fail("Update check failed", e);
        }
    }

    /**
     * Downloads and stages the release the last check found, for a user who has auto-update off.
     *
     * <p>Refuses rather than re-checking when nothing is pending: "download what, exactly" is a
     * question this method cannot answer on its own, and silently checking first would make a
     * button labelled Download sometimes mean Check.
     */
    public synchronized void downloadNow() {
        if (detectOnly()) {
            throw new IllegalStateException(
                    "This platform updates by downloading the new release, not in place");
        }
        Release release = pending;
        if (release == null) throw new IllegalStateException("No update available to download");
        if (status == UpdateStatus.DOWNLOADING) throw new IllegalStateException("A download is already running");
        startDownload(release, true);
    }

    /**
     * Stops the running download. The partial file and anything staged are removed, and the status
     * goes back to AVAILABLE so the same version can be downloaded again by hand.
     *
     * <p>NOT synchronized, deliberately: the download holds no lock, but a check or a download
     * request might, and a cancel that waited behind one would arrive after the thing it cancels.
     */
    public void cancelDownload() {
        if (status != UpdateStatus.DOWNLOADING) throw new IllegalStateException("No download is running");
        cancelRequested = true;
        Thread running = downloadThread;
        if (running != null) running.interrupt();
    }

    /** Marks the download started and hands it to the download thread; returns at once. */
    // byUser: the Download button rather than auto-update, for the audit log's DOWNLOADED entry.
    private void startDownload(Release release, boolean byUser) {
        cancelRequested = false;
        downloadedBytes = 0;
        downloadTotalBytes = 0;
        downloadStage = DownloadStage.PREPARING;
        status = UpdateStatus.DOWNLOADING;
        downloader.submit(() -> stage(release, byUser));
    }

    private void stage(Release release, boolean byUser) {
        downloadThread = Thread.currentThread();
        try {
            // A cancel that landed between startDownload and this line set only the flag.
            checkCancelled();
            downloadAndStage(release);
            if (cancelRequested) throw new DownloadCancelled();
            status = UpdateStatus.READY;
            history.record(UpdateHistory.Event.DOWNLOADED, currentVersion, release.tag(),
                    "Downloaded, ready to apply", byUser);
        } catch (Exception e) {
            // Asked of the FLAG, not of the exception type: the cancel surfaces from inside the
            // RestClient's exchange callback, which may wrap whatever was thrown there.
            if (cancelRequested) {
                Thread.interrupted();   // cleared before the clean-up's own file I/O
                discardStaging();
                cancelledVersion = release.tag();
                lastError = null;
                status = UpdateStatus.AVAILABLE;
                history.record(UpdateHistory.Event.CANCELLED, currentVersion, release.tag(), "Download cancelled");
                log.info("Update download of {} cancelled", release.tag());
            } else {
                fail("Update download failed", e);
            }
        } finally {
            downloadThread = null;
            cancelRequested = false;
            // An interrupt that arrived as the download ended must not reach the next task on
            // this reused thread.
            Thread.interrupted();
        }
    }

    /** Thrown between stages when a cancel was asked for. Never escapes {@link #stage}. */
    private static final class DownloadCancelled extends RuntimeException {
        DownloadCancelled() { super("Download cancelled", null, false, false); }
    }

    private void checkCancelled() {
        if (cancelRequested) throw new DownloadCancelled();
    }

    /**
     * Forgets a staged release, so a cancelled update cannot be applied. Parts already downloaded
     * stay: a later download of the same release reuses them, and the launcher removes any that no
     * release refers to.
     */
    private void discardStaging() {
        PartsStore store = store();
        if (store == null) return;
        try {
            store.clearStaged();
        } catch (IOException e) {
            log.debug("Could not clear the staged release: {}", e.getMessage());
        }
    }

    private void fail(String what, Exception e) {
        // The MESSAGE, never the exception - a stack trace here would reach the Updates page and
        // the history file, both of which are read by people and neither of which is a log.
        // Failures.describe rather than getMessage(): an update check that fails behind a proxy or
        // on a DNS or TLS fault is wrapped by the HTTP client, and the outer message alone said
        // only that a request failed - the cause, and the plain sentence for it, sit underneath.
        lastError = what + ": " + Failures.describe(e);
        log.warn("{}: {}", what, e.getMessage());
        status = UpdateStatus.ERROR;
        history.record(UpdateHistory.Event.FAILED, currentVersion,
                availableVersion == null ? "" : availableVersion, lastError);
    }

    private UpdatesSettings settings() {
        return settingsService.getSettings(UpdatesSettings.PREFIX, UpdatesSettings.class);
    }

    public Instant getLastCheckAt() { return lastCheckAt; }
    public String getLastError() { return lastError; }
    public String getCurrentVersion() { return currentVersion; }

    /**
     * Whether this installation can install an update itself.
     *
     * <p>The inverse of {@link #detectOnly()}, exposed because the Updates page needs to say WHY it
     * is not offering an Apply button. A disabled button with no explanation reads as a bug; "this
     * installation updates by downloading the new release" reads as a fact.
     */
    public boolean canApplyInPlace() { return channel() != Channel.DETECT_ONLY; }

    /** How this installation applies an update - PARTS or DETECT_ONLY - for the Updates page. */
    public String getInstallKind() { return channel().name(); }

    /** What this installation can do about an update. */
    private enum Channel {
        /** A desktop install started by the launcher: download the changed parts, restart into them. */
        PARTS,
        /** Everything else - a server edition, a jar run by hand. Detect and point at the download. */
        DETECT_ONLY
    }

    /** Set by the launcher on the application JVM. A contract with ProxymaLauncher. */
    static final String PARTS_DIR_PROPERTY = "proxyma.parts.dir";

    /**
     * How this copy of Proxyma can update itself.
     *
     * <p><b>Decided by what the LAUNCHER passed, never inferred from the filesystem.</b> The launcher
     * names the parts directory it started this process from; a jar run by hand has none, and
     * updating a parts directory the user did not launch from would update the wrong installation.
     */
    private static Channel channel() {
        String dir = System.getProperty(PARTS_DIR_PROPERTY);
        if (dir != null && !dir.isBlank() && Platform.current() != null) return Channel.PARTS;
        return Channel.DETECT_ONLY;
    }

    private static boolean detectOnly() { return channel() == Channel.DETECT_ONLY; }

    private static PartsStore store() {
        String dir = System.getProperty(PARTS_DIR_PROPERTY);
        return dir == null || dir.isBlank() ? null : new PartsStore(Path.of(dir));
    }

    public void applyAndRestart() {
        // Unreachable through the UI (the button is gated on canApplyInPlace) but stated here
        // anyway - this is public, and "no update staged" would be a confusing thing to read on an
        // installation that never stages one.
        if (detectOnly()) {
            throw new IllegalStateException(
                    "This installation updates by downloading the new release, not in place");
        }
        if (status != UpdateStatus.READY) throw new IllegalStateException("No update staged");
        // Recorded BEFORE the process ends, because after that there is nothing left to record
        // with. Whether the new version then started is the next run's CHECKED line, with a
        // different fromVersion.
        history.record(UpdateHistory.Event.APPLIED, currentVersion,
                availableVersion == null ? "" : availableVersion, "Restarting to apply");
        // The launcher around this JVM sees exit code 3, switches to the staged release and starts
        // it - going back to this one by itself if the new release does not come up.
        new Thread(() -> {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            System.exit(3);
        }, "update-restart").start();
    }

    // Parses the fields we need from GET /releases/latest JSON without pulling in Jackson here.
    // assetUrl is this platform's parts manifest; the release's download location is derived from it.
    private record Release(String tag, String assetUrl) {}

    private Release fetchLatestRelease(String apiUrl, boolean devBuilds) {
        try {
            // /releases/latest EXCLUDES prereleases, which is exactly right for everybody who has not
            // asked for dev builds. With them on, the list is read instead and the newest release
            // THIS installation can apply is chosen from it, stable or not; see chooseRelease.
            String url = apiUrl + (devBuilds ? "/releases?per_page=30" : "/releases/latest");
            String body = restClient.get()
                    .uri(URI.create(url))
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .retrieve()
                    .body(String.class);
            if (body == null) return null;
            if (devBuilds) {
                // A newer dev build with nothing for THIS platform is passed over in silence: a dev
                // build is published for Linux by the pipeline and for Windows by hand, and until the
                // second half exists there is nothing here to offer. Naming the skipped build ("has no
                // version for this computer yet", 2026-10-08) was taken out at the maintainer's
                // request on 2026-10-10 - a version this computer cannot have is not worth a line.
                body = chooseRelease(body, UpdateService::matchesChannel, !detectOnly());
                if (body == null) return null;
            }

            String tag = jsonString(body, "tag_name");
            if (tag == null) return null;
            String assetUrl = scanAssets(body, UpdateService::matchesChannel, false);
            // A missing manifest means there is nothing to download, which is only fatal where we
            // would have downloaded it. On a detect-only installation the tag is the entire answer,
            // and requiring an artifact would report UP_TO_DATE for a release that is newer.
            if (assetUrl == null && !detectOnly()) return null;
            return new Release(tag, assetUrl);
        } catch (Exception e) {
            log.debug("Could not fetch latest release: {}", e.getMessage());
            return null;
        }
    }

    /** This platform's parts manifest - the one asset a release must carry for this installation. */
    private static boolean matchesChannel(String assetName) {
        String platform = Platform.current();
        return channel() == Channel.PARTS && platform != null && PartsManifest.assetName(platform).equals(assetName);
    }

    /**
     * Downloads the parts of {@code release} that are not installed yet, and stages the release.
     *
     * <p>Nothing is downloaded before the release's manifest has been proved genuine: the signature
     * on SHA256SUMS.txt first, then the manifest's digest against it (PartsRelease). Each part is
     * then checked against the digest in that manifest before it is extracted (PartsFetcher).
     */
    private void downloadAndStage(Release release) throws Exception {
        PartsStore store = store();
        if (store == null || release.assetUrl() == null) throw new IllegalStateException("Nothing to download");
        String releaseUrl = release.assetUrl().substring(0, release.assetUrl().lastIndexOf('/') + 1);
        boolean devAllowed = devNumber(release.tag()) >= 0 && settings().isDevBuilds();
        PartsTransport transport = transport();

        downloadStage = DownloadStage.PREPARING;
        // The manifest must be the version the release is TAGGED as, and newer than this one: the
        // tag is an unsigned name, and an old release's signed files under a newer tag would
        // otherwise stage a downgrade (platform-02). PartsStore refuses to promote one as well.
        PartsManifest manifest = PartsRelease.fetch(transport, releaseUrl, Platform.current(), signatures,
                devAllowed, release.tag());
        if (!isNewer(manifest.version(), currentVersion)) {
            throw new IOException("Proxyma " + manifest.version() + " is not newer than this installation ("
                    + currentVersion + ") - refusing it");
        }
        checkCancelled();

        // The total BEFORE the stage: a poll between the two would otherwise see a transfer of 0 B.
        downloadedBytes = 0;
        downloadTotalBytes = store.missing(manifest).stream().mapToLong(PartsManifest.Part::size).sum();
        downloadStage = DownloadStage.TRANSFERRING;
        new PartsFetcher(store, transport).installMissing(manifest,
                (part, done, total) -> downloadedBytes = done, () -> cancelRequested);
        checkCancelled();
        store.stage(manifest);
        log.info("Update {} staged: signature on {}, and every part's SHA-256", release.tag(), CHECKSUMS_ASSET);
    }

    /**
     * The application's own HTTP client as a PartsTransport, so an update travels through the proxy
     * and the certificate authorities the user configured in Settings > Network.
     *
     * <p>exchange(), not retrieve(): the body is the raw network stream, copied to disk as it
     * arrives. retrieve().body(InputStream.class) has no converter for application/octet-stream,
     * and failed every download this class attempted until 2026-09-01.
     */
    private PartsTransport transport() {
        return new PartsTransport() {
            @Override
            public <T> T get(URI url, long offset, ResponseHandler<T> handler) throws IOException {
                try {
                    return restClient.get()
                            .uri(url)
                            .header("Accept", "application/octet-stream")
                            .headers(h -> { if (offset > 0) h.set("Range", "bytes=" + offset + "-"); })
                            .exchange((request, response) -> handler.handle(response.getStatusCode().value(),
                                    response.getHeaders().getContentLength(), response.getBody()));
                } catch (RestClientException e) {
                    // AS AN IOException, or PartsFetcher does not retry it. RestClient wraps a
                    // dropped connection in its own unchecked exception, so the first drop in a
                    // 180 MB part failed the whole update instead of resuming - found 2026-09-26
                    // against a local release that cut every part once.
                    if (e.getCause() instanceof IOException io) throw io;
                    throw new IOException(e.getMessage(), e);
                }
            }
        };
    }

    // Minimal JSON field extractors - avoids a Jackson dependency in this service.

    private static String jsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) return null;
        int open = json.indexOf('"', colon + 1);
        if (open < 0) return null;
        int close = json.indexOf('"', open + 1);
        if (close < 0) return null;
        return json.substring(open + 1, close);
    }

    // findUpdateAssetUrl/findUpdateAssetName used to live here: a second, hand-rolled walk of the
    // assets array that hardcoded "-update.zip", plus a one-line wrapper over scanAssets that did
    // the same job. Both are gone, and the channel's own suffix now goes straight into scanAssets.
    // Two implementations of the same slice arithmetic was the risk scanAssets' own javadoc names.

    /**
     * Walks the assets array once, returning either the matching asset's NAME or its download URL.
     *
     * <p>Shared by the three lookups above rather than written three times: the slice arithmetic
     * below - where one asset object ends and the next begins - is the fiddly part, and three
     * copies of it is three chances to take a URL from the wrong object. That failure would hand
     * the updater a real, signed-looking URL pointing at a different artifact.
     */
    private static String scanAssets(String json, java.util.function.Predicate<String> matches,
                                     boolean wantName) {
        int assetsIdx = json.indexOf("\"assets\"");
        if (assetsIdx < 0) return null;
        int search = assetsIdx;
        while (true) {
            int nameIdx = json.indexOf("\"name\"", search);
            if (nameIdx < 0) return null;
            String name = jsonStringAt(json, nameIdx);
            if (name != null && matches.test(name)) {
                if (wantName) return name;
                // THE FIRST browser_download_url AFTER THIS NAME, not "whatever is before the next
                // brace". That is what this used to do, and it never worked against GitHub: a real
                // asset object carries an "uploader": { ... } between its name and its URL, so the
                // slice ended at the uploader's opening brace and the URL was always outside it.
                //
                // fetchLatestRelease then found a tag but no asset URL, and returns null for any
                // platform that needs one - so every check reported UP_TO_DATE and no update was
                // ever offered. It survived because the fake releases API used to test this emitted
                // assets carrying only name and browser_download_url, which is the one shape where
                // the old arithmetic happened to be right.
                //
                // Scanning forward is safe: each asset object has exactly one
                // "browser_download_url", and nothing nested inside an asset (the uploader has
                // "url", "html_url" and "avatar_url") uses that key.
                int urlIdx = json.indexOf("\"browser_download_url\"", nameIdx);
                if (urlIdx < 0) return null;
                return jsonStringAt(json, urlIdx);
            }
            search = nameIdx + 6;
        }
    }

    private static String jsonStringAt(String json, int keyStart) {
        int colon = json.indexOf(':', keyStart);
        if (colon < 0) return null;
        int open = json.indexOf('"', colon + 1);
        if (open < 0) return null;
        int close = json.indexOf('"', open + 1);
        if (close < 0) return null;
        return json.substring(open + 1, close);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * From a {@code GET /releases} list, the newest release this installation can apply, as the JSON
     * of that one release - so the single-release extraction above reads it unchanged.
     *
     * <p>Stable releases and dev prereleases compete on version alone ({@link #isNewer}). A release
     * missing THIS channel's artifact, the checksums file or its signature is skipped rather than
     * chosen: a dev
     * build published by the pipeline has no Windows payload until somebody builds one on the PC,
     * and a Windows user should then get the newest build that HAS one, not an error. Drafts are
     * never considered.
     */
    static String chooseRelease(String listJson, Predicate<String> assetMatches, boolean requireAsset) {
        try {
            JsonNode list = JSON.readTree(listJson);
            if (list == null || !list.isArray()) return null;
            JsonNode best = null;
            String bestTag = null;
            for (JsonNode release : list) {
                if (release.path("draft").asBoolean(false)) continue;
                String tag = release.path("tag_name").asText(null);
                if (tag == null || tag.isBlank()) continue;
                if (requireAsset) {
                    boolean hasArtifact = false, hasSums = false, hasSignature = false;
                    for (JsonNode asset : release.path("assets")) {
                        String name = asset.path("name").asText("");
                        if (assetMatches.test(name)) hasArtifact = true;
                        if (CHECKSUMS_ASSET.equals(name)) hasSums = true;
                        if (ReleaseSignatures.SIGNATURE_ASSET.equals(name)) hasSignature = true;
                    }
                    if (!hasArtifact || !hasSums || !hasSignature) continue;
                }
                if (best == null || isNewer(tag, bestTag)) {
                    best = release;
                    bestTag = tag;
                }
            }
            return best == null ? null : JSON.writeValueAsString(best);
        } catch (Exception e) {
            log.debug("Could not read the releases list: {}", e.getMessage());
            return null;
        }
    }

    static boolean isNewer(String candidate, String current) {
        return Versions.isNewer(candidate, current);
    }

    /** N for a {@code -devN} qualifier, else -1. */
    static long devNumber(String v) {
        return Versions.devNumber(v);
    }
}
