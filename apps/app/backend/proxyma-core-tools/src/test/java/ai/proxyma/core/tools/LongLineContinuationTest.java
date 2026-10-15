package ai.proxyma.core.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A line longer than the 2000-character cap can be read to its end (2026-09-29). One-line JSON is
 * the usual case: it was cut at 2000 characters with no way to see the rest, and grep reported a
 * match past that point and then did not show it.
 */
class LongLineContinuationTest {

    @TempDir
    Path dir;

    private Path fileWithLongLine() throws Exception {
        // 5000 characters: "a" x 2500, then a marker, then "b" to the end.
        String line = "a".repeat(2500) + "NEEDLE" + "b".repeat(5000 - 2506);
        Path file = dir.resolve("result.txt");
        Files.writeString(file, "first\n" + line + "\nlast\n");
        return file;
    }

    @Test
    @DisplayName("read says how to continue a cut line, and charOffset continues it")
    void readContinuesALongLine() throws Exception {
        Path file = fileWithLongLine();
        ReadTextFileTool read = new ReadTextFileTool(dir.toString(), false);

        String page = read.read(file.toString(), null, null, null);
        assertTrue(page.contains("read with offset=2 charOffset=2000 to continue"), page);
        assertFalse(page.contains("NEEDLE"), "the marker sits past the cut");

        String next = read.read(file.toString(), 2, null, 2000);
        assertTrue(next.contains("NEEDLE"), next);
        assertTrue(next.contains("chars 2000-4000 of 5000"), next);
        assertTrue(next.contains("charOffset=4000"), next);

        String tail = read.read(file.toString(), 2, null, 4000);
        assertFalse(tail.contains("to continue"), "the last slice reaches the end of the line: " + tail);
    }

    @Test
    @DisplayName("grep shows the window around a match past character 2000")
    void grepShowsTheMatch() throws Exception {
        Path file = fileWithLongLine();
        SearchTextFileTool grep = new SearchTextFileTool(dir.toString(), false);

        String hits = grep.grep(file.toString(), "NEEDLE", null, null, null);
        assertTrue(hits.contains("NEEDLE"), hits);
        assertTrue(hits.contains("read with offset=2 charOffset="), hits);
    }
}
