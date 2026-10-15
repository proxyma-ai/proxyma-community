package ai.proxyma.core.tools.office;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Laying inputs out in a run directory and reading its outputs back, with the shared limits. */
final class SandboxFiles {

    // Relative paths only, and only under the two folders a program sees: a key from anywhere else
    // is a bug in the caller, never something to write.
    static void writeInputs(Path run, Map<String, byte[]> inputs) throws IOException {
        Files.createDirectories(run.resolve("attachments"));
        Files.createDirectories(run.resolve("outputs"));
        for (Map.Entry<String, byte[]> e : inputs.entrySet()) {
            Path target = run.resolve(e.getKey()).normalize();
            if (!target.startsWith(run.resolve("attachments")) && !target.startsWith(run.resolve("outputs"))) {
                throw new IllegalArgumentException("Input outside attachments/ or outputs/: " + e.getKey());
            }
            Files.createDirectories(target.getParent());
            Files.write(target, e.getValue());
        }
    }

    // Every regular file under outputs/, keyed by its path relative to the run directory. A file over
    // the per-file limit, or past the total, is left behind rather than failing the run.
    //
    // NEVER THROUGH A LINK. A program may replace outputs/ or a folder in it with a symbolic link or,
    // on Windows, a junction - which java.nio reports as a directory and Files.walk descends into -
    // and its "outputs" were then any file the link pointed at, read back into the conversation.
    // Links and reparse points are skipped, not followed, and the outputs folder itself must be a
    // real directory.
    static Map<String, byte[]> readOutputs(Path run) throws IOException {
        Path outputs = run.resolve("outputs");
        Map<String, byte[]> result = new LinkedHashMap<>();
        if (!isPlainDirectory(outputs)) return result;
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(outputs, EnumSet.noneOf(java.nio.file.FileVisitOption.class), 4, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return isLink(attrs) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && !isLink(attrs)) files.add(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(null);
        long total = 0;
        for (Path file : files) {
            long size = Files.size(file);
            if (size > PythonSandbox.Limits.MAX_FILE_BYTES || total + size > PythonSandbox.Limits.MAX_TOTAL_BYTES) continue;
            total += size;
            result.put(run.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
        }
        return result;
    }

    /**
     * Deletes a tree WITHOUT following a link or junction out of it: a link is removed as itself,
     * and what it points at is left alone. Files.walk descended into a junction a program had made
     * and deleted everything under its target. Best effort.
     */
    static void deleteTree(Path root) {
        try {
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (isLink(attrs)) {
                        deleteQuietly(dir);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    deleteQuietly(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    deleteQuietly(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    deleteQuietly(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    /**
     * The last {@code maxChars} or so of a stream file, decoded leniently. Reads only the tail: a
     * program printing gigabytes put all of it on the heap before it was cut, and one invalid UTF-8
     * byte failed the decode - the run reported as failed and its files never saved.
     */
    static String tail(Path file, int maxChars) throws IOException {
        if (!Files.exists(file)) return "";
        long maxBytes = (long) maxChars * 4;
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            long size = channel.size();
            long start = Math.max(0, size - maxBytes);
            channel.position(start);
            ByteBuffer buffer = ByteBuffer.allocate((int) (size - start));
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // keep reading
            }
            buffer.flip();
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .decode(buffer).toString();
            String cut = PythonSandbox.Limits.tail(text);
            return start > 0 && cut.equals(text) ? "...(truncated)\n" + text : cut;
        }
    }

    private static boolean isPlainDirectory(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return false;
        BasicFileAttributes attrs = Files.readAttributes(dir, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return attrs.isDirectory() && !isLink(attrs);
    }

    // A symbolic link, or anything else that is not plainly a file or a directory - which is how a
    // Windows junction or mount point reads when links are not followed.
    private static boolean isLink(BasicFileAttributes attrs) {
        return attrs.isSymbolicLink() || attrs.isOther();
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best effort
        }
    }

    private SandboxFiles() {}
}
