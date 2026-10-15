package ai.proxyma.connectors.workitems;

import java.util.Collection;
import java.util.List;

/**
 * One work item - an Asana task, a monday.com item, a Trello card, a ClickUp task - as the markdown
 * document it is indexed as. The work-management connectors share it so a task reads the same
 * whichever tool it came from: its name as the title, its details as a list of {@code - Label:
 * value} lines, then its description, and then sections of entries - comments with their author and
 * time, subtasks, checklists, attachments.
 *
 * <pre>{@code
 * TaskMarkdown.titled("Fix login")
 *         .field("Assignee", "Ana").field("Due", "2026-10-20").field("Tags", List.of("bug", "web"))
 *         .body("Description", notes)
 *         .comment("Bo", "2026-10-12T09:30:00Z", "Reproduced.")
 *         .bullet("Subtasks", "[x] Write the test")
 *         .toString();
 * }</pre>
 *
 * A blank value is left out rather than printed empty, so a sparse task stays short. A section's
 * heading is written before its first entry, and only when it has one.
 */
public final class TaskMarkdown {

    /** The heading comments go under. */
    public static final String COMMENTS = "Comments";

    private final StringBuilder md = new StringBuilder();
    private String section;
    private boolean inList;

    private TaskMarkdown(String title) {
        md.append("# ").append(oneLine(title == null || title.isBlank() ? "(untitled)" : title)).append("\n\n");
    }

    public static TaskMarkdown titled(String title) {
        return new TaskMarkdown(title);
    }

    /** {@code - Label: value} among the details; nothing when the value is blank. */
    public TaskMarkdown field(String label, String value) {
        if (value == null || value.isBlank()) return this;
        md.append("- ").append(label).append(": ").append(oneLine(value.strip())).append('\n');
        return this;
    }

    /** {@code - Label: a, b, c}; nothing when there are none. */
    public TaskMarkdown field(String label, Collection<String> values) {
        if (values == null) return this;
        List<String> kept = values.stream().filter(v -> v != null && !v.isBlank()).map(String::strip).toList();
        return kept.isEmpty() ? this : field(label, String.join(", ", kept));
    }

    /** A section of free text - a description, notes - with {@code empty} in its place when blank. */
    public TaskMarkdown body(String heading, String text, String empty) {
        String value = text == null ? "" : text.strip();
        if (value.isEmpty() && empty == null) return this;
        md.append("\n## ").append(heading).append("\n\n").append(value.isEmpty() ? empty : value).append('\n');
        section = heading;
        inList = false;
        return this;
    }

    public TaskMarkdown body(String heading, String text) {
        return body(heading, text, "(no description)");
    }

    /** One comment under Comments: who, when, and what they wrote. */
    public TaskMarkdown comment(String author, String when, String text) {
        return entry(COMMENTS, author, when, text);
    }

    /** One entry under {@code heading}: {@code **author**, when:} and its text. */
    public TaskMarkdown entry(String heading, String author, String when, String text) {
        open(heading);
        md.append("\n**").append(blank(author) ? "Someone" : oneLine(author.strip())).append("**");
        if (!blank(when)) md.append(", ").append(when.strip());
        md.append(":\n\n").append(text == null || text.isBlank() ? "(empty)" : text.strip()).append('\n');
        inList = false;
        return this;
    }

    /** One line of a bulleted section - a subtask, a checklist item, an attachment. */
    public TaskMarkdown bullet(String heading, String line) {
        if (blank(line)) return this;
        open(heading);
        if (!inList) md.append('\n');
        md.append("- ").append(oneLine(line.strip())).append('\n');
        inList = true;
        return this;
    }

    @Override
    public String toString() {
        return md.toString();
    }

    private void open(String heading) {
        if (heading.equals(section)) return;
        md.append("\n## ").append(heading).append('\n');
        section = heading;
        inList = false;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    // A value on one line: a name with a line break in it would end its list item early.
    private static String oneLine(String s) {
        return s.replaceAll("\\s*[\\r\\n]+\\s*", " ");
    }
}
