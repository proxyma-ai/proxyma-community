package ai.proxyma.core.system.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

// Where the bundled Python runtime lives, and what its layout looks like on each platform.
//
// THE RUNTIME LIVES IN THE DATA DIRECTORY, NOT THE INSTALL ROOT. The launcher installs Python and
// several pip packages into it on first run, so it has to be somewhere the running user can write.
// The install root is not that place on any platform once the app is packaged properly: a .deb
// installs root-owned under /opt (as the AppImage, dropped in 0.16.0, was a read-only squashfs
// mount), and even on Windows C:\Program Files is only writable because the Inno Setup installer
// runs elevated - which is an accident of that one packaging choice rather than a property we
// should keep depending on.
//
// LEGACY INSTALLS ARE NOT MIGRATED. Every Windows installation predating this class has its runtime
// in the install root, and it is a working several-hundred-megabyte tree. Moving or re-downloading
// it on upgrade would be a long, failure-prone startup for no gain, so an install-root runtime is
// found and used exactly as before. New installs get the data-directory one; old ones keep theirs
// until they are uninstalled.
//
// This also settles a duplication ChromaStoreService used to carry a local copy of: indexstore sits
// below ingest in the module graph and so could not reuse PythonRuntime's resolver. Both now depend
// on common, which is below both.
public final class PythonLayout {

    private PythonLayout() {}

    /**
     * Candidate runtime roots, in resolution order: the data directory first, then the legacy
     * install root. `installRoot` may be null in contexts that have no meaningful one.
     */
    public static List<Path> roots(Path dataRoot, Path installRoot) {
        List<Path> out = new ArrayList<>(2);
        if (dataRoot != null) out.add(dataRoot.resolve("runtime"));
        if (installRoot != null) out.add(installRoot.resolve("python"));
        return out;
    }

    /** The root the runtime should be INSTALLED into when none exists yet - always the data one. */
    public static Path preferredRoot(Path dataRoot) {
        return dataRoot.resolve("runtime");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** {@code <root>/python.exe} on Windows, {@code <root>/bin/python3} elsewhere. */
    public static Path interpreter(Path root) {
        return isWindows() ? root.resolve("python.exe") : root.resolve("bin").resolve("python3");
    }

    /** {@code Scripts/} on Windows, {@code bin/} elsewhere - where pip and console scripts land. */
    public static Path scriptsDir(Path root) {
        return root.resolve(isWindows() ? "Scripts" : "bin");
    }

    public static Path pipExecutable(Path root) {
        return scriptsDir(root).resolve(isWindows() ? "pip.exe" : "pip3");
    }

    /**
     * The site-packages directory, or null when the runtime is not laid out yet.
     *
     * Windows embeddable Python puts it at a fixed {@code Lib/site-packages}. A relocatable CPython
     * on Linux puts it under {@code lib/python3.<minor>/site-packages}, and the minor version is a
     * property of whichever build was bundled - so it is discovered rather than hardcoded, which
     * would silently break the day the bundled Python is bumped.
     */
    public static Path sitePackages(Path root) {
        if (isWindows()) {
            Path fixed = root.resolve("Lib").resolve("site-packages");
            return Files.isDirectory(fixed) ? fixed : null;
        }
        Path lib = root.resolve("lib");
        if (!Files.isDirectory(lib)) return null;
        try (Stream<Path> s = Files.list(lib)) {
            return s.filter(p -> p.getFileName().toString().startsWith("python3"))
                    .map(p -> p.resolve("site-packages"))
                    .filter(Files::isDirectory)
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** True when {@code module} is importable from this root, judged by its directory existing. */
    public static boolean hasPackage(Path root, String module) {
        Path site = sitePackages(root);
        return site != null && Files.exists(site.resolve(module).resolve("__init__.py"));
    }

    /**
     * The first candidate root that has a real interpreter in it, or null when none does.
     * This is the resolution every consumer should use.
     */
    public static Path resolveRoot(Path dataRoot, Path installRoot) {
        for (Path root : roots(dataRoot, installRoot)) {
            if (Files.isRegularFile(interpreter(root))) return root;
        }
        return null;
    }

    /** Convenience: {@link #interpreter} of {@link #resolveRoot}, or null. */
    public static Path resolveInterpreter(Path dataRoot, Path installRoot) {
        Path root = resolveRoot(dataRoot, installRoot);
        return root != null ? interpreter(root) : null;
    }
}
