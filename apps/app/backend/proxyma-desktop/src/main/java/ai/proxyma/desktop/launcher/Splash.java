package ai.proxyma.desktop.launcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// What the launcher tells the user while it is getting ready, decoupled from the window that shows
// it. The launcher's startup sequence is long enough (extracting a Python runtime, installing
// several packages) that running it silently would look like a hang, so every step reports here.
//
// The interface exists so there can be a no-op implementation. SplashWindow extends JFrame and its
// constructor throws HeadlessException where there is no display - which used to take the whole
// launcher down with an unexplained stack trace before the app had said anything at all. A desktop
// build genuinely needs a desktop session (it opens a browser and a tray icon), but "no display,
// here is why" is a far better failure than a HeadlessException, and starting headless is useful
// enough for diagnosis to be worth allowing.
interface Splash {

    void setCheckingPrereqs();
    void setInstallingPrereqs(String what);
    void setPrereqsReady();
    void setChromaStarting();
    void setChromaRunning();
    void setAppRunning();
    void setStatus(String s);
    /**
     * A download with a known size: {@code status} as the line, the bar filled to {@code fraction}
     * (0..1) rather than sweeping. Used for the parts a first start downloads.
     */
    void setDownloading(String status, double fraction);
    void setError(String message);
    void dispose();

    /** Logs what would have been shown. Used when there is no display to show it on. */
    Splash NONE = new Splash() {
        private final Logger log = LoggerFactory.getLogger("ai.proxyma.desktop.launcher.Splash");

        @Override public void setCheckingPrereqs()              { log.info("Checking prerequisites..."); }
        @Override public void setInstallingPrereqs(String what) { log.info("Installing {}...", what); }
        @Override public void setPrereqsReady()                 { log.info("Prerequisites ready."); }
        @Override public void setChromaStarting()               { log.info("Starting ChromaDB..."); }
        @Override public void setChromaRunning()                { log.info("Starting application..."); }
        @Override public void setAppRunning()                   { log.info("Application running."); }
        @Override public void setStatus(String s)               { log.info("{}", s); }
        private long lastLogged;
        @Override public void setDownloading(String status, double fraction) {
            // A download reports every 64 KB; once every few seconds is enough for a log.
            long now = System.currentTimeMillis();
            if (now - lastLogged < 5_000 && fraction < 1.0) return;
            lastLogged = now;
            log.info("{}", status);
        }
        @Override public void setError(String message)          { log.error("{}", message); }
        @Override public void dispose()                         { }
    };
}
