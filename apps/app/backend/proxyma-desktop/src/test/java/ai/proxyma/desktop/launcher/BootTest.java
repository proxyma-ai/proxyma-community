package ai.proxyma.desktop.launcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which launcher runs: a downloaded launcher part, or the one the installer carries.
 *
 * <p>Deliberately Boot only. ProxymaLauncher's static initializer creates the log directory of the
 * REAL installation (AppPaths reads only the environment), so no test here touches that class.
 *
 * <p>The "launcher part" here is this module's own test-classes directory, loaded in a class loader
 * of its own exactly as Boot loads a downloaded proxyma-launcher.jar.
 */
class BootTest {

    private static final String RAN = "proxyma.test.boot.ran";

    @TempDir Path parts;

    // runPart leaves the thread on the part's class loader, as a real launch must. Left there, every
    // later test on this thread saw JDBC through a loader with no H2: DriverManager finds its drivers
    // once, through the context loader, so H2EnumColumnsTest and StaleDatabaseLocksTest failed with
    // "No suitable driver" wherever this class ran first - Linux, not Windows (2026-10-15).
    private final ClassLoader context = Thread.currentThread().getContextClassLoader();

    @AfterEach
    void clearProperty() {
        System.clearProperty(RAN);
        Thread.currentThread().setContextClassLoader(context);
    }

    private static Path partJar() throws Exception {
        return Path.of(BootTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    @Test
    @DisplayName("a part that loads runs, with the trying marker written first")
    void partRuns() throws Throwable {
        Path trying = parts.resolve("launcher-trying");
        assertTrue(Boot.runPart(partJar(), Starts.class.getName(), trying, new String[0]));
        assertEquals("yes", System.getProperty(RAN));
        assertEquals(partJar().toString(), Files.readString(trying, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a part that cannot be loaded falls back to the installer's launcher, unmarked")
    void unloadablePartFallsBack() throws Throwable {
        Path trying = parts.resolve("launcher-trying");
        assertFalse(Boot.runPart(partJar(), "ai.proxyma.desktop.launcher.NoSuchLauncher", trying, new String[0]));
        assertFalse(Files.exists(trying));
    }

    @Test
    @DisplayName("a part whose main throws surfaces its own error - never a second launcher in this JVM")
    void failingPartPropagates() throws Exception {
        Path trying = parts.resolve("launcher-trying");
        // Before the fix the InvocationTargetException was caught as "could not load" and the
        // installer's launcher ran, failing on the lock the part already held.
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Boot.runPart(partJar(), Fails.class.getName(), trying, new String[0]));
        assertEquals("the part's own failure", e.getMessage());
        // Left in place, so the next start uses the installer's launcher.
        assertTrue(Files.exists(trying));
    }

    @Test
    @DisplayName("startedWell removes the trying marker")
    void startedWellClearsMarker() throws Exception {
        Path trying = parts.resolve("launcher-trying");
        Files.writeString(trying, "x", StandardCharsets.UTF_8);
        Boot.startedWell(parts);
        assertFalse(Files.exists(trying));
    }

    /** A launcher part that starts. */
    public static final class Starts {
        public static void main(String[] args) {
            System.setProperty(RAN, "yes");
        }
    }

    /** A launcher part that loads and then fails, as one would after taking the instance lock. */
    public static final class Fails {
        public static void main(String[] args) {
            throw new IllegalStateException("the part's own failure");
        }
    }
}
