package ai.proxyma.core.connector.facts;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.bitbucket.BitbucketConnector;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an item's {@link SourceFacts} back out of the markdown its connector rendered - the
 * "## Metadata" table of a Jira issue (JiraIssueMarkdown.issueToMarkdown), the "## Page Details" and
 * "## Location" of a Confluence page (ConfluencePageMarkdown.pageToMarkdown), the details table of a
 * Bitbucket pull request or commit (BitbucketConnector.prToMarkdown, commitToMarkdown).
 *
 * <p><b>From the chunks, not from the connector's API objects</b>, and that is the point: the graph
 * is built from what is already indexed - a source switched on for the graph is read from its
 * keyword index with no re-sync - and the rendered header is the one place an item's metadata
 * survives indexing. It lives HERE, beside the renderers, so a change to one is made with the
 * other in view.
 *
 * <p>Chunks overlap and each starts with its section's heading, so a table row cut by a chunk
 * boundary appears whole at the start of the next; a row is matched only when it is complete. Each
 * fact is kept once however many chunks repeat it.
 *
 * <p>Nothing for SVN, files, web pages or Microsoft 365: they render no metadata header.
 */
public final class SourceFactsReader {

    private SourceFactsReader() {}

    /**
     * The version of what this reader extracts. RAISE IT whenever it learns a field or changes how
     * one is read: every document whose metadata was written by an older version is read again by
     * the graph worker's metadata pass - no model call, no re-sync - so an existing graph gets the
     * change instead of only documents indexed after it.
     */
    public static final int VERSION = 3; // 3: a person's name containing "by " is read whole

    /** The source types whose items carry metadata this reader understands. */
    public static boolean reads(ConnectorType type) {
        return type == ConnectorType.JIRA || type == ConnectorType.CONFLUENCE || type == ConnectorType.BITBUCKET;
    }

    private static final Pattern ROW = Pattern.compile("^\\|\\s*(.+?)\\s*\\|\\s*(.*?)\\s*\\|\\s*$");
    private static final Pattern KEYED = Pattern.compile("^\\[([A-Z][A-Z0-9]{1,9}-\\d+)]\\s*(.*)$");
    private static final Pattern LINKED = Pattern.compile("^\\*\\*(.+?)\\*\\*\\s*\\[([A-Z][A-Z0-9]{1,9}-\\d+)]\\s*(.*)$");
    private static final Pattern TRAILING_PARENS = Pattern.compile("\\s*\\([^()]*\\)\\s*$");
    private static final Pattern TRAILING_LINK_ID = Pattern.compile("\\s*\\[link [^\\]]*]\\s*$");
    private static final Pattern EMAIL = Pattern.compile("\\s*<[^<>]*@[^<>]*>\\s*$");
    private static final Pattern PR_LINE = Pattern.compile("^(PR-\\d+)\\s+(.*?)(?:\\s*\\([A-Z_]*\\))?$");

    /**
     * @param type          the item's source type
     * @param sourceId      the item's id in its source: an issue key, a page id, PR-12, COMMIT-hash
     * @param title         the item's title as indexed
     * @param connectorName the source's name - a Bitbucket source's is PROJECT/repo, which is what
     *                      makes "PR-12" one pull request rather than one per repository
     * @param chunks        the item's chunks, as the keyword index holds them
     */
    public static SourceFacts read(ConnectorType type, String sourceId, String title, String connectorName,
                                   List<String> chunks) {
        if (type == null || sourceId == null || chunks == null || chunks.isEmpty()) return SourceFacts.empty();
        Builder b = new Builder();
        switch (type) {
            case JIRA -> jira(b, sourceId, title, chunks);
            case CONFLUENCE -> confluence(b, title, chunks);
            case BITBUCKET -> bitbucket(b, sourceId, title, connectorName, chunks);
            default -> { }
        }
        return b.build();
    }

    // -- Jira ----------------------------------------------------------------------------------------

    private static void jira(Builder b, String key, String title, List<String> chunks) {
        b.thing(key, "issue", summaryOf(title, key));
        List<Section> sections = sections(chunks);
        Map<String, String> meta = new LinkedHashMap<>();
        for (Section s : sections) if (s.heading().equals("Metadata")) for (String[] row : rows(s.lines())) meta.putIfAbsent(row[0], row[1]);
        Instant created = when(meta.get("Created"));
        // Who it was assigned to, and when, from the change history: each spell from one change to
        // the next. Without history the current assignee holds from creation.
        List<String[]> changes = changeHistory(sections);
        List<String[]> assigneeChanges = changes.stream().filter(c -> c[2].equalsIgnoreCase("assignee")).toList();
        if (!assigneeChanges.isEmpty()) {
            String first = person(assigneeChanges.get(0)[3]);
            if (!first.isEmpty()) b.link(key, "assigned to", first, "person", created, when(assigneeChanges.get(0)[0]), null);
            for (int i = 0; i < assigneeChanges.size(); i++) {
                String to = person(assigneeChanges.get(i)[4]);
                Instant until = i + 1 < assigneeChanges.size() ? when(assigneeChanges.get(i + 1)[0]) : null;
                if (!to.isEmpty()) b.link(key, "assigned to", to, "person", when(assigneeChanges.get(i)[0]), until, null);
            }
        }
        for (String[] c : changes) {
            if (!c[2].equalsIgnoreCase("status") || c[4].isEmpty()) continue;
            b.link(key, "moved to", c[4], "status", null, null, when(c[0]));
        }
        for (Section s : sections) {
            switch (s.heading()) {
                case "Metadata" -> {
                    for (String[] row : rows(s.lines())) {
                        switch (row[0]) {
                            case "Assignee" -> {
                                if (assigneeChanges.isEmpty()) b.link(key, "assigned to", person(row[1]), "person", created, null, null);
                            }
                            case "Reporter" -> b.link(key, "reported by", person(row[1]), "person", created, null, null);
                            case "Components" -> each(row[1]).forEach(c -> b.link(key, "in component", c, "component"));
                            case "Fix Versions" -> each(row[1]).forEach(v -> b.link(key, "fixed in", v, "version"));
                            case "Affects" -> each(row[1]).forEach(v -> b.link(key, "affects", v, "version"));
                            case "Labels" -> each(row[1]).forEach(l -> b.link(key, "labelled", l, "label"));
                            default -> { }
                        }
                    }
                }
                case "Parent" -> s.lines().forEach(line -> {
                    Matcher m = KEYED.matcher(line.trim());
                    if (m.matches()) {
                        b.thing(m.group(1), "issue", blankToNull(m.group(2)));
                        b.link(key, "child of", m.group(1), "issue");
                    }
                });
                case "Subtasks" -> s.lines().forEach(line -> {
                    Matcher m = KEYED.matcher(item(line));
                    if (m.matches()) {
                        b.thing(m.group(1), "issue", blankToNull(stripStatus(m.group(2))));
                        b.link(m.group(1), "child of", key, "issue");
                    }
                });
                case "Linked Issues" -> s.lines().forEach(line -> {
                    Matcher m = LINKED.matcher(item(line));
                    if (m.matches()) {
                        String summary = stripStatus(TRAILING_LINK_ID.matcher(m.group(3)).replaceAll(""));
                        b.thing(m.group(2), "issue", blankToNull(summary));
                        b.link(key, m.group(1).trim(), m.group(2), "issue");
                    }
                });
                default -> { }
            }
        }
    }

    // -- Confluence ----------------------------------------------------------------------------------

    private static void confluence(Builder b, String title, List<String> chunks) {
        if (title == null || title.isBlank()) return;
        String page = title.trim();
        b.thing(page, "page", null);
        for (Section s : sections(chunks)) {
            switch (s.heading()) {
                case "Page Details" -> {
                    for (String[] row : rows(s.lines())) {
                        switch (row[0]) {
                            case "Space" -> {
                                // "KEY - Name", or the key alone.
                                int dash = row[1].indexOf(" - ");
                                b.link(page, "in space", dash > 0 ? row[1].substring(dash + 3) : row[1], "space");
                            }
                            case "Created" -> b.link(page, "created by", byWhom(row[1]), "person", when(beforeBy(row[1])), null, null);
                            case "Last Updated" -> b.link(page, "last updated by", byWhom(row[1]), "person", when(beforeBy(row[1])), null, null);
                            case "Labels" -> each(row[1]).forEach(l -> b.link(page, "labelled", l, "label"));
                            default -> { }
                        }
                    }
                }
                case "Location" -> s.lines().forEach(line -> {
                    if (!line.contains(" > ")) return;
                    List<String> path = new ArrayList<>();
                    for (String part : line.split(" > ")) if (!part.isBlank()) path.add(part.trim());
                    // Each step of the tree is a parent and its child; the last is this page.
                    for (int i = 1; i < path.size(); i++) {
                        b.thing(path.get(i - 1), "page", null);
                        b.thing(path.get(i), "page", null);
                        b.link(path.get(i), "child of", path.get(i - 1), "page");
                    }
                });
                default -> { }
            }
        }
    }

    // -- Bitbucket -----------------------------------------------------------------------------------

    private static void bitbucket(Builder b, String sourceId, String title, String repo, List<String> chunks) {
        String prefix = repo == null || repo.isBlank() ? "" : repo.trim() + " ";
        String item;
        if (sourceId.startsWith("PR-")) {
            item = prefix + sourceId;
            b.thing(item, "pull request", summaryOf(title, sourceId));
        } else if (sourceId.startsWith("COMMIT-")) {
            String hash = sourceId.substring("COMMIT-".length());
            item = prefix + (hash.length() > 11 ? hash.substring(0, 11) : hash);
            b.thing(item, "commit", blankToNull(title));
        } else {
            return;
        }
        List<Section> sections = sections(chunks);
        Map<String, String> details = new LinkedHashMap<>();
        for (Section s : sections) {
            if (s.heading().equals("PR Details") || s.heading().equals("Commit Details")) {
                for (String[] row : rows(s.lines())) details.putIfAbsent(row[0], row[1]);
            }
        }
        Instant created = when(details.getOrDefault("Created", details.get("Date")));
        if ("MERGED".equals(details.get("State")) && details.get("To Branch") != null) {
            b.link(item, "merged into", prefix + details.get("To Branch"), "branch", null, null, when(details.get("Updated")));
        }
        for (Section s : sections) {
            switch (s.heading()) {
                case "PR Details", "Commit Details" -> {
                    for (String[] row : rows(s.lines())) {
                        switch (row[0]) {
                            case "Author" -> b.link(item, "authored by", person(row[1]), "person", created, null, null);
                            case "Committer" -> b.link(item, "committed by", person(row[1]), "person");
                            case "Reviewers" -> {
                                if (!row[1].equals("None")) {
                                    each(row[1]).forEach(r -> b.link(item, "reviewed by", person(r), "person"));
                                }
                            }
                            case "To Branch" -> b.link(item, "merges into", prefix + row[1], "branch");
                            default -> { }
                        }
                    }
                }
                case "Pull Requests" -> s.lines().forEach(line -> {
                    Matcher m = PR_LINE.matcher(item(line));
                    if (m.matches()) {
                        b.thing(prefix + m.group(1), "pull request", blankToNull(m.group(2)));
                        b.link(item, "part of", prefix + m.group(1), "pull request");
                    }
                });
                case "Jira Issues Referenced" -> s.lines().forEach(line -> {
                    int colon = line.indexOf(": ");
                    if (!line.startsWith("Detected in") || colon < 0) return;
                    for (String key : BitbucketConnector.extractJiraIssueKeys(line.substring(colon + 2))) {
                        b.link(item, "references", key, "issue");
                    }
                });
                default -> { }
            }
        }
    }

    // -- reading the markdown ------------------------------------------------------------------------

    private record Section(String heading, List<String> lines) {}

    /**
     * Each chunk as the section it belongs to: ChunkingService prefixes every chunk with its
     * section's heading, so the first line names the section.
     */
    private static List<Section> sections(List<String> chunks) {
        List<Section> out = new ArrayList<>();
        for (String chunk : chunks) {
            if (chunk == null || chunk.isBlank()) continue;
            String[] lines = chunk.split("\\r?\\n");
            // The heading's TEXT, its '#' marks taken off by ChunkingService. A chunk with no
            // heading starts with whatever it holds, which names no section read here.
            String heading = lines[0].trim().replaceFirst("^#+\\s*", "").trim();
            List<String> body = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) body.add(lines[i]);
            out.add(new Section(heading, body));
        }
        return out;
    }

    /** The complete table rows of a section, header and separator rows left out, cells unescaped. */
    private static List<String[]> rows(List<String> lines) {
        List<String[]> out = new ArrayList<>();
        for (String line : lines) {
            String t = line.trim();
            if (!t.startsWith("|") || !t.endsWith("|") || t.startsWith("|--") || t.startsWith("|---")) continue;
            // An escaped pipe inside a cell must not end it.
            String masked = t.replace("\\|", "\u0001");
            Matcher m = ROW.matcher(masked);
            if (!m.matches()) continue;
            String field = m.group(1).replace("\u0001", "|").trim();
            String value = m.group(2).replace("\u0001", "|").trim();
            if (field.equals("Field") || value.isEmpty()) continue;
            out.add(new String[] {field, value});
        }
        return out;
    }

    /**
     * The rows of a Jira "Change History" table - date, author, field, from, to - oldest first.
     * Five cells, so not {@link #rows}, which reads two.
     */
    private static List<String[]> changeHistory(List<Section> sections) {
        Set<String> seen = new LinkedHashSet<>();
        List<String[]> out = new ArrayList<>();
        for (Section s : sections) {
            if (!s.heading().equals("Change History")) continue;
            for (String line : s.lines()) {
                String t = line.trim();
                if (!t.startsWith("|") || !t.endsWith("|") || t.startsWith("|--") || t.startsWith("| Date |")) continue;
                String masked = t.substring(1, t.length() - 1).replace("\\|", "\u0001");
                String[] cells = masked.split("\\|", -1);
                if (cells.length != 5) continue;
                for (int i = 0; i < 5; i++) cells[i] = cells[i].replace("\u0001", "|").trim();
                if (when(cells[0]) == null || !seen.add(String.join("\u0002", cells))) continue;
                out.add(cells);
            }
        }
        out.sort(java.util.Comparator.comparing((String[] c) -> when(c[0])));
        return out;
    }

    private static final java.time.format.DateTimeFormatter DISPLAYED =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * A date as the renderers print it - "yyyy-MM-dd HH:mm", the source's wall clock with no zone -
     * read as UTC. Minutes off at worst, which a timeline of days does not show. Null when absent or
     * in any other shape.
     */
    static Instant when(String text) {
        if (text == null || text.isBlank()) return null;
        String t = text.trim();
        if (t.length() > 16) t = t.substring(0, 16);
        try {
            return java.time.LocalDateTime.parse(t, DISPLAYED).toInstant(java.time.ZoneOffset.UTC);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    // "2026-10-01 09:00 by Ana Lima": the date, then the person. One pattern for both halves - the
    // person was taken after the last "by ", which is inside "Bobby Smith", so the edge named
    // "Smith" (and Ruby Lee "Lee", and every Abby, Toby, Shelby...).
    private static final Pattern WHEN_BY_WHOM =
            Pattern.compile("^(?:(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2})\\s+)?by\\s+(.+)$");

    // "2026-10-01 09:00 by Ana Lima" -> "2026-10-01 09:00"
    private static String beforeBy(String raw) {
        Matcher m = WHEN_BY_WHOM.matcher(raw.trim());
        if (m.matches()) return m.group(1) == null ? "" : m.group(1);
        int by = raw.indexOf(" by ");
        return by < 0 ? raw : raw.substring(0, by);
    }

    private static String item(String line) {
        String t = line.trim();
        return t.startsWith("- ") ? t.substring(2).trim() : t;
    }

    private static List<String> each(String list) {
        List<String> out = new ArrayList<>();
        for (String part : list.split(",")) if (!part.isBlank()) out.add(part.trim());
        return out;
    }

    // "Ana Lima (ana@example.com)", "Ana Lima <ana@example.com>", "Ana Lima (approved)" -> "Ana Lima"
    private static String person(String raw) {
        String p = EMAIL.matcher(raw.trim()).replaceAll("");
        p = TRAILING_PARENS.matcher(p).replaceAll("");
        return p.trim();
    }

    // "2026-10-01 09:00 by Ana Lima" -> "Ana Lima"
    private static String byWhom(String raw) {
        Matcher m = WHEN_BY_WHOM.matcher(raw.trim());
        if (m.matches()) return m.group(2).trim();
        int by = raw.indexOf(" by ");
        return by < 0 ? null : raw.substring(by + 4).trim();
    }

    private static String stripStatus(String text) {
        return TRAILING_PARENS.matcher(text.trim()).replaceAll("").trim();
    }

    // A title indexed as "[KEY] Summary" or the summary alone - the summary either way.
    private static String summaryOf(String title, String key) {
        if (title == null) return null;
        String t = title.trim();
        String prefix = "[" + key + "]";
        if (t.startsWith(prefix)) t = t.substring(prefix.length()).trim();
        return blankToNull(t);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Things by name, links once each. */
    private static final class Builder {
        private final Map<String, SourceFacts.Thing> things = new LinkedHashMap<>();
        private final Set<SourceFacts.Link> links = new LinkedHashSet<>();

        void thing(String name, String type, String description) {
            if (name == null || name.isBlank() || "Unknown".equals(name) || "None".equals(name)) return;
            things.merge(name.trim(), new SourceFacts.Thing(name.trim(), type, description),
                    (old, now) -> old.description() == null && now.description() != null ? now : old);
        }

        void link(String subject, String predicate, String object, String objectType) {
            link(subject, predicate, object, objectType, null, null, null);
        }

        void link(String subject, String predicate, String object, String objectType,
                  Instant validFrom, Instant validTo, Instant occurredAt) {
            if (object == null || object.isBlank() || "Unknown".equals(object) || "None".equals(object)) return;
            if (!things.containsKey(subject) || subject.equals(object.trim())) return;
            thing(object, objectType, null);
            links.add(new SourceFacts.Link(subject, predicate, object.trim(), validFrom, validTo, occurredAt));
        }

        SourceFacts build() {
            return new SourceFacts(List.copyOf(things.values()), List.copyOf(links));
        }
    }
}
