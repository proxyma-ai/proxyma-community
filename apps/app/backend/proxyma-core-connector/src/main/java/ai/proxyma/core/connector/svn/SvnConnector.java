package ai.proxyma.core.connector.svn;

import ai.proxyma.core.common.net.NoProxyRules;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.CustomCaCertificates;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.SyncErrorAware;
import ai.proxyma.core.connector.files.FileTypeSupport;
import ai.proxyma.core.connector.spi.ConnectorDescriptor;
import ai.proxyma.core.connector.spi.ConnectorPlugin;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.CoreDescriptors;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import ai.proxyma.core.connector.spi.SourceContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Subversion: the svn command-line client every SVN path in the product runs - the sync of an SVN
 * source (its {@link SvnSession}) and the agent's live browse tools alike - and the plugin of the
 * SVN source type.
 *
 * <p>NO SOURCE'S STATE LIVES HERE. Every command takes the URL and the credential it runs with as
 * arguments: a session passes its own source's, a tool its caller's. This bean used to hold the
 * source being synced in fields that auth() wrote, so a fetch running beside another source's
 * configuration could read the wrong repository with the wrong account.
 */
@Service
public class SvnConnector implements ConnectorPlugin {

    private static final Logger log = LoggerFactory.getLogger(SvnConnector.class);

    private static final int PROCESS_TIMEOUT_SECONDS = 120;

    // `list --depth infinity` walks the whole repository server-side and is the only subcommand
    // whose cost grows with the repository rather than with one file, so it gets its own ceiling
    // (spec 04 section 4). Under the shared 120s every listing of a large repository through a
    // proxy timed out, which is not a slow source but a source that can never finish: the sync
    // fails, nothing is ever indexed, and the next scheduled pass fails the same way forever.
    private static final int LIST_TIMEOUT_SECONDS = 1800;

    // The largest file a sync fetches - the crawler's ceiling for a PDF. `svn cat` was read whole
    // into memory with no ceiling at all, so one installer committed to a repository could take the
    // server's heap down for every user.
    static final long MAX_FETCH_BYTES = 200L * 1024 * 1024;

    // What is kept of svn's stderr: its error lines, which is all anything reads.
    private static final int MAX_STDERR_BYTES = 64 * 1024;

    static final String NOT_INSTALLED =
            "The svn command-line client is not installed on this server - install Subversion to sync SVN sources";

    // The `svn` CLI is a native subprocess and does not inherit the JVM's HttpClient/RestClient
    // proxy configuration directly - it only respects lowercase http_proxy/https_proxy env vars
    // or an explicit servers-config proxy option. RestClientConfig.setSystemProxyProperties()
    // resolves the admin's Settings > Network proxy config (mode/host/port/noProxy) into JVM system
    // properties (http(s).proxyHost/proxyPort/nonProxyHosts), which this module reads rather than
    // depending on proxyma-core-network. Resolved per command, so the no-proxy rules are evaluated
    // against the actual target host of each one.
    private String resolveProxyHost() {
        String host = System.getProperty("https.proxyHost");
        return (host != null && !host.isBlank()) ? host : System.getProperty("http.proxyHost");
    }

    private String resolveProxyPort() {
        String port = System.getProperty("https.proxyPort");
        return (port != null && !port.isBlank()) ? port : System.getProperty("http.proxyPort");
    }

    // The same reading of the no-proxy list every REST client makes (NoProxyRules) - this used to be
    // a weaker copy that knew only exact names and "*suffix", so svn went through the proxy for
    // hosts every other client reached directly.
    static boolean bypassesProxy(String host, String nonProxyHosts) {
        if (host == null) return false;
        return NoProxyRules.shouldBypass(host, NoProxyRules.parse(nonProxyHosts));
    }

    private static final String[] INDEXED_EXTENSIONS = {
        "pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx",
        "txt", "md",
        "c", "h", "cpp", "hpp", "cc", "hh", "cxx", "hxx",
        "s", "asm", "ld", "lds", "lsl",
        "arxml", "dbc", "a2l",
        "sh", "bat", "cmd", "ps1"
    };

    // - Records -

    public record SvnFileEntry(String filePath, String revision, LocalDateTime lastModified, String author) {

        public SvnFileEntry(String filePath, String revision, LocalDateTime lastModified) {
            this(filePath, revision, lastModified, null);
        }
    }

    /**
     * One entry in a BROWSE listing - unlike {@link SvnFileEntry} it carries the kind, because the
     * caller's next move depends on it: descend into a DIRECTORY, read a FILE.
     *
     * @param path relative to the listed directory - a bare name at depth "immediates", a
     *             subtree-relative path at depth "infinity"
     * @param size bytes for a FILE, null for a DIRECTORY (svn sends no size for one)
     */
    public record SvnTreeEntry(String path, String kind, Long size, String revision,
                               LocalDateTime lastModified) {}

    /** One file's text as a tool reads it - cut at the tool's bound, and whether it was. */
    public record SvnText(String text, boolean truncated) {}

    private record SvnCommand(List<String> args, String stdinPassword) {}

    // The command that starts svn - a list so a test can start a stand-in with its arguments.
    private volatile List<String> launcher = List.of("svn");

    private volatile CustomCaCertificates customCa;

    @Autowired(required = false)
    void setCustomCaCertificates(CustomCaCertificates customCa) {
        this.customCa = customCa;
    }

    void setLauncher(List<String> launcher) {
        this.launcher = List.copyOf(launcher);
    }

    // =========================================================================
    // The SVN source type
    // =========================================================================

    private volatile SvnOwnerPreferences preferences;

    @Autowired(required = false)
    void setPreferences(SvnOwnerPreferences preferences) {
        this.preferences = preferences;
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return CoreDescriptors.of(ConnectorType.SVN);
    }

    /**
     * A session over one repository, as its OWNER: the repository URL the source stored at
     * registration (a path relative to the connection's root is joined onto it, for a configuration
     * that names one), the connection's root - the one server its credential may go to - and its
     * credential, and the source's scope. Which extensions a sync keeps: the source's own file types,
     * else the owner's {@code svn.indexed-extensions}, else the built-in list.
     */
    @Override
    public ConnectorSession open(SourceContext context) {
        ResolvedCredentials connection = context.connection();
        String root = connection.hasBaseUrl() ? connection.baseUrl() : null;
        String repo = context.get(REPO_PATH);
        String url = repo == null || repo.isBlank() ? null
                : SvnUrls.isUrl(repo) || root == null ? repo.strip()
                : root.replaceAll("/+$", "") + "/" + normalizeRepoPath(repo).replaceAll("^/+", "");
        String password = connection.secret("password") != null ? connection.secret("password") : connection.soleSecret();
        boolean credential = connection.username() != null && !connection.username().isBlank()
                && password != null && !password.isBlank();
        SvnOwnerPreferences prefs = preferences;
        Set<String> extensions = effectiveExtensions(FileTypeSupport.parseFileTypes(context.scope().fileTypes()),
                parseIndexedExtensions(prefs == null ? null : prefs.indexedExtensions(context.ownerId())));
        return new SvnSession(this, url, root, credential ? connection.username() : null, credential ? password : null,
                extensions, context.scope().recursive());
    }

    /** The field a source's repository URL is stored under - the whole URL, fixed at registration. */
    public static final String REPO_PATH = "repoPath";

    /** The name the person gave it, else the repository's last folder. */
    @Override
    public String displayName(Map<String, String> config) {
        String name = config.get("repoName");
        if (name != null && !name.isBlank() && !name.equals("!")) return name.strip();
        String url = config.get(REPO_PATH);
        if (url == null || url.isBlank()) return url;
        String[] segments = url.replaceAll("/+$", "").split("/");
        return segments.length == 0 ? url : java.net.URLDecoder.decode(segments[segments.length - 1], StandardCharsets.UTF_8);
    }

    // The repository itself - an http(s) one is a page a browser opens.
    @Override
    public String webUrl(Map<String, String> config, String serverUrl) {
        String url = config.get(REPO_PATH);
        return url == null ? null : url.strip();
    }

    /**
     * Turns an svn failure into the sentence spec 04 section 5.4 asks for.
     *
     * <p>The CLI reports everything as exit code 1 plus stderr, and the line that matters is often
     * not the first: an authentication failure arrives as `E170013 Unable to connect` followed by
     * `E215004`, so the authentication codes are checked BEFORE the connection ones and both are
     * matched against the whole message rather than its first line. Without this the source card
     * carried "svn exited with code 1. Stderr: svn: E170013: ...", which names no action at all.
     *
     * @param credentialConfigured whether a username was configured for this source - it decides
     *                             between "save a credential" and "the saved one was rejected",
     *                             which are different jobs for the reader
     */
    public static String describeFailure(Exception e, String url, boolean credentialConfigured) {
        String message = e == null || e.getMessage() == null ? "" : e.getMessage();
        String lower = message.toLowerCase();
        if (message.contains("E215004") || message.contains("E170001")
                || lower.contains("authorization failed") || lower.contains("authentication failed")) {
            return credentialConfigured
                    ? "The SVN server rejected the saved SVN credential - check it in Settings > Connections > SVN"
                    : "The SVN server requires a login - save your SVN credential (user:password) in Settings > Connections > SVN";
        }
        // Certificate validation is on, since 2026-10-14 - every command used to accept an unknown
        // CA and a name mismatch, which handed the credential to anyone able to intercept it.
        if (message.contains("E230001") || lower.contains("certificate verification failed")
                || lower.contains("server certificate")) {
            return "The SVN server's certificate is not trusted for " + url
                    + " - if it is signed by your company's own CA, add that CA in Settings > Network";
        }
        if (message.contains("E170000") || message.contains("E160013") || message.contains("E200009")
                || lower.contains("non-existent") || lower.contains("path not found")) {
            return "Not found in the SVN repository: " + url
                    + " - use the repository URL (for example https://host/svn/Repo/path), not a web viewer link";
        }
        if (lower.contains("timed out")) {
            return "The SVN server took too long to answer for " + url;
        }
        if (message.contains("E170013") || message.contains("E175002") || message.contains("E731001")) {
            return "Could not connect to the SVN server for " + url;
        }
        for (String line : message.split("\\R")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                return trimmed.length() > 300 ? trimmed.substring(0, 300) : trimmed;
            }
        }
        return SyncErrorAware.summarize(e);
    }

    /**
     * Checks that a repository URL answers for these credentials, BEFORE a source is registered.
     *
     * <p>`svn info`, not a listing: registration only needs to know the URL is a real repository path
     * this account can read, and `list --depth infinity` on a large repository is the slowest call
     * this class makes. Throws with svn's own stderr, which names the actual problem - "Unable to
     * connect", "E170013", "Authorization failed", "path not found".
     */
    public void probeAs(String url, String username, String password) throws IOException {
        runText(assemble(username, password, "info", "--no-auth-cache", url));
    }

    /**
     * Turns a pasted web-browser URL into the repository URL svn needs, and leaves anything else alone.
     *
     * <p>A VisualSVN Server web UI shows a repository at {@code https://host/!/#Repo/view/head/path},
     * while svn itself needs {@code https://host/svn/Repo/path} - the {@code /svn/} location is that
     * server's default. People copy the browser address, and nothing converted it, so the source
     * pointed at an HTML page, listing failed and the source indexed zero items (reported 2026-09-14
     * against exactly such a server). Only the full web URL is rewritten here - the {@code /!/#}
     * marker makes it unambiguous - to {@code /svn/Repo/<path>}. For the part after {@code #} pasted
     * as a path under a root URL, see {@link #normalizeRepoPath}. The revision ("head" or a number)
     * is dropped: a source indexes the head.
     */
    public static String normalizeRepoUrl(String url) {
        if (url == null) return null;
        String trimmed = url.trim();
        int bang = trimmed.indexOf("/!/#");
        if (bang > 0) {
            String origin = trimmed.substring(0, bang);
            trimmed = origin + "/svn/" + normalizeRepoPath(trimmed.substring(bang + "/!/#".length()));
        }
        return trimmed.replaceAll("/+$", "");
    }

    /**
     * The web UI's {@code Repo/view/<rev>/<path>}, pasted as a path relative to the SVN root URL, back
     * to the {@code Repo/<path>} svn needs.
     *
     * <p>ONLY when {@code view/<rev>} is the SECOND segment, straight after the repository name, which
     * is where the web UI puts it. Removing it anywhere would corrupt a real folder named "view" with a
     * year under it - {@code docs/view/2024/report} - which is a perfectly ordinary repository path.
     */
    public static String normalizeRepoPath(String path) {
        if (path == null) return null;
        return path.trim().replaceFirst("^/*([^/]+)/view/(?:head|HEAD|\\d+)(?=/|$)", "$1");
    }

    /** Where one item's bytes live: the repository URL, the path under it, and the file itself. */
    record SvnTarget(String repoUrl, String repoPath, String filePath) {}

    /**
     * Resolves the fetch target from the item, FALLING BACK to the item id and the configured URL.
     *
     * <p>The drain loop rebuilds ItemMetadata from the database row, which carries no extras at all
     * (framework 3.5) - so demanding `repoUrl` and `filePath` extras made every SVN content fetch in
     * that loop throw. Everything needed is recoverable without them: a listing uses repoPath "",
     * so an item id IS the file path relative to the source's own repository URL, which its session
     * holds. Extras an item does carry still win.
     */
    static SvnTarget resolveTarget(ItemMetadata metadata, String configuredSvnUrl) {
        String repoUrl = metadata.getExtra("repoUrl");
        String repoPath = metadata.getExtra("repoPath");
        String filePath = metadata.getExtra("filePath");

        if (repoUrl == null || repoUrl.isBlank()) repoUrl = configuredSvnUrl;
        if (filePath == null || filePath.isBlank()) filePath = metadata.getId();
        if (repoPath == null) repoPath = "";

        if (repoUrl == null || repoUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "No SVN repository URL is configured for this source, and the item carries none");
        }
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("SVN item has no file path and no id to fall back on");
        }
        return new SvnTarget(repoUrl, repoPath, filePath);
    }

    // =========================================================================
    // SVN CLI - file listing
    // =========================================================================

    /**
     * @param recursive false lists only the files directly in the repository URL ("immediates"),
     *                  which is what "include subfolders" off means for a folder too.
     */
    public List<SvnFileEntry> listFiles(String repoUrl, String repoPath, Set<String> allowedExtensions,
                                        boolean recursive, String username, String password) throws IOException {
        String fullUrl = buildUrl(repoUrl, repoPath);
        SvnCommand sc = assemble(username, password, "list", "--xml", "--depth", recursive ? "infinity" : "immediates",
                "--no-auth-cache", fullUrl);
        // Parsed as it arrives: the listing of a large repository is hundreds of megabytes of XML,
        // and it used to be held as bytes, as a String and as a DOM before the first entry was read.
        return run(sc, in -> new Output<>(indexEntries(in, allowedExtensions), true));
    }

    /**
     * Which extensions a sync keeps: the source's own list when it has one, else the
     * {@code svn.indexed-extensions} setting, else null for the built-in list.
     *
     * <p>The source's list wins outright rather than being intersected with the setting, for the
     * reason FileTypeSupport.parseFileTypes gives for a folder: an extension a person picked for
     * this source is an explicit instruction, and narrowing it again by a setting they may not even
     * know exists would drop exactly what they asked for.
     */
    static Set<String> effectiveExtensions(Set<String> sourceFileTypes, Set<String> settingExtensions) {
        return sourceFileTypes != null ? sourceFileTypes : settingExtensions;
    }

    // =========================================================================
    // SVN CLI - explicit-credential overloads for the TOOL path
    // =========================================================================

    /**
     * One directory level, or the whole subtree, as the CALLING USER.
     *
     * <p>{@code depth} is the reason this is not just listFiles with credentials bolted on. The
     * sync path always wants {@code infinity} because it is enumerating everything to index it; a
     * person asking "what is in trunk/src" wants one level, and getting the recursive answer to
     * that question on a large repository is both slow on the server and useless in a reply. So
     * the caller chooses, and the tool defaults to one level.
     *
     * @param maxEntries the most the caller will use - one more is returned when there are more, so
     *                   the caller can say the listing was cut, and svn is stopped there rather than
     *                   left to list the rest of a large repository into memory
     */
    public List<SvnTreeEntry> listFilesAs(String repoUrl, String repoPath, String username,
                                          String password, boolean recursive, int maxEntries) throws IOException {
        String fullUrl = buildUrl(repoUrl, repoPath);
        SvnCommand sc = assemble(username, password,
                "list", "--xml", "--depth", recursive ? "infinity" : "immediates",
                "--no-auth-cache", fullUrl);
        return run(sc, in -> {
            List<SvnTreeEntry> entries = new ArrayList<>();
            boolean complete = parseListing(in, entry -> {
                entries.add(entry.asTreeEntry());
                return entries.size() <= maxEntries;
            });
            return new Output<>(entries, complete);
        });
    }

    /**
     * One file's text, as the calling user, read no further than {@code maxBytes}.
     *
     * <p>svn has no ranged read, so `svn cat` sends the whole file - and it used to be read whole into
     * memory before the tool kept its first 200,000 characters, so a model asked about a large binary
     * in a repository made the server allocate all of it. svn is now stopped at the bound.
     */
    public SvnText catFileAs(String repoUrl, String repoPath, String filePath, String username,
                             String password, int maxBytes) throws IOException {
        String fullUrl = fileUrl(repoUrl, repoPath, filePath);
        SvnCommand sc = assemble(username, password, "cat", "--no-auth-cache", fullUrl);
        return run(sc, in -> {
            byte[] bytes = in.readNBytes(maxBytes);
            boolean more = in.read() >= 0;
            return new Output<>(new SvnText(new String(bytes, StandardCharsets.UTF_8), more), !more);
        });
    }

    // =========================================================================
    // SVN CLI - file content
    // =========================================================================

    public byte[] catFileBinary(String repoUrl, String repoPath, String filePath, String username,
                                String password) throws IOException {
        String fullUrl = fileUrl(repoUrl, repoPath, filePath);
        SvnCommand sc = assemble(username, password, "cat", "--no-auth-cache", fullUrl);
        return run(sc, in -> {
            byte[] bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, MAX_FETCH_BYTES + 1));
            if (bytes.length > MAX_FETCH_BYTES) {
                throw new BoundExceeded("The file is larger than " + (MAX_FETCH_BYTES / (1024 * 1024))
                        + " MB and is not indexed: " + filePath);
            }
            return new Output<>(bytes, true);
        });
    }

    // =========================================================================
    // File type helpers
    // =========================================================================

    public boolean isIndexableFile(String filePath) {
        String lower = filePath.toLowerCase();
        for (String ext : INDEXED_EXTENSIONS) {
            if (lower.endsWith("." + ext)) return true;
        }
        return false;
    }

    // =========================================================================
    // MIME type resolution
    // =========================================================================

    // The file sources' one table (FileTypeSupport.resolveMimeType), not a copy of it: a copy here
    // had no macro-enabled Office types, no msg, epub, html or csv, and drifted from the one the
    // folders, shares and uploads use.
    public String deriveContentType(String filePath) {
        return FileTypeSupport.resolveMimeType(filePath);
    }

    // =========================================================================
    // Extension filter parsing
    // =========================================================================

    /**
     * The `svn.indexed-extensions` setting (spec 04 section 5.2): comma-separated extensions that
     * REPLACE the built-in list, null when blank so the built-in one stands.
     *
     * <p>The built-in list was the only one there was, and it is a guess about what an organization
     * keeps in Subversion. Domain-specific text formats - configuration, interface descriptions,
     * calibration files - index perfectly well as text/plain and were simply skipped, with the
     * source reporting a clean sync that had quietly ignored most of the repository. A leading dot
     * is accepted because that is how people write extensions.
     */
    static Set<String> parseIndexedExtensions(String setting) {
        if (setting == null || setting.isBlank()) return null;
        Set<String> result = new HashSet<>();
        for (String ext : setting.split(",")) {
            String trimmed = ext.strip().toLowerCase();
            while (trimmed.startsWith(".")) trimmed = trimmed.substring(1);
            if (!trimmed.isBlank()) result.add(trimmed);
        }
        return result.isEmpty() ? null : result;
    }

    // =========================================================================
    // SVN CLI - command builder and runner
    // =========================================================================

    /*
     * Every command is given the credential it runs with: the tool path the CALLING USER's own, a
     * session its source owner's. It keeps a chat turn's parallel tool calls and two syncs off shared
     * mutable state, and it stops a tool from running under whichever account a registered source
     * authenticates with: ToolConnectorResolver's rule is that a user may only ever act as themselves.
     * The tools check the URL against the user's root themselves (SvnUrls), and a session against its
     * connection's (SvnSession.requireOnRoot), before they get here.
     */
    private SvnCommand assemble(String username, String password, String... args) {
        List<String> cmd = new ArrayList<>(launcher);
        for (String arg : args) {
            cmd.add(arg);
        }
        // Fail fast on any unexpected auth/cert prompt instead of hitting the closed-stdin EOF
        // path below when no username/password is configured (the anonymous-access case).
        cmd.add("--non-interactive");

        String stdinPassword = null;
        if (username != null && !username.isBlank()
                && password != null && !password.isBlank()) {
            cmd.add("--username");
            cmd.add(username);
            cmd.add("--password-from-stdin");
            stdinPassword = password;
        }

        // Lets the client answer a Negotiate (SPNEGO -> Kerberos or NTLM) challenge as well as a
        // Basic one. VisualSVN Server offers three modes and the client side of two of them is
        // identical - Subversion Authentication sends a VisualSVN-internal user over Basic, Windows
        // Authentication in Basic mode sends DOMAIN\\user over Basic (doubled because javac scans
        // unicode escapes even inside comments), so only the username STRING
        // differs and neither needs a setting here. Integrated mode is the one that does: without
        // this the client never offers Negotiate and the connection fails against a server that
        // has Basic disabled. Default is basic-only, hence the explicit widening.
        // See https://www.visualsvn.com/support/topic/00040/
        //
        // ONLY WITH A CREDENTIAL IN HAND, since the spec review of 2026-09-18. Offering Negotiate
        // with no username lets the client single-sign-on over SSPI as whatever account the process
        // runs as - which on the background sync path is the SERVER's service account, not the
        // source's owner. Content read with the service account's rights would then be searchable by
        // users who never had them. A credential names the account that answers the challenge;
        // without one the client stays on Basic and an Integrated-only server refuses us, which
        // describeFailure turns into "save your SVN credential".
        if (stdinPassword != null) {
            cmd.add("--config-option");
            cmd.add("servers:global:http-auth-types=basic;negotiate");
        }

        // CERTIFICATES ARE CHECKED, since 2026-10-14. Every command used to pass
        // --trust-server-cert-failures=unknown-ca,cn-mismatch, so anyone able to intercept the
        // connection could present any certificate for any name and receive the credential sent on
        // stdin, while the connection's Test validated the same server fully. svn now trusts what the
        // operating system trusts, plus the CA an administrator added in Settings > Network - the
        // same trust every HTTP client in the product has (RestClientConfig).
        cmd.add("--config-option");
        cmd.add("servers:global:ssl-trust-default-ca=yes");
        Path authorities = customAuthorityFile();
        if (authorities != null) {
            cmd.add("--config-option");
            cmd.add("servers:global:ssl-authority-files=" + authorities);
        }

        String proxyHost = resolveProxyHost();
        String proxyPort = resolveProxyPort();
        if (proxyHost != null && proxyPort != null) {
            String targetUrl = args.length > 0 ? args[args.length - 1] : null;
            String targetHost = hostOf(targetUrl);
            if (!bypassesProxy(targetHost, System.getProperty("http.nonProxyHosts"))) {
                cmd.add("--config-option");
                cmd.add("servers:global:http-proxy-host=" + proxyHost);
                cmd.add("--config-option");
                cmd.add("servers:global:http-proxy-port=" + proxyPort);
            }
        }

        return new SvnCommand(cmd, stdinPassword);
    }

    // The administrator's CA as a file svn can read, written once per content and reused. Null when
    // none is configured, or when it cannot be written - then svn trusts what the system trusts, and
    // a server signed by that CA fails with a sentence pointing at Settings > Network.
    private Path customAuthorityFile() {
        CustomCaCertificates source = customCa;
        String pem = source == null ? null : source.pem();
        if (pem == null || pem.isBlank()) return null;
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(pem.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
            Path file = Path.of(System.getProperty("java.io.tmpdir"), "proxyma-svn-ca-" + digest + ".pem");
            if (!Files.isRegularFile(file)) {
                Path partial = Files.createTempFile(file.getParent(), "proxyma-svn-ca-", ".tmp");
                Files.writeString(partial, pem, StandardCharsets.UTF_8);
                Files.move(partial, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }
            return file;
        } catch (Exception e) {
            log.warn("[SVN] Could not write the Settings > Network CA for svn: {}", e.getMessage());
            return null;
        }
    }

    /** What a reader made of svn's output, and whether it read all of it. */
    private record Output<T>(T value, boolean complete) {}

    @FunctionalInterface
    private interface OutputReader<T> {
        Output<T> read(InputStream stdout) throws Exception;
    }

    private String runText(SvnCommand sc) throws IOException {
        return run(sc, in -> new Output<>(new String(in.readAllBytes(), StandardCharsets.UTF_8), true));
    }

    /**
     * Runs one svn command and hands its output to {@code reader} as it arrives.
     *
     * <p><b>NOTHING HERE WAITS FOR A SHARED THREAD, since 2026-10-14.</b> Every command used to submit
     * three tasks to one three-thread pool - a stdout reader, a stderr reader and, last, the writer of
     * the password svn reads before it writes anything. A sync's listing held two of the threads for
     * up to half an hour, so a tool call's password was never written and it timed out after two
     * minutes; two credentialed calls at once deadlocked each other. The password is now written on
     * the calling thread before anything is read (one short line fits any pipe), and stdout and
     * stderr are read on virtual threads of this command's own.
     *
     * <p>A reader that stops early - a bound reached - stops svn as well, rather than leaving it
     * blocked on a full pipe until the timeout.
     */
    private <T> T run(SvnCommand sc, OutputReader<T> reader) throws IOException {
        String subcommand = subcommandOf(sc);
        log.debug("Executing svn command: {}", subcommand);
        Process process;
        try {
            process = new ProcessBuilder(sc.args()).start();
        } catch (IOException e) {
            // "Cannot run program "svn"" - the CLI is missing, which no retry fixes.
            throw new IOException(NOT_INSTALLED, e);
        }
        try {
            try (OutputStream stdin = process.getOutputStream()) {
                if (sc.stdinPassword() != null) {
                    stdin.write((sc.stdinPassword() + "\n").getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // svn exited before reading it; its exit code and stderr say why.
            }
            CompletableFuture<String> stderr = onVirtualThread(() -> boundedText(process.getErrorStream()));
            CompletableFuture<Output<T>> stdout = onVirtualThread(() -> {
                try (InputStream in = process.getInputStream()) {
                    return reader.read(in);
                }
            });
            stdout.whenComplete((output, failure) -> {
                if (failure != null || !output.complete()) process.destroyForcibly();
            });

            int timeoutSeconds = timeoutFor(subcommand);
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new IOException("svn command timed out after " + timeoutSeconds + "s: " + subcommand);
            }
            Output<T> output;
            try {
                output = stdout.get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                // A reader's own refusal (a bound) is the answer; a parse failure of an svn that
                // failed is not - its stderr is.
                Throwable cause = e.getCause();
                if (process.exitValue() != 0 && !(cause instanceof BoundExceeded)) {
                    throw svnFailed(process.exitValue(), stderr);
                }
                if (cause instanceof IOException io) throw io;
                throw new IOException("Could not read the svn output: " + cause.getMessage(), cause);
            }
            if (!output.complete()) return output.value();
            if (process.exitValue() != 0) throw svnFailed(process.exitValue(), stderr);
            return output.value();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("svn command interrupted: " + subcommand, e);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException("svn command timed out reading its output: " + subcommand, e);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static IOException svnFailed(int exitCode, CompletableFuture<String> stderr) {
        String text;
        try {
            text = stderr.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            text = "";
        }
        return new IOException(String.format("svn exited with code %d. Stderr: %s", exitCode, text.trim()));
    }

    /** A reader's refusal to read past a bound - the caller's answer, not svn's failure. */
    private static final class BoundExceeded extends IOException {
        BoundExceeded(String message) { super(message); }
    }

    private static <T> CompletableFuture<T> onVirtualThread(Callable<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Thread.ofVirtual().name("svn-io").start(() -> {
            try {
                future.complete(work.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    // Reads stderr to its end - svn must never block on it - keeping the first part.
    private static String boundedText(InputStream in) throws IOException {
        try (in) {
            ByteArrayOutputStream kept = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                int room = MAX_STDERR_BYTES - kept.size();
                if (room > 0) kept.write(buffer, 0, Math.min(n, room));
            }
            return kept.toString(StandardCharsets.UTF_8);
        }
    }

    // The subcommand follows the launcher; only `list` walks the repository, and only it gets the
    // long wait.
    private String subcommandOf(SvnCommand sc) {
        int at = launcher.size();
        return sc.args().size() > at ? sc.args().get(at) : "";
    }

    private static int timeoutFor(String subcommand) {
        return "list".equals(subcommand) ? LIST_TIMEOUT_SECONDS : PROCESS_TIMEOUT_SECONDS;
    }

    // =========================================================================
    // XML parsing - streamed, with no DTD
    // =========================================================================

    /** One {@code <entry>} of `svn list --xml`, as it was read. */
    private record ListedEntry(String kind, String name, Long size, String revision, String author,
                               LocalDateTime lastModified) {
        SvnTreeEntry asTreeEntry() {
            return new SvnTreeEntry(name, "dir".equals(kind) ? "DIRECTORY" : "FILE", size, revision, lastModified);
        }
    }

    // A factory per listing: a StAX factory is not promised to be safe to share between threads.
    private static XMLInputFactory newSafeXmlInput() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory;
    }

    /**
     * Reads `svn list --xml` entry by entry, handing each to {@code sink} until it answers false.
     *
     * <p>A PARSE FAILURE IS A FAILED LISTING, NOT AN EMPTY REPOSITORY, since the spec review of
     * 2026-09-18: a DOCTYPE (the output is server-influenced, and doctype declarations are refused
     * for exactly that reason) or output truncated by a killed process throws, so the reason reaches
     * the source card and the purge is skipped rather than every document removed.
     *
     * @return false when the sink stopped the read, true when the listing was read to its end
     */
    private boolean parseListing(InputStream in, Predicate<ListedEntry> sink) throws IOException {
        XMLStreamReader xml = null;
        try {
            xml = newSafeXmlInput().createXMLStreamReader(in, "UTF-8");
            String kind = null, name = null, revision = null, author = null;
            Long size = null;
            LocalDateTime date = null;
            boolean inEntry = false;
            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamConstants.DTD) {
                    throw new IOException("Could not read the svn listing: it carries a DOCTYPE");
                }
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (xml.getLocalName()) {
                        case "entry" -> {
                            inEntry = true;
                            kind = xml.getAttributeValue(null, "kind");
                            name = null; revision = null; author = null; size = null; date = null;
                        }
                        case "name" -> { if (inEntry) name = xml.getElementText(); }
                        case "size" -> { if (inEntry) size = parseSize(xml.getElementText()); }
                        case "commit" -> { if (inEntry) revision = xml.getAttributeValue(null, "revision"); }
                        case "author" -> { if (inEntry) author = xml.getElementText(); }
                        case "date" -> { if (inEntry) date = parseDate(xml.getElementText()); }
                        default -> { }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && "entry".equals(xml.getLocalName())) {
                    inEntry = false;
                    if (name == null || name.isBlank()) continue;
                    if (!sink.test(new ListedEntry(kind, name, size, revision, author, date))) return false;
                }
            }
            return true;
        } catch (XMLStreamException e) {
            throw new IOException("Could not read the svn listing: " + e.getMessage(), e);
        } finally {
            if (xml != null) {
                try { xml.close(); } catch (XMLStreamException ignored) { }
            }
        }
    }

    // What the INDEXER keeps of a listing: files only, with an extension it indexes.
    private List<SvnFileEntry> indexEntries(InputStream in, Set<String> allowedExtensions) throws IOException {
        List<SvnFileEntry> entries = new ArrayList<>();
        parseListing(in, entry -> {
            if (!"file".equals(entry.kind())) return true;
            String name = entry.name();
            if (allowedExtensions != null) {
                String lower = name.toLowerCase();
                int dot = lower.lastIndexOf('.');
                if (dot < 0 || !allowedExtensions.contains(lower.substring(dot + 1))) return true;
            } else if (!isIndexableFile(name)) {
                return true;
            }
            entries.add(new SvnFileEntry(name, entry.revision(), entry.lastModified(), entry.author()));
            return true;
        });
        return entries;
    }

    /**
     * Parses `svn list --xml` for BROWSING, keeping everything the server returned - directories and
     * files of any extension, where the indexer's {@link #parseListXml} drops both: a missing
     * subdirectory means the caller cannot descend, a missing binary that they are told a file does
     * not exist.
     */
    List<SvnTreeEntry> parseTreeXml(String xml) throws IOException {
        List<SvnTreeEntry> entries = new ArrayList<>();
        parseListing(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), entry -> {
            entries.add(entry.asTreeEntry());
            return true;
        });
        return entries;
    }

    /** Parses `svn list --xml` for the INDEXER, dropping directories and unindexable extensions. */
    List<SvnFileEntry> parseListXml(String xml, Set<String> allowedExtensions) throws IOException {
        return indexEntries(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), allowedExtensions);
    }

    private static Long parseSize(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException ignored) {
            // A directory carries no <size>; anything else malformed is not worth failing a listing over.
            return null;
        }
    }

    private static LocalDateTime parseDate(String dateStr) {
        if (dateStr == null) return null;
        try {
            return ZonedDateTime.parse(dateStr.trim(), DateTimeFormatter.ISO_DATE_TIME).toLocalDateTime();
        } catch (Exception e) {
            return null;
        }
    }

    // =========================================================================
    // Path utilities
    // =========================================================================

    private String buildUrl(String repoUrl, String repoPath) {
        String base = repoUrl.replaceAll("/+$", "");
        String path = encodePath(repoPath);
        return path.isEmpty() ? base : base + "/" + path;
    }

    /** The URL of one file: the repository, the path under it, and the file name, each encoded. */
    public static String fileUrl(String repoUrl, String repoPath, String filePath) {
        String base = repoUrl.replaceAll("/+$", "");
        String path = encodePath(repoPath);
        String file = encodePath(filePath);
        StringBuilder url = new StringBuilder(base);
        if (!path.isEmpty()) url.append('/').append(path);
        if (!file.isEmpty()) url.append('/').append(file);
        return url.toString();
    }

    /**
     * Percent-encodes a path SEGMENT BY SEGMENT, keeping the separators as separators.
     *
     * <p>`svn list --xml` returns names exactly as they are in the repository, and concatenating one
     * raw gives an invalid URL for every ordinary Windows document name: a space is illegal in a
     * URL, a `#` truncates it at the fragment so the server is asked for a shorter path, and a `%`
     * is read as the start of an escape. `design spec #2.docx` failed to fetch as all three.
     *
     * <p>Encoded here rather than by {@link URI}'s multi-argument constructor because that one
     * leaves `#` inside a path alone - it only escapes what is illegal in the component, and a `#`
     * in a path is legal there and fatal once the components are joined into one string.
     */
    public static String encodePath(String path) {
        if (path == null) return "";
        String trimmed = path.replaceAll("^/+", "").replaceAll("/+$", "");
        if (trimmed.isEmpty()) return "";
        StringBuilder encoded = new StringBuilder(trimmed.length() + 8);
        for (String segment : trimmed.split("/")) {
            if (segment.isEmpty()) continue;
            if (encoded.length() > 0) encoded.append('/');
            encoded.append(encodeSegment(segment));
        }
        return encoded.toString();
    }

    // RFC 3986 unreserved, plus the sub-delims a repository name genuinely uses and that no server
    // or proxy re-reads inside a path. Everything else - space, '#', '%', '?', '+', ':' and every
    // non-ASCII byte - becomes %XX over its UTF-8 bytes.
    private static final String SEGMENT_SAFE = "-._~!$&'()*,;=@";

    public static String encodeSegment(String segment) {
        StringBuilder out = new StringBuilder(segment.length() + 8);
        for (byte b : segment.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || SEGMENT_SAFE.indexOf(c) >= 0) {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return out.toString();
    }

    /**
     * The host of a target URL, or null when there is not one to be had.
     *
     * <p>{@link URI#create} throws an UNCHECKED IllegalArgumentException on anything it considers
     * malformed - a space in a configured repository URL is enough - and it was called on the proxy
     * path, so a badly configured URL failed the whole command with a parse error instead of the
     * listing failure that would have named the URL. The host is only wanted to test the no-proxy
     * list, so a URL too broken to parse simply gets the proxy, exactly as an unknown host does.
     */
    private static String hostOf(String url) {
        if (url == null) return null;
        try {
            String host = URI.create(url).getHost();
            if (host != null) return host;
        } catch (IllegalArgumentException ignored) {
            // Fall through to the textual read below.
        }
        int scheme = url.indexOf("://");
        if (scheme < 0) return null;
        String rest = url.substring(scheme + 3);
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == ':' || c == '?' || c == '#') { end = i; break; }
        }
        int at = rest.lastIndexOf('@', end);
        String host = rest.substring(at + 1, end);
        return host.isEmpty() ? null : host;
    }
}
