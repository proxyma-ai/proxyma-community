package ai.proxyma.core.tools;

import ai.proxyma.core.chat.service.SmartChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One call to read or grep returns a page an agent loop hands back whole.
 *
 * <p>These are the tools an offloaded tool result is read back with. A page of one that is itself
 * too long to hand back was offloaded in turn, and the agent was sent to read THAT - a loop that
 * ran to the step limit (2026-10-01, a 200-match grep over a wide table). So each stops inside the
 * limit and says where the rest begins, and following that instruction reaches every line.
 */
class PagingToolsStayInlineTest {

    private static final int LINES = 400;
    // Wide rows, like the converted spreadsheet the report came from: 400 lines of 1500 characters.
    private static final String CELLS = "| x | x | x | x |" + " cell".repeat(296);

    @TempDir
    Path dir;

    private Path file;

    @BeforeEach
    void aWideTable() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= LINES; i++) sb.append("row").append(i).append(' ').append(CELLS).append('\n');
        file = Files.writeString(dir.resolve("table.txt"), sb);
    }

    private static final Pattern CONTINUE_AT = Pattern.compile("read with offset=(\\d+)");

    @Test
    @DisplayName("read stops inside the limit and says which line continues it")
    void readStopsInsideTheLimit() {
        ReadTextFileTool read = new ReadTextFileTool(dir.toString(), false);

        String page = read.read(file.toString(), 1, 4000, null);

        assertTrue(page.length() <= SmartChatMemory.MAX_INLINE_RESULT_CHARS, "page is " + page.length() + " chars");
        assertTrue(CONTINUE_AT.matcher(page).find(), page.substring(page.length() - 200));
    }

    @Test
    @DisplayName("following read's own instruction reaches every line exactly once")
    void readPagesToTheEnd() {
        ReadTextFileTool read = new ReadTextFileTool(dir.toString(), false);

        List<Integer> seen = new ArrayList<>();
        int offset = 1;
        for (int calls = 0; calls < LINES; calls++) {
            String page = read.read(file.toString(), offset, 4000, null);
            assertTrue(page.length() <= SmartChatMemory.MAX_INLINE_RESULT_CHARS);
            Matcher numbered = Pattern.compile("(?m)^\\s*(\\d+)\\trow\\d+ ").matcher(page);
            while (numbered.find()) seen.add(Integer.parseInt(numbered.group(1)));
            Matcher next = CONTINUE_AT.matcher(page);
            if (!next.find()) break;
            offset = Integer.parseInt(next.group(1));
        }

        assertEquals(LINES, seen.size());
        for (int i = 0; i < LINES; i++) assertEquals(i + 1, seen.get(i));
    }

    @Test
    @DisplayName("grep stops inside the limit and says where more matches begin")
    void grepStopsInsideTheLimit() {
        SearchTextFileTool grep = new SearchTextFileTool(dir.toString(), false);

        String hits = grep.grep(file.toString(), "| x | x | x | x |", null, null, 200);

        assertTrue(hits.length() <= SmartChatMemory.MAX_INLINE_RESULT_CHARS, "result is " + hits.length() + " chars");
        assertTrue(hits.startsWith("Found "), hits.substring(0, 60));
        Matcher next = CONTINUE_AT.matcher(hits);
        assertTrue(next.find(), hits.substring(hits.length() - 200));
        // The first line it did not show, so nothing between is skipped.
        Matcher shown = Pattern.compile("(?m)^(\\d+): row").matcher(hits);
        int last = 0;
        while (shown.find()) last = Integer.parseInt(shown.group(1));
        assertEquals(last + 1, Integer.parseInt(next.group(1)));
    }

    @Test
    @DisplayName("a short result is returned as it always was, with no note")
    void shortResultsAreUntouched() {
        SearchTextFileTool grep = new SearchTextFileTool(dir.toString(), false);
        ReadTextFileTool read = new ReadTextFileTool(dir.toString(), false);

        assertFalse(grep.grep(file.toString(), "row7 ", null, null, null).contains("stopped here"));
        assertFalse(read.read(file.toString(), 10, 5, null).contains("stopped at"));
    }
}
