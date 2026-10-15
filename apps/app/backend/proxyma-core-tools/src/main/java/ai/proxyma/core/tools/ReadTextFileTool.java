package ai.proxyma.core.tools;

import ai.proxyma.core.system.util.Failures;

import ai.proxyma.core.tools.schema.ToolFieldType;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.schema.ToolResultField;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

// The 'read' tool: reads an offloaded tool-result file (or any file inside the conversations
// directory) as line-numbered text, cat -n style, the way Claude Code's Read tool does. Offloaded
// results are rendered to readable multi-line text before being written (see
// SmartChatMemory.offloadResult), so line-based paging is meaningful here. Output is returned
// verbatim via RawTextResultConverter so real newlines survive instead of being re-escaped to "\n"
// by the default JSON result converter. Paired with the 'grep' tool (SearchTextFileTool).
@Component
public class ReadTextFileTool {

    private static final int DEFAULT_LIMIT_LINES = 2000;
    private static final int MAX_LIMIT_LINES = 4000;
    // Individual lines longer than this are truncated in the output (a single pathological line -
    // e.g. one long JSON array that didn't render to multiple lines - must not blow up the result).
    private static final int MAX_LINE_CHARS = 2000;
    // The whole page, whatever `limit` asked for - see OffloadFileAccess.MAX_PAGE_CHARS.
    static final int MAX_RESULT_CHARS = OffloadFileAccess.MAX_PAGE_CHARS;

    private final String conversationsDir;
    private final boolean multiUser;

    public ReadTextFileTool(@Value("${proxyma.conversations-dir:${user.home}/.proxyma/conversations}") String conversationsDir,
                            @Value("${proxyma.multi-user:false}") boolean multiUser) {
        this.conversationsDir = conversationsDir;
        this.multiUser = multiUser;
    }

    @Tool(name = "read", resultConverter = RawTextResultConverter.class,
            description = "Read an offloaded tool-result file as line-numbered text (cat -n). Only files "
            + "under the conversations directory. Find the line with grep first, then read around it with offset. "
            + "A long page stops early and says which offset continues it. "
            + "A line longer than 2000 characters is cut; read it on with offset and charOffset.")
    @ToolResult(rawText = true,
            description = "The requested lines as plain text, line-numbered. Not JSON - see "
                    + "RawTextResultConverter. A workflow needing fields out of this has to parse the text "
                    + "itself.")
    public String read(
            @ToolParam(description = "Absolute path to the file") String filePath,
            @ToolParam(required = false, description = "1-based start line (default 1)") Integer offset,
            @ToolParam(required = false, description = "Lines to read, max 4000 (default 2000)") Integer limit,
            @ToolParam(required = false, description = "Continue the line at 'offset' from this character "
                    + "(0-based); reads that one line only") Integer charOffset) {
        try {
            Path target = Path.of(filePath).normalize();
            Path allowed = OffloadFileAccess.resolveAllowedRoot(conversationsDir, multiUser);
            if (!OffloadFileAccess.isWithinAllowedRoot(target, allowed)) {
                return OffloadFileAccess.denied("read", target, allowed);
            }
            if (!Files.exists(target)) {
                return "Error: file not found";
            }

            List<String> lines = LenientText.readLines(target);
            int totalLines = lines.size();
            if (totalLines == 0) {
                return "(file is empty)";
            }
            int start = Math.max(1, offset != null ? offset : 1);
            int count = Math.min(MAX_LIMIT_LINES, Math.max(1, limit != null ? limit : DEFAULT_LIMIT_LINES));
            if (start > totalLines) {
                return "Error: offset " + start + " is past the end of the file (" + totalLines + " lines)";
            }
            // ONE LONG LINE, CONTINUED - since 2026-09-29. A line over MAX_LINE_CHARS was cut with no
            // way to see the rest, which is exactly what a one-line JSON result or a minified file is.
            if (charOffset != null) {
                String line = lines.get(start - 1);
                int from = Math.max(0, charOffset);
                if (from >= line.length()) {
                    return "Error: line " + start + " has only " + line.length() + " characters";
                }
                int to = Math.min(line.length(), from + MAX_LINE_CHARS);
                StringBuilder one = new StringBuilder(String.format("%6d\t%s", start, line.substring(from, to)));
                if (to < line.length()) {
                    one.append(String.format(" ... [chars %d-%d of %d; read with offset=%d charOffset=%d to continue]",
                            from, to, line.length(), start, to));
                }
                return one.append(System.lineSeparator()).toString();
            }
            int end = Math.min(start + count - 1, totalLines); // inclusive, 1-based

            StringBuilder sb = new StringBuilder();
            int last = start - 1;
            for (int i = start; i <= end; i++) {
                String row = String.format("%6d\t%s%n", i, truncateLine(lines.get(i - 1), i));
                // Always at least the line asked for, however long the page already is.
                if (i > start && sb.length() + row.length() > MAX_RESULT_CHARS) break;
                sb.append(row);
                last = i;
            }
            if (last < end) {
                sb.append(String.format("... stopped at line %d: this is as much as one read returns. "
                        + "%d more line(s); call read with offset=%d to continue.%n",
                        last, totalLines - last, last + 1));
            } else if (end < totalLines) {
                sb.append(String.format("... %d more line(s); call read with offset=%d to continue.%n",
                        totalLines - end, end + 1));
            }
            return sb.toString();
        } catch (Exception e) {
            return "Error: " + Failures.describe(e);
        }
    }

    private static String truncateLine(String line, int lineNumber) {
        return line.length() <= MAX_LINE_CHARS
                ? line
                : line.substring(0, MAX_LINE_CHARS) + " ... [line truncated, " + line.length()
                        + " chars total; read with offset=" + lineNumber + " charOffset=" + MAX_LINE_CHARS
                        + " to continue]";
    }
}
