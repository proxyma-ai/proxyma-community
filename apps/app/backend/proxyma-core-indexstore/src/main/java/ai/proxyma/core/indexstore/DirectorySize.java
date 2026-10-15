package ai.proxyma.core.indexstore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The bytes under a directory, best effort - the storage figures both indexes report.
 *
 * <p>One copy, because the copies drifted: the Lucene ones learned that a lazy {@code Files.walk}
 * throws {@link UncheckedIOException} when a file disappears mid-walk (a commit cleaning up a
 * superseded segment, an expected race on a directory being written to), and the ChromaDB one did
 * not, so a chroma compaction during the walk threw out of the storage total.
 */
final class DirectorySize {

    private DirectorySize() {
    }

    /** 0 when the directory is missing or cannot be walked; a file that cannot be sized counts 0. */
    static long of(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return 0;
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try { return Files.size(p); } catch (IOException e) { return 0; }
                    })
                    .sum();
        } catch (IOException | UncheckedIOException e) {
            return 0;
        }
    }
}
