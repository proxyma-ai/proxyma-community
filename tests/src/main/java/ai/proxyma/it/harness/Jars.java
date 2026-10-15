package ai.proxyma.it.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.jar.JarFile;

/** Reads a packaged jar without unpacking it. */
public final class Jars {

    private Jars() {}

    /**
     * How many entries a jar carries under a prefix.
     *
     * <p>Exists for one check: whether the SPA is inside a Proxyma jar. That question CANNOT be
     * answered by file size - the jar is around 342 MB of bundled runtime whether or not the
     * frontend is in it, and the whole SPA disappears into the rounding. Counting entries is the
     * only reading that distinguishes the two, which is why this counts rather than measures.
     */
    public static int countEntriesUnder(Path jar, String prefix) {
        try (JarFile file = new JarFile(jar.toFile())) {
            return (int) file.stream()
                    .filter(entry -> entry.getName().startsWith(prefix))
                    .count();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
