package ai.proxyma.desktop.launcher;

import ai.proxyma.core.system.parts.PartsFetcher;
import ai.proxyma.core.system.parts.PartsManifest;
import ai.proxyma.core.system.parts.PartsRelease;
import ai.proxyma.core.system.parts.PartsStore;
import ai.proxyma.core.system.parts.PartsTransport;
import ai.proxyma.core.system.parts.Platform;
import ai.proxyma.core.system.parts.ReleaseSignatures;
import ai.proxyma.core.system.parts.Versions;
import ai.proxyma.core.system.util.AccessCode;
import ai.proxyma.core.system.util.AppPaths;
import ai.proxyma.core.system.util.PythonLayout;
import ai.proxyma.desktop.system.AppWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardOpenOption.*;

// Desktop launcher, Windows and Linux. Responsibilities (in order):
//   1. Single-instance lock
//   2. ensureParts          - the parts of this release, downloaded on the first start (see below)
//   3. ensurePython         - the Python runtime, built from the python part when it changes
//   4. spawnApplication     - starts the Spring Boot JVM from the app part, on loopback - from an
//                             extracted copy with a class archive once ClassDataSharing has made them
//   5. waitForAppReady      - polls /api/v1/health/ready, then dismisses the splash
//   6. on exit code 3       - switches to a release the updater staged, rolling back if it fails
// Before every start it also carries out a reset the user asked for (PendingReset).
// ChromaDB process management and license verification are handled by the app itself.
//
// THE INSTALLERS CARRY ONLY THIS LAUNCHER AND A JAVA RUNTIME, since 2026-09-26. Everything else -
// the application, the Python runtime and its packages, the headless browser, Tesseract and (on
// Windows) the application window - is a PART, downloaded from the release on the first start into
// the data directory and verified against the release's signed checksums. An update downloads only
// the parts that changed. PartsStore describes the layout and PartsManifest the format.
//
// IN THE DATA DIRECTORY, NOT THE INSTALL ROOT, because the .deb's /opt/proxyma is root-owned and
// both this launcher and the running application's updater have to write there. The Linux window
// is the one exception: Chromium's sandbox helper must be setuid root, which only a package can
// install, so on Linux the window stays in the .deb.
//
// THIS CLASS CAN BE NEWER THAN THE INSTALLER. Boot, the installer's entry point, starts the
// launcher part of the active release when that is newer than the launcher it carries - so launcher
// fixes ship like any other part. Nothing here may assume it runs from the installed jar.
public class ProxymaLauncher {

    // MUST come before the Logger field below, and must stay a static block rather than the first
    // lines of main().
    //
    // logback.xml writes to ${proxyma.log.dir:-logs}/proxyma.log. That property used to be set at
    // the top of main() with a comment claiming it ran "before any logger initializes" - it did
    // not. `log` below is a static field, so class initialization runs it, and class initialization
    // happens before main() is ever called. logback therefore resolved the fallback `logs`,
    // relative to the working directory, every single time - and on Linux the working directory is
    // the root-owned /opt/proxyma, so the launcher ran with no log file at all.
    //
    // Static initializers run in textual order, so putting this above `log` is what makes the
    // property visible to logback. Failures are swallowed: no log directory is a reason to fall
    // back to console logging, never a reason to refuse to start.
    static {
        try {
            Path logsDir = AppPaths.create().logsDir();
            Files.createDirectories(logsDir);
            System.setProperty("proxyma.log.dir", logsDir.toString());
        } catch (Exception e) {
            System.err.println("Could not prepare the log directory (" + e.getMessage()
                    + ") - continuing with console logging only");
        }
        // The operating system's proxy settings, for the first-start download: a company laptop
        // reaches GitHub through a proxy it never told Java about. Read once, at the first network
        // call, so it has to be set before anything connects.
        System.setProperty("java.net.useSystemProxies", "true");
    }

    private static final Logger log = LoggerFactory.getLogger(ProxymaLauncher.class);
    private static final int EXIT_CODE_UPDATE = 3;

    /**
     * Where releases are downloaded from: {@code <base><tag>/<file>}. GitHub serves the newest stable
     * release at {@code .../releases/latest/download/<file>}.
     *
     * <p>PROXYMA_RELEASES_URL replaces both, for testing a release before it is published: it serves
     * {@code <url>/<tag>/<file>} and {@code <url>/latest/<file>}. Safe to leave to the environment,
     * because nothing it serves is installed without a signature from a key built into this jar.
     */
    private static final String RELEASES = "https://github.com/proxyma-ai/proxyma-releases/releases/download/";
    private static final String RELEASES_LATEST = "https://github.com/proxyma-ai/proxyma-releases/releases/latest/download/";

    public static void main(String[] args) throws Exception {
        runWatchdog();
    }

    private static void runWatchdog() throws Exception {
        AppPaths appPaths = AppPaths.create();
        Path dataRoot = appPaths.createDataRoot();
        PartsStore store = new PartsStore(appPaths.partsDir());

        // Single-instance guard
        FileChannel lockChannel = FileChannel.open(appPaths.appLockFile(), CREATE, WRITE);
        FileLock lock = lockChannel.tryLock();
        if (lock == null) {
            // Started again while running - from the Start menu, a desktop shortcut, the app menu.
            // Bring the window up instead of doing nothing: the shell keeps a single instance, so
            // this focuses the open window, or opens it again if it was closed.
            System.err.println("Proxyma is already running - opening its window.");
            // Boot marked this launcher part as trying before starting it, and this start ends here
            // without the application ever answering it. Left behind, the marker would make the next
            // real start distrust a part that works and fall back to the installer's launcher. The
            // part got as far as finding Proxyma running, which is as good as it gets on this path.
            Boot.startedWell(store.root());
            openRunningWindow(store, dataRoot);
            System.exit(0);
        }

        Splash splash = SplashWindow.create();
        try {
            watch(store, dataRoot, splash);
        } catch (Exception e) {
            // Anything that escapes the loop - a Python rebuild that failed on the restart path, a
            // rollback that could not prepare - used to leave the splash up with nothing running
            // behind it, until somebody ended the process (editions-assembly-07).
            log.error("Startup failed: {}", e.getMessage(), e);
            SplashWindow.create().setError(e.getMessage());
            Thread.sleep(12_000);
            System.exit(1);
        }

        lock.release();
        lockChannel.close();
    }

    private static void watch(PartsStore store, Path dataRoot, Splash splash) throws Exception {
        PartsManifest active;
        Optional<PartsManifest> promoted;
        String bearer;
        try {
            // An update the application downloaded before it was quit rather than restarted.
            promoted = store.promoteStaged();
            promoted.ifPresent(m -> log.info("Switching to {}, downloaded earlier", m.version()));
            active = ensureParts(store, splash);
            try {
                ensurePython(active, store, splash);
            } catch (Exception e) {
                // A release promoted at THIS start that cannot even be prepared goes back to the one
                // before it, rather than failing the same way at every start after this one.
                if (promoted.isEmpty() || !promoted.get().equals(active)) throw e;
                Optional<PartsManifest> back = rollBack(active, store, splash, e);
                if (back.isEmpty()) throw e;
                active = back.get();
                promoted = Optional.empty();
            }
            // The access code, made before the application first starts so the two never race to
            // create it; the launcher's own calls to the app carry the token derived from it.
            bearer = AccessCode.bearerToken(AccessCode.resolve(System.getenv("PROXYMA_ACCESS_CODE"), dataRoot));
        } catch (Exception e) {
            log.error("Startup failed: {}", e.getMessage(), e);
            splash.setError(e.getMessage());
            Thread.sleep(12_000);
            System.exit(1);
            return;
        }
        store.collectGarbage();
        splash.setChromaStarting();

        // True for the first start of a release the updater just switched to: if that release cannot
        // start, the previous one is still installed and the launcher goes back to it.
        //
        // That includes a release promoted at THIS start - an update downloaded and then Quit rather
        // than applied - which is exactly as untried as one switched to on exit code 3. Not when
        // ensureParts then chose a different release (a newer installer run over the install): that
        // is the installer's own, and the promoted one is already its rollback target.
        boolean justSwitched = promoted.isPresent() && promoted.get().equals(active);
        while (true) {
            // A reset the user asked for, at EVERY start and before anything opens the databases -
            // not only after an exit-3 restart, which let a marker that missed its restart wait for
            // an unrelated update to wipe everything (editions-assembly-05).
            if (Files.exists(dataRoot.resolve(PendingReset.MARKER))) splash.setStatus("Resetting Proxyma");
            PendingReset.applyIfRequested(dataRoot, Instant.now());
            splash.setStatus("Starting");
            int appPort = APP_PORT;
            Process appProcess;
            ClassDataSharing.Launch launch;
            // Nothing of the application is running here, so a lock file is one a previous run
            // left - and each costs that start four seconds (StaleDatabaseLocks).
            StaleDatabaseLocks.clear(dataRoot);
            try {
                Path packedJar = applicationJar(active, store);
                launch = ClassDataSharing.plan(ClassDataSharing.dirFor(dataRoot, packedJar), packedJar, JVM);
                appProcess = spawnApplication(appPort, active, store, launch);
            } catch (IOException e) {
                Optional<PartsManifest> back = justSwitched ? rollBack(active, store, splash, e) : Optional.empty();
                if (back.isPresent()) {
                    active = back.get();
                    justSwitched = false;
                    continue;
                }
                log.error("Could not start the application: {}", e.getMessage(), e);
                splash.setError(e.getMessage());
                Thread.sleep(12_000);
                System.exit(1);
                return;
            }
            Thread reaper = installOrphanReaper(appProcess, appPort, bearer);
            boolean ready = waitForAppReady(appPort, appProcess, splash);
            if (ready) {
                splash.setAppRunning();
                Boot.startedWell(store.root());
                // The next step towards faster starts of this release, behind the one that just
                // finished (ClassDataSharing).
                ClassDataSharing.prepareInBackground(launch, appProcess, javaExecutable(), JVM);
            } else if (launch.stage() != ClassDataSharing.Stage.PACKED && !appProcess.isAlive()
                    && appProcess.exitValue() != EXIT_CODE_UPDATE) {
                // Died before answering while started from the extracted copy: the copy is suspect,
                // so it goes, and this start is made again from the release's own jar - which it
                // cannot come back to, since that start is PACKED (ClassDataSharing).
                log.warn("Proxyma stopped while starting from {} - starting it again from the release's own jar",
                        launch.jar());
                ClassDataSharing.discard(launch);
                try { Runtime.getRuntime().removeShutdownHook(reaper); } catch (IllegalStateException ignored) {}
                splash = SplashWindow.create();
                splash.setChromaStarting();
                continue;
            } else if (justSwitched) {
                // A release the updater just switched to that died starting, OR NEVER ANSWERED: one
                // that stayed alive without answering used to be waited on for ever, and the
                // single-instance lock then turned every later start into a window onto a dead port.
                stop(appProcess);
                try { Runtime.getRuntime().removeShutdownHook(reaper); } catch (IllegalStateException ignored) {}
                splash = SplashWindow.create();
                Optional<PartsManifest> back = rollBack(active, store, splash,
                        new IOException("it did not answer within " + APP_READY_TIMEOUT_MS / 60_000 + " minutes"));
                if (back.isPresent()) {
                    active = back.get();
                    justSwitched = false;
                    continue;
                }
            }

            int exitCode = appProcess.waitFor();
            // The child is already gone; drop the hook so a restart loop does not stack one per
            // iteration, and so a later exit does not try to reap a dead process.
            try { Runtime.getRuntime().removeShutdownHook(reaper); } catch (IllegalStateException ignored) {}
            if (exitCode == EXIT_CODE_UPDATE) {
                log.info("Restart signal received");
                splash = SplashWindow.create();
                // Exit code 3 means "start me again", not specifically "an update is staged" -
                // SystemController's restart and the desktop reset both use it. A staged release is
                // switched to here; a pending reset is carried out at the top of the loop, after it,
                // so a new release is in place before the data is deleted.
                Optional<PartsManifest> switched = store.promoteStaged();
                if (switched.isPresent()) {
                    splash.setStatus("Applying update");
                    log.info("Switching from {} to {}", active.version(), switched.get().version());
                    active = switched.get();
                    justSwitched = true;
                } else {
                    justSwitched = false;
                }
                try {
                    ensurePython(active, store, splash);
                } catch (Exception e) {
                    Optional<PartsManifest> back = justSwitched ? rollBack(active, store, splash, e) : Optional.empty();
                    if (back.isEmpty()) throw e;
                    active = back.get();
                    justSwitched = false;
                }
                splash.setChromaStarting();
            } else {
                break;
            }
        }
    }

    /**
     * Goes back from a release the launcher just switched to that could not be prepared or started,
     * and prepares the one before it.
     *
     * @return the release now active, or empty when there is nothing to go back to
     */
    private static Optional<PartsManifest> rollBack(PartsManifest failed, PartsStore store, Splash splash,
                                                    Exception why) throws Exception {
        Optional<PartsManifest> back = store.rollback();
        if (back.isEmpty()) return back;
        log.error("Proxyma {} did not start ({}) - going back to {}", failed.version(), why.getMessage(), back.get().version());
        splash.setStatus("The update did not start - going back to " + back.get().version());
        ensurePython(back.get(), store, splash);
        return back;
    }

    // A hung application and everything it started: descendants first, so nothing is reparented and
    // left behind (the reaper's own order).
    private static void stop(Process app) throws InterruptedException {
        if (!app.isAlive()) return;
        app.descendants().forEach(ProcessHandle::destroyForcibly);
        app.destroyForcibly();
        app.waitFor(30, TimeUnit.SECONDS);
    }

    /**
     * Brings up the window of a Proxyma that is already running. The running application opens it
     * - it holds the access code, and hands the window its way in (WindowController). If it does not
     * answer, it is still starting, and Proxyma's own window is started here with the code read from
     * the data directory: it waits for the app by itself.
     */
    private static void openRunningWindow(PartsStore store, Path dataRoot) {
        String code;
        try {
            code = AccessCode.resolve(System.getenv("PROXYMA_ACCESS_CODE"), dataRoot);
        } catch (Exception e) {
            code = null;
        }
        if (code != null && post(APP_PORT, "/api/v1/desktop/window", AccessCode.bearerToken(code), 2_000)) return;
        store.active().ifPresent(m -> useWindowOf(m, store));
        String url = APP_URL + APP_PORT;
        // No one-time code can be minted from here, so a browser - the fallback where Proxyma's own
        // window is missing - gets the plain address and the access screen.
        AppWindow.open(url, code, () -> url, OptionalLong.empty());
    }

    /** POSTs an empty body to the application; true on a 2xx answer. */
    private static boolean post(int port, String path, String bearer, int readTimeoutMs) {
        try {
            HttpURLConnection conn = (HttpURLConnection)
                    URI.create(APP_URL + port + path).toURL().openConnection();
            conn.setRequestMethod("POST");
            // Required for ANY request body, the empty one included. Without it getResponseCode()
            // threw "cannot write to a URLConnection if doOutput=false", so the reaper's "graceful"
            // quit never reached the app and every reap was a forced kill.
            conn.setDoOutput(true);
            conn.setConnectTimeout(500);
            conn.setReadTimeout(readTimeoutMs);
            conn.setFixedLengthStreamingMode(0);
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer " + bearer);
            int status = conn.getResponseCode();
            return status >= 200 && status < 300;
        } catch (Exception e) {
            log.debug("POST {} failed: {}", path, e.toString());
            return false;
        }
    }

    // -- Parts -----------------------------------------------------------------

    /**
     * The release to start, with every part of it installed - downloading whatever is missing.
     *
     * <p>Which release: the one already active, unless this launcher is newer than it (a newer
     * installer run over an older install), in which case this launcher's own release. A first
     * start has nothing active and takes its own release too.
     */
    private static PartsManifest ensureParts(PartsStore store, Splash splash) throws Exception {
        String platform = Platform.current();
        if (platform == null) throw new IOException("Proxyma does not run on this platform.");
        String version = ownVersion();
        Optional<PartsManifest> active = store.active();

        PartsManifest target;
        if (active.isPresent() && !Versions.isNewer(version, active.get().version())) {
            target = active.get();
        } else {
            splash.setStatus("Looking up Proxyma " + version);
            target = fetchManifest(version, platform);
            // The newest-release fallback names no version to check against, so it may only move
            // an install forward: never below the release it already runs.
            if (active.isPresent() && Versions.isNewer(active.get().version(), target.version())) {
                log.warn("The release offered ({}) is older than the one installed ({}) - keeping it",
                        target.version(), active.get().version());
                target = active.get();
            }
        }

        List<PartsManifest.Part> missing = store.missing(target);
        if (!missing.isEmpty()) {
            long total = missing.stream().mapToLong(PartsManifest.Part::size).sum();
            log.info("Downloading {} part(s) of Proxyma {}, {} MB", missing.size(), target.version(), total >> 20);
            splash.setDownloading("Downloading Proxyma", 0);
            try {
                new PartsFetcher(store, PartsTransport.jdk()).installMissing(target,
                        (part, done, all) -> splash.setDownloading(
                                "Downloading Proxyma - " + (done >> 20) + " of " + (all >> 20) + " MB",
                                all == 0 ? 1.0 : (double) done / all),
                        () -> false);
            } catch (IOException e) {
                throw new IOException("Proxyma could not download its components (" + e.getMessage()
                        + "). Check the internet connection, then start Proxyma again - it continues "
                        + "where it stopped.", e);
            }
        }
        if (active.isEmpty() || !active.get().equals(target)) store.activate(target);
        return target;
    }

    /** This release's manifest, falling back to the newest stable one when it is no longer published. */
    private static PartsManifest fetchManifest(String version, String platform) throws IOException {
        String override = System.getenv("PROXYMA_RELEASES_URL");
        String base = override != null && !override.isBlank() ? (override.endsWith("/") ? override : override + "/") : null;
        String own = (base != null ? base : RELEASES) + "v" + version + "/";
        String latest = base != null ? base + "latest/" : RELEASES_LATEST;
        boolean dev = Versions.devNumber(version) >= 0;
        ReleaseSignatures keys = ReleaseSignatures.fromClasspath();
        PartsTransport transport = PartsTransport.jdk();
        try {
            // A dev key counts only for a dev installer - one somebody chose to run. The manifest
            // must BE this version: a genuine older release's files served under this tag are
            // signed, and would otherwise install as if they were it (editions-assembly-18).
            return PartsRelease.fetch(transport, own, platform, keys, dev, version);
        } catch (PartsRelease.NotPublished e) {
            // Dev prereleases are pruned after a few newer ones, and an installer can outlive its
            // release. The newest stable release is always a correct thing to install instead.
            log.warn("Proxyma {} is not published any more - installing the newest release instead", version);
            try {
                return PartsRelease.fetch(transport, latest, platform, keys, false, null);
            } catch (IOException e2) {
                throw new IOException("Proxyma " + version + " is no longer available. Download the current "
                        + "installer from proxyma.ai.", e2);
            }
        } catch (IOException e) {
            throw new IOException("Proxyma could not reach its download location (" + e.getMessage()
                    + "). Check the internet connection, then start Proxyma again.", e);
        }
    }

    /** The version this launcher was built as, from the build-info Spring Boot's plugin writes. */
    static String ownVersion() {
        try (InputStream in = ProxymaLauncher.class.getResourceAsStream("/META-INF/build-info.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty("build.version");
                if (v != null && !v.isBlank()) return v.trim();
            }
        } catch (IOException ignored) {
            // Falls through to the failure below.
        }
        throw new IllegalStateException("The launcher does not know its own version (no build-info in the jar)");
    }

    private static Path partDir(PartsManifest m, PartsStore store, String name) {
        return m.part(name).map(store::dir).orElse(null);
    }

    /**
     * Points AppWindow at this release's window. On Windows the window is a part, and its small
     * application files (main.js and the icons) travel in the app part so a change to them does not
     * download all of Electron again - they are copied into the window before it starts. On Linux
     * the window is in the .deb, beside the working directory.
     */
    private static void useWindowOf(PartsManifest m, PartsStore store) {
        Path window = partDir(m, store, "window");
        if (window == null) return;
        Path appFiles = partDir(m, store, "app") == null ? null : partDir(m, store, "app").resolve("window-app");
        if (appFiles != null && Files.isDirectory(appFiles)) {
            try {
                syncDirectory(appFiles, window.resolve("resources").resolve("app"));
            } catch (IOException e) {
                log.warn("Could not update the window's application files: {}", e.getMessage());
            }
        }
        System.setProperty(AppWindow.WINDOW_DIR_PROPERTY, window.toString());
    }

    /** Makes {@code target} hold exactly what {@code source} holds, writing only what differs. */
    static void syncDirectory(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        Set<Path> wanted = new HashSet<>();
        try (var walk = Files.walk(source)) {
            for (Path src : walk.toList()) {
                Path rel = source.relativize(src);
                Path dst = target.resolve(rel.toString());
                wanted.add(rel);
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else if (!Files.isRegularFile(dst) || Files.mismatch(src, dst) != -1) {
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        try (var walk = Files.walk(target)) {
            for (Path dst : walk.sorted(Comparator.reverseOrder()).toList()) {
                if (!wanted.contains(target.relativize(dst))) Files.deleteIfExists(dst);
            }
        }
    }

    // -- Python ----------------------------------------------------------------

    /** Remembers which python part a runtime was built from, so a new one rebuilds it. */
    private static final String RUNTIME_MARKER = ".proxyma-python-part";

    /**
     * The Python runtime in the data directory, built from the active release's python part.
     *
     * <p>Rebuilt WHOLE when that part changes, rather than upgraded package by package: the part is
     * a pinned set of wheels, and pip installing a new set over an old one is how an environment
     * ends up holding a mix nobody tested. A rebuild takes a minute and happens only when a release
     * changes the Python side. ChromaDB's data is not in the runtime and is untouched by it.
     */
    private static void ensurePython(PartsManifest active, PartsStore store, Splash splash) throws Exception {
        Optional<PartsManifest.Part> python = active.part("python");
        if (python.isEmpty()) throw new IOException("Release " + active.version() + " carries no Python runtime");
        Path source = store.dir(python.get());
        Path runtimeRoot = PythonLayout.preferredRoot(AppPaths.create().dataRoot());
        Path marker = runtimeRoot.resolve(RUNTIME_MARKER);
        if (Files.isRegularFile(PythonLayout.interpreter(runtimeRoot)) && Files.isRegularFile(marker)
                && Files.readString(marker).trim().equals(python.get().id())) {
            splash.setPrereqsReady();
            return;
        }

        splash.setCheckingPrereqs();
        log.info("Building the Python runtime at {} from part {}", runtimeRoot, python.get().id());
        PartsStore.deleteRecursively(runtimeRoot);
        if (Files.exists(runtimeRoot)) {
            throw new IOException("The old Python runtime at " + runtimeRoot + " is in use and cannot be "
                    + "replaced. Close anything using it, then start Proxyma again.");
        }

        splash.setInstallingPrereqs("the Python runtime");
        installPython(source, runtimeRoot, splash);
        // Before pip is installed, since pip now comes from these wheels too.
        splash.setStatus("Verifying Python packages");
        verifyWheelChecksums(source.resolve("wheels"));
        if (!hasPip(runtimeRoot)) {
            splash.setInstallingPrereqs("pip");
            installPip(source, runtimeRoot, splash);
        }
        splash.setInstallingPrereqs("Python packages");
        // --no-compile, then compileall on every core: pip byte-compiles each package one file at a
        // time, and that WAS the rebuild - measured 2026-10-10 on the 0.32.1 part (121 wheels), 74s
        // and 75s with pip compiling against 8s without, plus 9s for compileall -j 0 to write the
        // same 10,257 .pyc files. A rebuild runs at the first start after every release that
        // changes the Python side, and the minute it saves is a minute of the splash and of the
        // app's restart screen. The files are compiled rather than left to compile on first
        // import because runPython runs with PYTHONDONTWRITEBYTECODE (LocalPythonSandbox), so
        // whatever is missing there is compiled again on every call.
        runAndWait(splash, List.of(PythonLayout.interpreter(runtimeRoot).toString(), "-m", "pip", "install",
                "--no-index", "--find-links=" + source.resolve("wheels"),
                "-r", source.resolve("requirements.txt").toString(), "--no-warn-script-location",
                "--no-compile"));
        compilePackages(runtimeRoot);

        // Last, so an interrupted build leaves no marker and is redone at the next start.
        Files.writeString(marker, python.get().id(), StandardCharsets.US_ASCII);
        splash.setPrereqsReady();
        splash.setStatus("Prerequisites installed");
    }

    /**
     * Byte-compiles the installed packages on every core. Best effort: compileall exits non-zero
     * when any one file fails to compile, and some packages ship files that never could (olefile
     * carries a Python 2 module) - pip skipped those too. A file left uncompiled is compiled when
     * it is first imported, so nothing here is a reason to stop the start.
     */
    private static void compilePackages(Path runtimeRoot) {
        Path site = PythonLayout.sitePackages(runtimeRoot);
        if (site == null) return;
        try {
            Process p = new ProcessBuilder(PythonLayout.interpreter(runtimeRoot).toString(),
                    "-m", "compileall", "-q", "-j", "0", site.toString())
                    .redirectErrorStream(true).start();
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            if (!p.waitFor(10, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                log.warn("Compiling the Python packages took over ten minutes - stopped; they compile on first use");
            } else {
                log.info("Compiled the Python packages (compileall exited {})", p.exitValue());
            }
        } catch (Exception e) {
            log.warn("Could not compile the Python packages ({}) - they compile on first use", e.getMessage());
        }
    }

    // `python -m pip --version` rather than looking for the executable: the relocatable CPython
    // Linux builds ship pip as a module with no console script until something generates one.
    private static boolean hasPip(Path runtimeRoot) {
        Path interpreter = PythonLayout.interpreter(runtimeRoot);
        if (!Files.isRegularFile(interpreter)) return false;
        try {
            Process p = new ProcessBuilder(interpreter.toString(), "-m", "pip", "--version")
                    .redirectErrorStream(true).start();
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            return p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // Verifies every wheel against the SHA-256 manifest the build writes beside them
    // (wheels/SHA256SUMS.txt). The part as a whole has already passed its own digest, so this is a
    // second line - it catches a build that shipped a wheel the manifest does not describe, with a
    // clear message instead of a confusing pip failure.
    private static void verifyWheelChecksums(Path wheelsDir) throws IOException {
        Path manifest = wheelsDir.resolve("SHA256SUMS.txt");
        if (!Files.isRegularFile(manifest)) {
            log.debug("No SHA256SUMS.txt manifest found in {} - skipping wheel checksum verification", wheelsDir);
            return;
        }
        int verified = 0;
        for (String line : Files.readAllLines(manifest)) {
            line = line.trim();
            if (line.isEmpty()) continue;
            int sep = line.indexOf(' ');
            if (sep < 0) continue;
            String expectedHash = line.substring(0, sep).trim().toLowerCase(Locale.ROOT);
            String fileName = line.substring(sep).trim();
            Path wheelFile = wheelsDir.resolve(fileName);
            if (!Files.isRegularFile(wheelFile)) {
                throw new IOException("Missing Python package: " + fileName
                        + " (listed in SHA256SUMS.txt but not found in " + wheelsDir + ")");
            }
            if (!expectedHash.equalsIgnoreCase(PartsFetcher.sha256(wheelFile))) {
                throw new IOException("Checksum mismatch for Python package " + fileName
                        + " - the file may be corrupted or tampered with.");
            }
            verified++;
        }
        log.debug("Verified SHA-256 checksums for {} wheel(s)", verified);
    }

    private static void installPython(Path source, Path runtimeRoot, Splash splash) throws Exception {
        // Windows ships the embeddable zip; Linux a relocatable CPython tarball. Matched by suffix
        // rather than by exact filename so bumping the bundled Python version is a build change
        // only.
        String suffix = Platform.windows() ? ".zip" : ".tar.gz";
        Path archive;
        try (var s = Files.list(source)) {
            archive = s.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(suffix) && (n.startsWith("python-") || n.startsWith("cpython-"));
                    })
                    .findFirst()
                    .orElseThrow(() -> new FileNotFoundException(
                            "No Python " + suffix + " archive found in " + source));
        }
        log.info("Extracting the Python runtime from {} into {}", archive, runtimeRoot);
        Files.createDirectories(runtimeRoot);

        if (Platform.windows()) {
            PartsFetcher.unzip(archive, runtimeRoot);
            patchEmbeddablePth(runtimeRoot);
        } else {
            // --strip-components=1 drops the leading "python/" directory that relocatable CPython
            // archives are rooted at, so bin/ and lib/ land directly in runtimeRoot.
            runAndWait(splash, List.of("tar", "-xzf", archive.toString(),
                    "-C", runtimeRoot.toString(), "--strip-components=1"));
            makeExecutable(PythonLayout.interpreter(runtimeRoot));
        }
    }

    // The Windows embeddable distribution ships with `import site` commented out in its ._pth file,
    // which disables site-packages entirely - so pip installs succeed and then nothing can be
    // imported. Not applicable to the Linux build, which has no ._pth at all.
    private static void patchEmbeddablePth(Path runtimeRoot) throws IOException {
        try (var s = Files.list(runtimeRoot)) {
            s.filter(p -> p.getFileName().toString().endsWith("._pth")).findFirst().ifPresent(pth -> {
                try {
                    Files.writeString(pth, Files.readString(pth).replace("#import site", "import site"));
                } catch (IOException e) {
                    log.warn("Could not patch {}: {}", pth, e.getMessage());
                }
            });
        }
    }

    private static void makeExecutable(Path file) {
        try {
            if (!Files.isRegularFile(file)) return;
            Set<PosixFilePermission> perms = new HashSet<>(Files.getPosixFilePermissions(file));
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        } catch (Exception e) {
            // A filesystem with no POSIX permission view (or Windows) - nothing to do.
            log.debug("Could not set the executable bit on {}: {}", file, e.getMessage());
        }
    }

    private static void installPip(Path source, Path runtimeRoot, Splash splash) throws Exception {
        Path getPip = source.resolve("get-pip.py");
        if (!Files.exists(getPip)) {
            throw new IOException("pip is missing from the Python runtime at " + runtimeRoot
                    + ", and the release carries no get-pip.py.");
        }
        // Copied into the runtime, which is writable by definition - get-pip.py writes next to
        // itself while bootstrapping, and a part directory is not somewhere to write.
        Path pipDst = runtimeRoot.resolve("get-pip.py");
        Files.copy(getPip, pipDst, StandardCopyOption.REPLACE_EXISTING);
        runAndWait(splash, getPipCommand(PythonLayout.interpreter(runtimeRoot), pipDst, source.resolve("wheels")));
    }

    /**
     * get-pip.py, installing pip from the part's own pip wheel - never from PyPI.
     *
     * <p>get-pip.py carries a pip to RUN, but has it install pip from an index, and that index was
     * PyPI: about five seconds of every rebuild, and a rebuild that FAILED offline or behind a proxy
     * that blocks PyPI - leaving an app that cannot start, since nothing runs without its Python.
     * requirements.txt names pip so the part carries the wheel, and get-pip.py hands these arguments
     * on to pip. A part built before that has no pip wheel and is installed the old way.
     *
     * <p>--no-compile for the reason the packages take it in ensurePython: compilePackages compiles
     * pip with everything else, on every core. Measured 2026-10-10 on the PC, offline: 5.2s
     * for get-pip.py from the wheel, 2.1s with --no-compile.
     */
    static List<String> getPipCommand(Path interpreter, Path getPip, Path wheels) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(interpreter.toString(), getPip.toString(),
                "--no-warn-script-location", "--no-compile"));
        if (hasPipWheel(wheels)) {
            cmd.addAll(List.of("--no-index", "--find-links=" + wheels));
        } else {
            log.warn("The python part carries no pip wheel - installing pip from PyPI");
        }
        return cmd;
    }

    private static boolean hasPipWheel(Path wheels) throws IOException {
        if (!Files.isDirectory(wheels)) return false;
        try (var s = Files.list(wheels)) {
            return s.anyMatch(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return n.startsWith("pip-") && n.endsWith(".whl");
            });
        }
    }

    private static void runAndWait(Splash splash, List<String> cmd) throws Exception {
        log.info("Running: {}", String.join(" ", cmd));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        // Kept, not discarded: when pip fails, its last lines are the only explanation there is.
        Deque<String> tail = new ArrayDeque<>();
        Thread drain = new Thread(() -> {
            try (var in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    synchronized (tail) {
                        tail.addLast(line);
                        if (tail.size() > 20) tail.removeFirst();
                    }
                }
            } catch (IOException ignored) {}
        }, "prereq-drain");
        drain.setDaemon(true);
        drain.start();
        int rc = p.waitFor();
        drain.join(2_000);
        if (rc != 0) {
            synchronized (tail) {
                log.error("{} exited {}:\n{}", cmd.get(0), rc, String.join("\n", tail));
            }
            throw new IOException("Setting up Python failed (exit " + rc + "). See the log for details.");
        }
    }

    // -- Application -----------------------------------------------------------

    /** The app part's jar, as the release ships it. */
    private static Path applicationJar(PartsManifest active, PartsStore store) throws IOException {
        Path appDir = partDir(active, store, "app");
        Path jar = appDir == null ? null : appDir.resolve(APP_JAR);
        if (jar == null || !Files.isRegularFile(jar)) {
            throw new FileNotFoundException("The application is missing from release " + active.version());
        }
        return jar;
    }

    /** The Java runtime this launcher runs on, which is the one the application gets. */
    private static String javaExecutable() {
        return System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
    }

    /** What an archive of loaded classes is tied to: the runtime that made it (ClassDataSharing). */
    private static final String JVM = System.getProperty("java.home") + " " + System.getProperty("java.vm.version");

    private static Process spawnApplication(int appPort, PartsManifest active, PartsStore store,
                                            ClassDataSharing.Launch launch) throws IOException {
        Path jar = launch.jar();
        useWindowOf(active, store);
        log.info("Starting Proxyma {} from {} ({})", active.version(), jar, launch.stage());

        String java = javaExecutable();
        Path tesseract = partDir(active, store, "tesseract");
        Path tesseractExe = tesseract != null && Files.isRegularFile(tesseract.resolve("tesseract.exe"))
                ? tesseract.resolve("tesseract.exe") : null;
        List<String> cmd = applicationCommand(java, jar, appPort, store.root(), partDir(active, store, "browser"),
                tesseractExe, System.getProperty(AppWindow.WINDOW_DIR_PROPERTY),
                System.getProperty("proxyma.log.dir", "logs"),
                ManagementFactory.getRuntimeMXBean().getInputArguments(), launch.jvmOptions());

        return new ProcessBuilder(cmd)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
    }

    /** The application JVM's command line. Package-private so a test can read it. */
    static List<String> applicationCommand(String java, Path jar, int appPort, Path partsRoot, Path browser,
                                           Path tesseractExe, String windowDir, String logDir,
                                           List<String> inheritedArguments, List<String> jvmOptions) {
        List<String> cmd = new ArrayList<>();
        cmd.add(java);
        // Class data sharing's, the launcher's own (ClassDataSharing) - never inherited, see below.
        cmd.addAll(jvmOptions);
        cmd.add("-Djava.awt.headless=false");
        cmd.add("-Dproxyma.chromadb.managed=true");
        // LOOPBACK, ALWAYS, for anything started by this launcher - which is every Windows
        // installer and every .deb. Two halves, both pinned here rather than documented, because a
        // system property outranks an environment variable in Spring's property order:
        //
        //   server.address              the listener itself. Nothing set it until 2026-10-14, so
        //                               Netty bound every interface (editions-assembly-01), and
        //                               PROXYMA_SERVER_ADDRESS in the environment loses to this.
        //   proxyma.network.allowed-hosts  the Host allow-list WebConfig keeps against DNS
        //                               rebinding; PROXYMA_NETWORK_ALLOWED_HOSTS loses to empty.
        //
        // The inherited-argument filter below already drops every `-Dproxyma.*` the user might
        // pass, and `-Dserver.address` with it. Reaching Proxyma from another machine is what the
        // home-server package is for; it runs the same jar without this launcher.
        cmd.add("-Dserver.address=127.0.0.1");
        cmd.add("-Dproxyma.network.allowed-hosts=");
        // Where the updater downloads and stages parts. Its presence is also what tells the
        // application it was started by this launcher and can update itself (UpdateService).
        cmd.add("-Dproxyma.parts.dir=" + partsRoot);
        if (browser != null) cmd.add("-Dproxyma.browsers.dir=" + browser);
        // The release's own Tesseract (Windows), for the offline OCR engine - it is not on PATH, and
        // pytesseract only ever looks there. Linux uses the system package.
        if (tesseractExe != null) cmd.add("-Dproxyma.ocr.tesseract-cmd=" + tesseractExe);
        if (windowDir != null) cmd.add("-D" + AppWindow.WINDOW_DIR_PROPERTY + "=" + windowDir);
        cmd.add("-Dserver.port=" + appPort);
        cmd.add("-Dproxyma.log.dir=" + logDir);
        inheritedArguments.stream()
                .filter(a -> a.startsWith("-D") && !a.startsWith("-Dproxyma.") && !a.startsWith("-Dserver.address"))
                .forEach(cmd::add);
        cmd.add("-jar");
        cmd.add(jar.toString());
        return cmd;
    }

    /** The application jar inside the app part. A contract with tools/release/parts.py. */
    static final String APP_JAR = "proxyma-desktop.jar";

    // Kills the application JVM if the launcher goes away first.
    //
    // THE BUG THIS FIXES. proxyma.exe spawns java.exe and then blocks in appProcess.waitFor().
    // Nothing tied the two together: on Windows a child process is NOT killed when its parent dies,
    // and there was no shutdown hook. So anything that ended the launcher while it was waiting -
    // End Task on proxyma.exe, a logoff, the installer stopping the app to upgrade it - left a
    // fully running java.exe behind, holding its jar open and the port bound.
    //
    // ASKS BEFORE IT KILLS. Process.destroy() is TerminateProcess on Windows - not graceful, whatever
    // the javadoc implies - and killing the JVM outright leaves ChromaDB's own child running and
    // skips every @PreDestroy. So the hook first posts to the app's own quit endpoint, the same one
    // the UI power icon uses, which closes the Spring context properly. Only if that does not stop
    // it within the grace window does it force the issue, descendants first so nothing is reparented
    // and left behind.
    //
    // A shutdown hook does not run on TerminateProcess (taskkill /F), and nothing can change that -
    // that case is what the installer's kill-by-path still covers.
    // The quit request carries the install's token: since the access code every API call needs it
    // (AccessGate), and without it the graceful quit would be a 401 and every reap a forced kill.
    private static Thread installOrphanReaper(Process appProcess, int appPort, String bearer) {
        Thread hook = new Thread(() -> {
            if (!appProcess.isAlive()) return;
            log.info("Launcher exiting while the application is still running - stopping it");
            if (!post(appPort, "/api/v1/desktop/quit", bearer, 1000)) {
                log.debug("Graceful quit request failed - forcing");
            }
            try {
                if (appProcess.waitFor(REAP_GRACE_SECONDS, TimeUnit.SECONDS)) return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // Descendants first: killing the JVM alone would reparent chroma.exe and any Python
            // helper onto the OS and leave them running, which is the same orphan one level down.
            appProcess.descendants().forEach(ProcessHandle::destroyForcibly);
            appProcess.destroyForcibly();
        }, "proxyma-orphan-reaper");
        Runtime.getRuntime().addShutdownHook(hook);
        return hook;
    }

    /**
     * Waits for the application to be READY. False when it exited first or never got there - which,
     * right after an update, is what decides whether to roll back.
     *
     * <p>/api/v1/health/ready, and a 200 from it. This polled /v1/health - a route that does not
     * exist - and took any answer under 500 as ready, so the SPA's index.html fallback, or any other
     * process answering on the port, dismissed the splash before the application had finished
     * starting (editions-assembly-25).
     */
    private static boolean waitForAppReady(int port, Process app, Splash splash) throws InterruptedException {
        long deadline = System.currentTimeMillis() + APP_READY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (!app.isAlive()) {
                splash.setError("Proxyma stopped while starting (exit " + app.exitValue() + "). See the log.");
                return false;
            }
            try {
                HttpURLConnection conn = (HttpURLConnection)
                        URI.create(APP_URL + port + "/api/v1/health/ready").toURL().openConnection();
                conn.setConnectTimeout(500);
                conn.setReadTimeout(3_000);
                if (conn.getResponseCode() == 200) return true;
            } catch (Exception ignored) {}
            Thread.sleep(500);
        }
        splash.setError("Proxyma did not respond within " + APP_READY_TIMEOUT_MS / 60_000 + " minutes. See the log.");
        return false;
    }

    /**
     * How long a started application has to answer before the splash calls the start failed. TEN
     * MINUTES, up from two (2026-10-10): the application normally answers in 15 to 35 seconds, but
     * on a slow machine whose antivirus scans the jar, the Python runtime and the databases on the
     * way in, a healthy start can take minutes - and the two-minute figure reported it as a
     * failure while the window opened behind the message, and left Boot's launcher-trying marker
     * behind so the next start distrusted a launcher part that works. Nothing waits on this to
     * notice a CRASH - an application that exits is seen on the next poll - so the only cost of a
     * long limit is how long a hung start takes to be called one.
     */
    static final long APP_READY_TIMEOUT_MS = 10 * 60_000L;

    // -- Helpers ---------------------------------------------------------------

    // Fixed application port (matches server.port in application-desktop.properties). The port is no
    // longer user-configurable, so the launcher no longer needs to read any settings file before the
    // app starts - which is what let the global settings store move entirely into the database.
    private static final int APP_PORT = 4246;
    // The literal, not localhost: the app listens on the IPv4 loopback only (applicationCommand).
    private static final String APP_URL = "http://127.0.0.1:";
    // Long enough for a clean Spring shutdown (ChromaDB stop included), short enough that a
    // logoff does not wait on us - Windows gives a shutdown hook only a few seconds anyway.
    private static final int REAP_GRACE_SECONDS = 5;
}
