package ai.proxyma.desktop;

import ai.proxyma.core.system.util.AppPaths;
import ai.proxyma.desktop.launcher.PendingReset;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.time.Instant;

// No @EnableAsync or @EnableScheduling here: AsyncConfig carries both, for every edition. And no
// @EnableAspectJAutoProxy - nothing in the product is an @Aspect, and class-based proxies are
// Spring Boot's default (editions-assembly-30).
@SpringBootApplication(scanBasePackages = {"ai.proxyma.core", "ai.proxyma.connectors", "ai.proxyma.desktop"})
public class ProxymaDesktopApplication {

    public static void main(String[] args) {
        // logback.xml writes to ${proxyma.log.dir:-logs}/proxyma.log. The launcher normally
        // passes -Dproxyma.log.dir on the child command line, but this jar is also run directly -
        // from an IDE, from `java -jar`, or by anything that bypasses the launcher - and then the
        // fallback is `logs` RELATIVE to the working directory. Under the .deb that directory is the
        // root-owned /opt/proxyma (under the AppImage, dropped in 0.16.0, a read-only squashfs
        // mount), so logging failed outright and took the only record of why with it. Absolute
        // here, unless something upstream already decided.
        // Both of these resolve through AppPaths, which is the ONE place that knows where this
        // platform keeps application data: %LOCALAPPDATA% on Windows, $XDG_DATA_HOME or
        // ~/.local/share elsewhere.
        //
        // application-desktop.properties used to hardcode ${LOCALAPPDATA} into all ten H2 JDBC URLs
        // and the evaluation media directory. LOCALAPPDATA is a Windows environment variable and
        // does not exist on Linux, so Spring left the placeholder unresolved and H2 was handed a
        // literal path beginning "${LOCALAPPDATA}":
        //     A file path that is implicitly relative to the current working directory is not
        //     allowed in the database URL "jdbc:h2:file:${LOCALAPPDATA}/proxyma/bitbucket_repos"
        // Every datasource failed, so the Linux build (then an AppImage) could never have started -
        // it exited moments after printing its active profile.
        setIfAbsent("proxyma.data.dir", () -> AppPaths.create().dataRoot().toString());
        setIfAbsent("proxyma.log.dir", () -> AppPaths.create().logsDir().toString());
        // A reset the user asked for, before anything opens the databases. The launcher has already
        // done it on Desktop, so this finds nothing there; it is what carries it out on the Home
        // Server and a jar run by hand, where no launcher runs (PendingReset).
        PendingReset.applyIfRequested(AppPaths.create().dataRoot(), Instant.now());
        // A window and a tray icon need a display; a Home Server container has none, and with
        // headless=false every AWT call there tries an X11 connection (editions-assembly-26).
        if (!homeServer()) System.setProperty("java.awt.headless", "false");
        SpringApplication.run(ProxymaDesktopApplication.class, args);
    }

    // Read before Spring exists, so from where Spring would read it: the system property, then the
    // environment variable the Home Server image sets.
    private static boolean homeServer() {
        String value = System.getProperty("proxyma.home-server", System.getenv("PROXYMA_HOME_SERVER"));
        return Boolean.parseBoolean(value);
    }

    // A path this cannot resolve is fatal either way - the app has nowhere to put its databases -
    // so the failure is reported plainly rather than swallowed into a placeholder that reaches H2.
    private static void setIfAbsent(String key, java.util.function.Supplier<String> value) {
        if (System.getProperty(key) != null) return;
        try {
            System.setProperty(key, value.get());
        } catch (Exception e) {
            System.err.println("Could not resolve " + key + ": " + e.getMessage());
        }
    }
}
