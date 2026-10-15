package ai.proxyma.desktop.system;

import ai.proxyma.core.system.parts.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Supplier;

/**
 * Shows the Desktop interface, best first:
 *
 * <ol>
 *   <li><b>Proxyma's own application window</b> - an Electron shell (apps/app/desktop-shell) that
 *       opens one native window onto the local interface, the way Claude Desktop does. On Windows
 *       it is a downloaded part, named by {@link #WINDOW_DIR_PROPERTY}; on Linux it ships in the
 *       .deb as /opt/proxyma/window, beside the working directory.</li>
 *   <li>A Chromium-family browser started with {@code --app=<url>}: a window with no tabs or address
 *       bar - where the shell is absent, such as the exec jar run by hand.</li>
 *   <li>The default browser, as Proxyma always did.</li>
 * </ol>
 *
 * <p>The shell keeps a single instance itself, so calling this while the window is open brings
 * that window forward rather than opening a second one.
 *
 * <p><b>Every way in carries the access code</b> (AccessGate), so a desktop user never meets the
 * access screen: Proxyma's own window reads the code from {@link #ACCESS_ENV} - an environment
 * variable, which only the same account can read, never its command line, which on Linux every
 * account can - and a browser is given a URL with a one-time code in it, minted only if it is
 * needed.
 *
 * <p><b>Why a browser at step 2 and not the bundled Chromium.</b> The installer does ship one, but
 * it is Playwright's headless shell, for rendering web pages during a crawl: it has no window. The
 * user's own browser profile is used on purpose - a dedicated one can open first-run pages beside
 * the app, and a Snap-confined Chromium cannot keep a profile under a hidden directory at all.
 */
public final class AppWindow {

    private static final Logger log = LoggerFactory.getLogger(AppWindow.class);

    /** The window's directory, set by the launcher from the active release's parts. */
    public static final String WINDOW_DIR_PROPERTY = "proxyma.window.dir";

    /** Where Proxyma's own window reads the access code from. A contract with desktop-shell/main.js. */
    public static final String ACCESS_ENV = "PROXYMA_WINDOW_ACCESS";

    private AppWindow() {}

    /**
     * Opens Proxyma in the best window available, naming the process whose exit closes it. Never
     * throws: losing the window costs a convenience, never the application, which is still
     * reachable at its address.
     *
     * <p>The owner is ProxymaLauncher, not the backend: the launcher lives across a restart and
     * exits on Quit, while the backend is gone for the whole of every restart. A window that watched
     * the backend closed on each one and took the Restarting dialog with it.
     *
     * @param url        the app's address, with no code in it - what the window shows
     * @param accessCode handed to Proxyma's own window through {@link #ACCESS_ENV}; may be null
     * @param browserUrl the address for a browser, with a one-time code; asked for only if no
     *                   window of Proxyma's own can be started
     */
    public static void open(String url, String accessCode, Supplier<String> browserUrl, OptionalLong ownerPid) {
        Optional<List<String>> shell = shellCommand(url, ownerPid);
        if (shell.isPresent() && start(shell.get(), accessCode)) return;
        String target = browserUrl.get();
        Optional<List<String>> browser = browserAppCommand(target);
        if (browser.isPresent() && start(browser.get(), null)) return;
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(target));
                return;
            }
        } catch (Throwable e) {
            log.debug("Could not open the default browser: {}", e.getMessage());
        }
        log.warn("Could not open a window - Proxyma is running at {}", url);
    }

    private static boolean start(List<String> command, String accessCode) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command)
                    // A browser or Chromium writes its own noise to stderr, and an unread pipe
                    // that fills would block it.
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            // Set by any Electron host (a terminal inside VS Code, for one) and inherited from
            // it: it makes the shell start as plain Node, which opens no window at all.
            pb.environment().remove("ELECTRON_RUN_AS_NODE");
            if (accessCode != null) pb.environment().put(ACCESS_ENV, accessCode);
            else pb.environment().remove(ACCESS_ENV);
            pb.start();
            return true;
        } catch (Throwable e) {
            log.debug("Could not start {} ({}) - trying the next way", command.get(0), e.getMessage());
            return false;
        }
    }

    /** Opens {@code url} as a tab in the default browser - the tray's Open in Browser. */
    public static void openInBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(url));
        } catch (Throwable e) {
            log.warn("Could not open a browser ({}) - Proxyma is running at {}", e.getMessage(), url);
        }
    }

    static Optional<List<String>> shellCommand(String url, OptionalLong ownerPid) {
        String part = System.getProperty(WINDOW_DIR_PROPERTY);
        Path dir = part != null && !part.isBlank() ? Path.of(part) : Path.of(System.getProperty("user.dir"), "window");
        Path exe = dir.resolve(Platform.windows() ? "ProxymaWindow.exe" : "proxyma-window");
        if (!Files.isRegularFile(exe)) return Optional.empty();
        List<String> cmd = new ArrayList<>(List.of(exe.toString(), "--url=" + url));
        ownerPid.ifPresent(pid -> cmd.add("--owner-pid=" + pid));
        return Optional.of(cmd);
    }

    static Optional<List<String>> browserAppCommand(String url) {
        return findBrowser().map(browser -> {
            List<String> cmd = new ArrayList<>();
            cmd.add(browser.toString());
            cmd.add("--app=" + url);
            return cmd;
        });
    }

    static Optional<Path> findBrowser() {
        return Platform.windows() ? findOnWindows() : findOnPath(
                "microsoft-edge", "microsoft-edge-stable", "google-chrome", "google-chrome-stable",
                "chromium", "chromium-browser", "brave-browser");
    }

    // Edge first: it ships with Windows, so it is the one most likely to be there and current.
    // Chrome installs per machine under Program Files or per user under LocalAppData.
    private static Optional<Path> findOnWindows() {
        String[] roots = {
                System.getenv("ProgramFiles(x86)"), System.getenv("ProgramFiles"), System.getenv("LOCALAPPDATA")};
        String[] apps = {"Microsoft\\Edge\\Application\\msedge.exe", "Google\\Chrome\\Application\\chrome.exe"};
        for (String app : apps) {
            for (String root : roots) {
                if (root == null || root.isBlank()) continue;
                Path candidate = Path.of(root, app);
                if (Files.isRegularFile(candidate)) return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> findOnPath(String... names) {
        String path = System.getenv("PATH");
        if (path == null) return Optional.empty();
        for (String name : names) {
            for (String dir : path.split(File.pathSeparator)) {
                if (dir.isBlank()) continue;
                Path candidate = Path.of(dir, name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
