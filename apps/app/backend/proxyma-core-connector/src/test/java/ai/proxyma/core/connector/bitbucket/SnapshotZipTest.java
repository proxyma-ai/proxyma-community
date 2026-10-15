package ai.proxyma.core.connector.bitbucket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The repository snapshot's unzip, which writes an archive the server sent onto this machine's disk.
 * An entry may name anything - "../" climbs, absolute paths - and only what stays under the
 * destination is written; the byte and entry caps hold whatever the headers claim.
 */
class SnapshotZipTest {

    @TempDir
    Path work;

    private static byte[] zip(String... namesAndContents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                out.putNextEntry(new ZipEntry(namesAndContents[i]));
                out.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Test
    @DisplayName("an entry climbing out of the destination, or naming an absolute path, is never written")
    void escapingEntriesAreSkipped() throws Exception {
        Path dest = Files.createDirectories(work.resolve("snapshot"));
        String absolute = work.resolve("absolute.txt").toAbsolutePath().toString().replace('\\', '/');
        byte[] archive = zip("src/Main.java", "class Main {}", "../escaped.txt", "x", absolute, "y");

        long[] totals = BitbucketConnector.extractZipBounded(new ByteArrayInputStream(archive), dest, 1_000_000, 100);

        assertThat(dest.resolve("src/Main.java")).exists();
        assertThat(work.resolve("escaped.txt")).doesNotExist();
        assertThat(work.resolve("absolute.txt")).doesNotExist();
        assertThat(totals[1]).isEqualTo(1);
    }

    @Test
    @DisplayName("the byte cap and the entry cap stop the unzip")
    void capsHold() throws Exception {
        byte[] archive = zip("a.txt", "x".repeat(5_000), "b.txt", "y", "c.txt", "z");
        assertThrows(IOException.class, () -> BitbucketConnector.extractZipBounded(
                new ByteArrayInputStream(archive), Files.createDirectories(work.resolve("bytes")), 1_000, 100));
        assertThrows(IOException.class, () -> BitbucketConnector.extractZipBounded(
                new ByteArrayInputStream(zip("a.txt", "x", "b.txt", "y", "c.txt", "z")),
                Files.createDirectories(work.resolve("entries")), 1_000_000, 2));
    }
}
