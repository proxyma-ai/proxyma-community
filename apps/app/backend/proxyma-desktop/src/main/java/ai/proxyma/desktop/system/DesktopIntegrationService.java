package ai.proxyma.desktop.system;

import ai.proxyma.core.network.AccessGate;
import ai.proxyma.core.system.parts.Platform;
import ai.proxyma.core.system.util.ProcessExit;
import ai.proxyma.desktop.common.ProxymaMark;
import com.sun.jna.Native;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.OptionalLong;

// Opens the UI in Proxyma's own window (see AppWindow) and installs a system tray icon when the
// application is ready.
@Service
public class DesktopIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(DesktopIntegrationService.class);

    @Value("${server.port:4246}")
    private int serverPort;

    private final AccessGate accessGate;
    private final ConfigurableApplicationContext context;

    public DesktopIntegrationService(AccessGate accessGate, ConfigurableApplicationContext context) {
        this.accessGate = accessGate;
        this.context = context;
    }

    /**
     * Both default to TRUE, so a real installation is unchanged and neither flag appears in any
     * properties file - they exist to be turned off from a command line.
     *
     * <p>Added for the functional suite, which boots this jar repeatedly. Without them every test
     * run opened a browser tab and installed a tray icon per booted instance, on the machine of
     * whoever ran it. That is not a cosmetic annoyance: a suite that seizes the foreground cannot
     * be run while working, so it would only ever be run deliberately, which is how a suite stops
     * being run at all.
     *
     * <p>Deliberately NOT keyed on some "test mode". There is no test profile in this product and
     * there should not be one - the whole value of the suite is that it drives the same code path
     * a customer gets. These name what they disable, and an operator running Proxyma as a
     * background service on a machine with a desktop session has an equally good reason to use
     * them.
     */
    @Value("${proxyma.desktop.open-browser:true}")
    private boolean openBrowserOnStart;

    @Value("${proxyma.desktop.system-tray:true}")
    private boolean systemTrayEnabled;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        nameThisProcessForWindows();
        if (openBrowserOnStart) openWindow();
        if (systemTrayEnabled) setupSystemTray();
    }

    /**
     * The literal text Windows prints as the heading of our notifications.
     *
     * <p>It is an AppUserModelID, and it looks like it should be reverse-DNS - Microsoft's own
     * guidance suggests {@code Company.Product}. It deliberately is not. Windows resolves an AUMID
     * against the Start Menu shortcut database to find a friendly name and icon, and no shortcut
     * carries ours, so it falls back to printing the ID verbatim. Measured on Windows 11:
     * {@code ai.proxyma.Proxyma} appears in the toast exactly like that. Since the raw string is
     * what the user reads, the raw string is "Proxyma".
     *
     * <p>The alternative is stamping the same ID onto the shortcut the installer creates in
     * CurStepChanged, which buys a small icon at the price of two places that must never disagree.
     * Not worth it. If that is ever done, this constant becomes a real identifier and the shortcut
     * must match it character for character.
     */
    private static final String WINDOWS_APP_ID = "Proxyma";

    /**
     * Without this, notifications from this process are headed "Java Platform".
     *
     * <p>Windows 10 and 11 render an AWT tray balloon as a toast, and a toast's identity comes from
     * the process, not from the call that raises it - so a JVM with no AppUserModelID advertises
     * the JRE. Setting one is the whole fix, and it has to happen in THIS process: the splash and
     * the launcher are a different one.
     */
    private void nameThisProcessForWindows() {
        if (!Platform.windows()) return;
        try {
            Shell32.INSTANCE.SetCurrentProcessExplicitAppUserModelID(new WString(WINDOWS_APP_ID));
        } catch (Throwable e) {
            // JNA failing to unpack its native stub throws UnsatisfiedLinkError, an Error - and the
            // cost of losing this is a notification headed "Java Platform", which is not worth a
            // startup failure. Same rule as the browser and the tray below.
            log.debug("Could not set the Windows application id: {}", e.getMessage());
        }
    }

    /** Declared here rather than taken from jna-platform's own Shell32, which does not bind this
     *  call. One function, no struct marshalling - a local interface is the smaller dependency. */
    private interface Shell32 extends StdCallLibrary {
        Shell32 INSTANCE = Native.load("shell32", Shell32.class);
        int SetCurrentProcessExplicitAppUserModelID(WString appId);
    }

    /**
     * Both probes below are INSIDE their try, and both catch Throwable rather than Exception.
     *
     * /usr/bin/proxyma starts the .deb install with -Djava.awt.headless=false, which is right on a desktop but
     * makes GraphicsEnvironment.isHeadless() report false even where no display exists at all - over
     * SSH, in a container, or on WSL without WSLg. Desktop.isDesktopSupported() and
     * SystemTray.isSupported() then both reach Toolkit.getDefaultToolkit(), which throws
     * java.awt.AWTError ("Can't connect to X11 window server"). AWTError is an Error, so a
     * `catch (Exception)` does not hold it, and thrown from an ApplicationReadyEvent listener it is
     * caught by SpringApplication's own catch (Throwable) and turned into a failed startup - the
     * whole application dying because it could not open a convenience.
     *
     * Neither the browser nor the tray icon is load-bearing: the UI is a web UI on a port, reachable
     * whether or not anything opened it. Losing a display must cost the convenience, not the app.
     */
    /**
     * Opens Proxyma's window, or brings it forward. Also what POST /api/v1/desktop/window does - the
     * launcher's way in when Proxyma is started a second time (WindowController).
     */
    public void openWindow() {
        // This JVM's parent is ProxymaLauncher (proxyma.exe, or /usr/bin/proxyma on a .deb), which
        // outlives a restart and exits on Quit - the window closes when it does. Run by hand, the
        // parent is a shell, and the window then closes with that.
        OptionalLong owner = ProcessHandle.current().parent()
                .map(p -> OptionalLong.of(p.pid())).orElse(OptionalLong.empty());
        // Off the caller's thread: from the tray that is AWT's event thread, which also delivers the
        // next click, and starting a process can take seconds on a machine whose antivirus scans it.
        Thread.ofVirtual().name("open-window").start(() ->
                AppWindow.open(appUrl(), accessGate.code(), this::handshakeUrl, owner));
    }

    private void openBrowser() {
        AppWindow.openInBrowser(handshakeUrl());
    }

    // 127.0.0.1, not localhost: the app listens on the IPv4 loopback only, and a name that resolved
    // to ::1 first cost every window a failed connection before the fallback.
    private String appUrl() {
        return "http://127.0.0.1:" + serverPort;
    }

    // A browser is let in with a ONE-TIME code: the URL ends up in its history, and on Linux in a
    // command line every account on the machine can read while the browser starts.
    private String handshakeUrl() {
        return appUrl() + "/?" + AccessGate.QUERY_PARAMETER + "=" + accessGate.issueOneTimeCode();
    }

    private void setupSystemTray() {
        try {
            if (!SystemTray.isSupported()) return;
            // Rendered at the tray's own reported size rather than handed a 512px bitmap with
            // setImageAutoSize(true): AWT's auto-scale is a nearest-neighbour resample, which
            // turned the mark's arcs into a smear at 16px. ProxymaMark downsamples the shipped
            // asset bilinearly, or draws its vector geometry if the asset is unreadable.
            Dimension traySize = SystemTray.getSystemTray().getTrayIconSize();
            Image icon = ProxymaMark.icon(Math.max(16, Math.min(traySize.width, traySize.height)));
            TrayIcon trayIcon = new TrayIcon(icon, "Proxyma");

            // Drawn by TrayMenu to match the app's own menus, not AWT's native PopupMenu - see there.
            // The same three actions it always had; Exit is "Quit Proxyma", as in the app's menu.
            List<List<TrayMenu.Item>> groups = List.of(
                    List.of(new TrayMenu.Item("Open Proxyma", TrayMenu.Glyph.WINDOW, false, this::openWindow),
                            // A tab in the user's own browser, for anyone who prefers one to the app window.
                            new TrayMenu.Item("Open in Browser", TrayMenu.Glyph.GLOBE, false, this::openBrowser)),
                    // Through ProcessExit like the app's own Quit, so the context is closed on a
                    // thread of its own and the exit is bounded; this was a bare System.exit(0),
                    // closed by Spring's shutdown hook with no limit (0.36.0 feedback).
                    List.of(new TrayMenu.Item("Quit Proxyma", TrayMenu.Glyph.POWER, true, () -> {
                        SystemTray.getSystemTray().remove(trayIcon);
                        ProcessExit.exitShortly(context, 0, "Quit requested from the tray");
                    })));
            trayIcon.addMouseListener(new MouseAdapter() {
                // Windows reports the popup trigger on RELEASE, other toolkits on press - so both.
                @Override public void mousePressed(MouseEvent e) { maybeShow(e); }
                @Override public void mouseReleased(MouseEvent e) { maybeShow(e); }
                private void maybeShow(MouseEvent e) {
                    if (e.isPopupTrigger()) TrayMenu.show(e.getLocationOnScreen(), groups);
                }
            });

            trayIcon.addActionListener(e -> openWindow());
            SystemTray.getSystemTray().add(trayIcon);
            announceTrayIcon(trayIcon);
        } catch (Throwable e) {
            log.warn("Could not install a system tray icon: {}", e.getMessage());
        }
    }

    /**
     * How long after startup the tray balloon appears.
     *
     * <p>It has to land AFTER the splash window has gone, and this process cannot see that window:
     * the splash belongs to ProxymaLauncher, which runs the application as a CHILD process. The
     * launcher polls /api/v1/health/ready, calls Splash.setAppRunning() and the splash then closes itself
     * 1.2 seconds later - and that health check passes at about the moment this listener runs. So
     * three seconds is the 1.2 the splash needs plus room for a slow machine.
     *
     * <p>Deliberately a delay rather than a handshake between the two processes. Being early costs
     * a balloon appearing beside a splash that is about to vanish; a handshake would put a startup
     * dependency between them for a cosmetic nicety, and the launcher is the one component whose
     * failure means the application never starts at all.
     */
    private static final long TRAY_HINT_DELAY_MS = 3_000L;

    /**
     * Tells the user, once per launch, that Proxyma is still running and where to find it.
     *
     * <p>The browser opens on startup and the window closes when they are done with it - at which
     * point Proxyma is running with nothing on screen to say so. People then either start it again,
     * find the port already in use, and conclude it is broken, or assume they have shut it down
     * when they have not. The tray icon has always answered both, and it is 16 pixels in a corner
     * that nobody has been given a reason to look at.
     */
    private void announceTrayIcon(TrayIcon trayIcon) {
        // Daemon: this must never be the thread keeping the JVM alive, and a shutdown during those
        // three seconds should not wait for a notification nobody will read.
        Thread hint = new Thread(() -> {
            try {
                Thread.sleep(TRAY_HINT_DELAY_MS);
                // NONE rather than INFO, so Windows does not stamp its own blue (i) over the
                // notification. That is the whole of what MessageType decides here - the heading
                // comes from the process AppUserModelID, which nameThisProcessForWindows sets.
                //
                // There is no Proxyma mark in the toast, and that is a decision rather than a
                // limitation: Windows takes the icon from the Start Menu shortcut that owns the
                // AUMID, so having one would mean the installer stamping the same string onto the
                // shortcut it creates - two places that must never disagree, for a 16px image.
                trayIcon.displayMessage(
                        "Proxyma is running",
                        "Closing its window does not stop it. Right-click the Proxyma icon in the "
                                + "system tray and choose Open Proxyma to come back.",
                        TrayIcon.MessageType.NONE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable e) {
                // Balloon support is not universal - several Linux tray implementations accept the
                // icon and ignore or reject displayMessage. Same rule as everything else here: a
                // missing convenience is not worth a line in the log at WARN, let alone a failure.
                log.debug("Could not show the tray notification: {}", e.getMessage());
            }
        }, "proxyma-tray-hint");
        hint.setDaemon(true);
        hint.start();
    }

}
