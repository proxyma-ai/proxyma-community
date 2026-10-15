package ai.proxyma.desktop.launcher;

import ai.proxyma.core.system.parts.PartsManifest;
import ai.proxyma.core.system.parts.Versions;
import ai.proxyma.core.system.util.AppPaths;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * The installer's entry point: proxyma.exe on Windows, /usr/bin/proxyma on Linux.
 *
 * <p>WHY IT EXISTS. The installer carries a launcher, and a launcher is code that changes - it
 * changed in eighteen commits in the two months before this class. Everything else is a downloaded
 * part that updates by itself; without this, the launcher would be the one piece that took a
 * reinstall to fix. So the launcher is ALSO a part, and this class decides which copy runs:
 *
 * <ul>
 *   <li>the active release's launcher part, when that release is newer than the installer -
 *       loaded in a class loader of its own, so none of the installer's copy is mixed in;</li>
 *   <li>otherwise the launcher the installer carries - on the first start, after a newer installer
 *       was run over an older install, and whenever the part cannot be used.</li>
 * </ul>
 *
 * <p>A LAUNCHER PART THAT CANNOT START THE APPLICATION IS NOT TRIED TWICE IN A ROW. This writes
 * {@code launcher-trying} before starting a part and {@link #startedWell} deletes it once the
 * application answers. Finding it still there means the last attempt with that part never got that
 * far, so this start uses the installer's own launcher - which can always start a release, because
 * that is all a launcher does. A second start while Proxyma runs deletes it too (see
 * ProxymaLauncher's single-instance guard), because that start ends without the application ever
 * answering it and would otherwise leave the marker behind for a part that works.
 *
 * <p>Deliberately small, and it logs nothing: logging is the launcher's, set up in its static
 * initializer, and initializing it here would fix the log location before the launcher gets to.
 */
public final class Boot {

    private static final String TRYING = "launcher-trying";
    private static final String LAUNCHER_JAR = "proxyma-launcher.jar";
    private static final String LAUNCHER_CLASS = "ai.proxyma.desktop.launcher.ProxymaLauncher";

    private Boot() {}

    public static void main(String[] args) throws Throwable {
        Path parts = AppPaths.create().partsDir();
        Optional<Path> jar = launcherPart(parts);
        if (jar.isPresent() && runPart(jar.get(), LAUNCHER_CLASS, parts.resolve(TRYING), args)) return;
        ProxymaLauncher.main(args);
    }

    /**
     * Runs {@code launcherClass}'s main from the launcher part in {@code jar}.
     *
     * <p>Returns false - use the installer's launcher - ONLY when the part could not be loaded,
     * linked or initialized, which is before any of its code has run. Once its main is running, a
     * failure is the part's own and is rethrown as itself: the part may already hold the
     * single-instance lock and have a splash open, so starting the installer's launcher in this
     * same JVM would fail on that lock (OverlappingFileLockException) and hide the real error behind
     * it. The {@code trying} marker stays, so the NEXT start uses the installer's launcher instead.
     */
    static boolean runPart(Path jar, String launcherClass, Path trying, String[] args) throws Throwable {
        Method main;
        try {
            // No close(): the launcher runs until the process ends, on this class loader.
            @SuppressWarnings("resource")
            URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()},
                    ClassLoader.getPlatformClassLoader());
            Thread.currentThread().setContextClassLoader(loader);
            // initialize=true, so a failing static initializer is caught here as "could not load"
            // rather than surfacing from invoke() below as if main itself had failed.
            main = Class.forName(launcherClass, true, loader).getMethod("main", String[].class);
        } catch (ReflectiveOperationException | LinkageError e) {
            // A part this build cannot even load. Fall through to the launcher it carries.
            System.err.println("Could not start the launcher in " + jar + " (" + e + ") - using the installed one");
            Thread.currentThread().setContextClassLoader(Boot.class.getClassLoader());
            return false;
        }
        Files.writeString(trying, jar.toString(), StandardCharsets.UTF_8);
        try {
            main.invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            // Thrown BY the part's main - never a reason to run a second launcher in this JVM.
            throw e.getCause();
        }
        return true;
    }

    /** Called by a launcher once the application has answered: the part it runs from is good. */
    static void startedWell(Path parts) {
        try {
            Files.deleteIfExists(parts.resolve(TRYING));
        } catch (Exception ignored) {
            // At worst the next start uses the installer's launcher once.
        }
    }

    private static Optional<Path> launcherPart(Path parts) {
        try {
            Path activeFile = parts.resolve("active.txt");
            if (!Files.isRegularFile(activeFile)) return Optional.empty();
            PartsManifest active = PartsManifest.parse(Files.readString(activeFile, StandardCharsets.UTF_8));
            if (!Versions.isNewer(active.version(), ownVersion())) return Optional.empty();
            Optional<PartsManifest.Part> part = active.part("launcher");
            if (part.isEmpty()) return Optional.empty();
            Path jar = parts.resolve("launcher").resolve(part.get().id()).resolve(LAUNCHER_JAR);
            if (!Files.isRegularFile(jar)) return Optional.empty();

            Path trying = parts.resolve(TRYING);
            if (Files.isRegularFile(trying) && Files.readString(trying, StandardCharsets.UTF_8).equals(jar.toString())) {
                // The last start with this part did not get as far as the application answering.
                Files.deleteIfExists(trying);
                System.err.println("The launcher in " + jar + " did not start Proxyma last time - using the installed one");
                return Optional.empty();
            }
            return Optional.of(jar);
        } catch (Exception e) {
            // Anything unreadable means the installer's own launcher, which is always a safe answer.
            return Optional.empty();
        }
    }

    private static String ownVersion() throws Exception {
        try (InputStream in = Boot.class.getResourceAsStream("/META-INF/build-info.properties")) {
            Properties p = new Properties();
            if (in != null) p.load(in);
            return p.getProperty("build.version", "0.0.0");
        }
    }
}
