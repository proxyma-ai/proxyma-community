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
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

// The 'grep' tool: searches an offloaded tool-result file (or any file inside the conversations
// directory) line by line and returns matching lines in grep style ("<lineNo>: <text>"), the way
// Claude Code's Grep tool does. In-process Java regex (not the OS grep binary) so it works
// identically on the Windows desktop build and the Linux server build. Offloaded results are
// rendered to readable multi-line text before being written (see SmartChatMemory.offloadResult),
// so line numbers are meaningful and can be fed straight into the 'read' tool's offset. Output is
// returned verbatim via RawTextResultConverter so it isn't re-escaped to a single JSON line.
@Component
public class SearchTextFileTool {

    private static final int DEFAULT_MAX_RESULTS = 50;
    private static final int MAX_MAX_RESULTS = 200;
    // Matched lines longer than this are truncated in the output (see ReadTextFileTool).
    private static final int MAX_LINE_CHARS = 2000;
    // Every match shown, whatever maxResults asked for - see OffloadFileAccess.MAX_PAGE_CHARS.
    static final int MAX_RESULT_CHARS = OffloadFileAccess.MAX_PAGE_CHARS;

    private final String conversationsDir;
    private final boolean multiUser;

    public SearchTextFileTool(@Value("${proxyma.conversations-dir:${user.home}/.proxyma/conversations}") String conversationsDir,
                               @Value("${proxyma.multi-user:false}") boolean multiUser) {
        this.conversationsDir = conversationsDir;
        this.multiUser = multiUser;
    }

    @Tool(name = "grep", resultConverter = RawTextResultConverter.class,
            description = "Search an offloaded tool-result file line by line; returns matches as "
            + "'<lineNumber>: <text>'. Only files under the conversations directory. "
            + "A long result stops early and says which line the rest begins at.")
    @ToolResult(rawText = true,
            description = "Plain text, not JSON (see RawTextResultConverter): a 'Found N matching line(s)' header, "
                    + "then one line per match with its line number. No match returns a 'No matches for ...' "
                    + "sentence, which is a success.")
    public String grep(
            @ToolParam(description = "Absolute path to the file") String filePath,
            @ToolParam(description = "Literal substring, or a regex if regex=true") String query,
            @ToolParam(required = false, description = "Treat query as a regex (default false)") Boolean regex,
            @ToolParam(required = false, description = "Case-sensitive (default false)") Boolean caseSensitive,
            @ToolParam(required = false, description = "Max matching lines, up to 200 (default 50)") Integer maxResults) {
        try {
            Path target = Path.of(filePath).normalize();
            Path allowed = OffloadFileAccess.resolveAllowedRoot(conversationsDir, multiUser);
            if (!OffloadFileAccess.isWithinAllowedRoot(target, allowed)) {
                return OffloadFileAccess.denied("grep", target, allowed);
            }
            if (!Files.exists(target)) {
                return "Error: file not found";
            }

            Pattern pattern;
            try {
                String regexSource = Boolean.TRUE.equals(regex) ? query : Pattern.quote(query);
                int flags = Boolean.TRUE.equals(caseSensitive) ? 0 : Pattern.CASE_INSENSITIVE;
                pattern = Pattern.compile(regexSource, flags);
            } catch (PatternSyntaxException e) {
                return "Error: invalid regex - " + e.getMessage();
            }

            List<String> lines = LenientText.readLines(target);
            // The whole search has this long; see LenientText.withDeadline.
            long deadline = System.nanoTime() + MATCH_BUDGET.toNanos();
            int cap = Math.min(MAX_MAX_RESULTS, Math.max(1, maxResults != null ? maxResults : DEFAULT_MAX_RESULTS));

            StringBuilder sb = new StringBuilder();
            int found = 0;
            boolean truncated = false;
            // Out of room before out of matches - see OffloadFileAccess.MAX_PAGE_CHARS. The line of
            // the first match left out is where the caller carries on from.
            int nextUnshown = 0;
            for (int i = 0; i < lines.size(); i++) {
                java.util.regex.Matcher m = pattern.matcher(LenientText.withDeadline(lines.get(i), deadline));
                if (!m.find()) continue;
                if (found >= cap) {
                    truncated = true;
                    break;
                }
                String row = (i + 1) + ": " + excerpt(lines.get(i), i + 1, m.start()) + System.lineSeparator();
                // Always at least the first match, however long its line.
                if (found > 0 && sb.length() + row.length() > MAX_RESULT_CHARS) {
                    nextUnshown = i + 1;
                    break;
                }
                sb.append(row);
                found++;
            }

            if (found == 0) {
                return "No matches for '" + query + "' in " + target.getFileName() + " (" + lines.size() + " lines).";
            }
            String header = "Found " + found + " matching line(s)" + (truncated ? " (capped at " + cap + ")" : "")
                    + " in " + target.getFileName() + " (" + lines.size() + " lines):" + System.lineSeparator();
            if (nextUnshown > 0) {
                sb.append("... stopped here: this is as much as one grep returns, and more lines match from line ")
                        .append(nextUnshown).append(" on. Narrow the query, or read with offset=")
                        .append(nextUnshown).append(".").append(System.lineSeparator());
            }
            return header + sb;
        } catch (LenientText.TooSlowException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error: " + Failures.describe(e);
        }
    }

    private static final java.time.Duration MATCH_BUDGET = java.time.Duration.ofSeconds(20);

    // A long line is shown as the MAX_LINE_CHARS window AROUND THE MATCH, since 2026-09-29. It was
    // cut from the start, so a match past character 2000 - common in one-line JSON - was reported as
    // found and then not shown. The note says where the window sits and how `read` continues it.
    private static String excerpt(String line, int lineNumber, int matchAt) {
        if (line.length() <= MAX_LINE_CHARS) return line;
        int from = Math.max(0, Math.min(matchAt - 200, line.length() - MAX_LINE_CHARS));
        int to = from + MAX_LINE_CHARS;
        return (from > 0 ? "... " : "") + line.substring(from, to) + " ... [chars " + from + "-" + to + " of "
                + line.length() + "; read with offset=" + lineNumber + " charOffset=" + to + " to continue]";
    }
}
