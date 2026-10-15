package ai.proxyma.core.system.util;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

// Single source of truth for all data-directory paths used by PROXYMA.
//
// Resolution order (cross-platform):
//   Windows : %LOCALAPPDATA%/proxyma/
//   Linux   : $XDG_DATA_HOME/proxyma/  or  ~/.local/share/proxyma/
//
// Also usable in pre-Spring contexts (EnvironmentPostProcessor) via AppPaths.create().
@Component
public final class AppPaths {

    private final Path dataRoot;

    public AppPaths() {
        this.dataRoot = resolveDataRoot();
    }

    public static AppPaths create() {
        return new AppPaths();
    }

    private static Path resolveDataRoot() {
        String la = System.getenv("LOCALAPPDATA");
        if (la != null && !la.isBlank()) return Path.of(la, "proxyma");
        String xdg = System.getenv("XDG_DATA_HOME");
        if (xdg != null && !xdg.isBlank()) return Path.of(xdg, "proxyma");
        // Prefer the HOME env var over the JVM's own user.home system property. On musl/Alpine
        // JVM builds, user.home resolves via the OS passwd database (getpwuid), which for a
        // container's root user is hardcoded to /root regardless of what HOME is set to in the
        // environment - so a deployment setting HOME=/data (to redirect this data root into a
        // mounted volume) silently has no effect on Alpine, even though the identical approach
        // works correctly on glibc-based images (e.g. eclipse-temurin:*-jre-noble). Reading HOME
        // directly is also what XDG's own spec assumes anyway ($HOME/.local/share).
        String home = System.getenv("HOME");
        String base = (home != null && !home.isBlank()) ? home : System.getProperty("user.home");
        return Path.of(base, ".local", "share", "proxyma");
    }

    public Path dataRoot()          { return dataRoot; }

    /**
     * Creates the data root if it is missing, and keeps it to its owner on POSIX.
     *
     * <p>It holds the unencrypted H2 databases, the credential key and the access code, and it was
     * created with the umask default - 0755 under a stock Ubuntu home - so another account on the
     * same machine could copy chat history and sources out of it (editions-assembly-17). An existing
     * directory is narrowed too, best-effort: a filesystem with no POSIX permissions, or a root
     * somebody else owns, is left as it is rather than stopping the start.
     */
    public Path createDataRoot() throws IOException {
        Files.createDirectories(dataRoot);
        if (dataRoot.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            try {
                Files.setPosixFilePermissions(dataRoot, PosixFilePermissions.fromString("rwx------"));
            } catch (Exception ignored) {
                // Not ours to change - see above.
            }
        }
        return dataRoot;
    }

    public Path chromaDbRoot()      { return dataRoot.resolve("chromadb"); }
    public Path luceneRoot()        { return dataRoot.resolve("lucene"); }
    public Path tempDir()           { return dataRoot.resolve("temp"); }
    public Path logsDir()           { return dataRoot.resolve("logs"); }
    public Path updateStagingDir()  { return dataRoot.resolve("update-staging"); }
    // The bundled Python interpreter and its pip packages. In the DATA directory rather than the
    // install root because the launcher writes into it on first run, and the install root is not
    // writable once the app is packaged as a .deb. See PythonLayout, which owns the
    // per-platform layout and the fallback to a legacy install-root runtime.
    public Path pythonRuntimeDir() { return dataRoot.resolve("runtime"); }

    /**
     * Where the bundled headless browser lives, for Playwright's PLAYWRIGHT_BROWSERS_PATH.
     *
     * In the DATA directory for the same two reasons as the Python runtime: the launcher unpacks
     * into it on first run, and the .deb's install root, /opt/proxyma, is root-owned.
     *
     * It also has to be OURS. Left to itself Playwright installs and looks for browsers in the
     * USER's cache - %LOCALAPPDATA%\ms-playwright on Windows, ~/.cache/ms-playwright elsewhere -
     * which is shared with every other Playwright on that machine. Proxyma would then be reading a
     * browser it did not install, writing one that other tools pick up, and leaving several hundred
     * megabytes behind after an uninstall. Pointing PLAYWRIGHT_BROWSERS_PATH here keeps the browser
     * inside Proxyma's own directory, where uninstalling the app removes it with everything else.
     */
    public Path browsersDir() {
        // The launcher points this at the installed browser part (see PartsStore); anything run
        // without it - a jar started by hand, the functional suite - keeps the old location.
        String part = System.getProperty("proxyma.browsers.dir");
        return part != null && !part.isBlank() ? Path.of(part) : dataRoot.resolve("browsers");
    }

    /** The downloaded parts of a desktop install and the manifests naming them - see PartsStore. */
    public Path partsDir()          { return dataRoot.resolve("parts"); }

    /**
     * Repository snapshots downloaded from Bitbucket, one directory per project/repo/ref.
     *
     * <p>In the data directory rather than temp because these are meant to survive a restart -
     * downloading a repository is slow and a user who asked for one expects it to still be there.
     * That also makes them the largest thing here that nothing prunes automatically, which is why
     * the tool that writes them reports the size it wrote and the one beside it can delete them.
     */
    public Path repoSnapshotsDir()  { return dataRoot.resolve("repos"); }

    public Path encryptionKeyDir()  { return dataRoot; }
    public Path licenseFile()       { return dataRoot.resolve("license.dat"); }
    public Path leaseFile()         { return dataRoot.resolve("lease.jwt"); }
    public Path appLockFile()       { return dataRoot.resolve("app.lock"); }
}
