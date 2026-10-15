package ai.proxyma.connectors.linear;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectorException;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemGoneException;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ConnectorAuthException;
import ai.proxyma.core.connector.spi.ConnectorHttpException;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.Listing;
import ai.proxyma.core.connector.spi.Listings;
import ai.proxyma.core.connector.spi.Paginator;
import ai.proxyma.core.connector.spi.SourceContext;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * One Linear source, opened for one pass or one batch of fetches. Everything it needs is its
 * {@link SourceContext} - the team keys, the connection's address and credential - and nothing it
 * holds is seen by any other source.
 *
 * <p>Items are {@code issue:<id>}, by Linear's UUID - an issue moved to another team changes its
 * identifier (ENG-12 becomes OPS-40) but not its id, so it is not indexed twice. Each is rendered on
 * fetch as one markdown document with its comments.
 *
 * <p>Pages are small ({@value #PAGE}) on purpose: Linear prices a query by the nodes it may return,
 * and an hour's complexity budget is spent far faster by wide pages than by more requests.
 */
final class LinearSession implements ConnectorSession {

    // Clock skew between this host and Linear: a CHANGES pass asks from a minute before the last
    // complete pass started.
    static final Duration OVERLAP = Duration.ofMinutes(1);
    static final int PAGE = 50;
    static final String MARKDOWN = "text/markdown";

    static final String ISSUES = """
            query Issues($first: Int!, $after: String, $filter: IssueFilter, $includeArchived: Boolean) {
              issues(first: $first, after: $after, filter: $filter, includeArchived: $includeArchived, orderBy: updatedAt) {
                nodes { id identifier title url updatedAt archivedAt trashed creator { name } }
                pageInfo { hasNextPage endCursor }
              }
            }""";

    static final String ISSUE = """
            query Issue($id: String!) {
              issue(id: $id) {
                id identifier title description url priorityLabel estimate dueDate
                createdAt updatedAt startedAt completedAt canceledAt archivedAt trashed
                state { name type } assignee { name } creator { name }
                team { key name } project { name } cycle { number name } parent { identifier title }
                labels(first: 50) { nodes { name } }
                comments(first: 50) { nodes { body createdAt user { name } } pageInfo { hasNextPage endCursor } }
              }
            }""";

    static final String ISSUE_COMMENTS = """
            query IssueComments($id: String!, $after: String) {
              issue(id: $id) {
                comments(first: 50, after: $after) { nodes { body createdAt user { name } } pageInfo { hasNextPage endCursor } }
              }
            }""";

    static final String TEAMS = """
            query Teams($after: String) {
              teams(first: 100, after: $after) { nodes { key name } pageInfo { hasNextPage endCursor } }
            }""";

    private final ApiClient api;
    private final List<String> teams;

    LinearSession(SourceContext context) {
        this.teams = LinearConnectorPlugin.teams(context.config());
        this.api = LinearApi.client(context.http(), context.connection());
    }

    // -- the probe -----------------------------------------------------------------------------

    @Override
    public AccessCheck verifyAccess() {
        try {
            if (teams.isEmpty()) {
                LinearApi.query(api, "Viewer", LinearConnectionPlugin.VIEWER, Map.of());
                return AccessCheck.verified();
            }
            Set<String> visible = new TreeSet<>();
            Paginator.iterate(after -> {
                Map<String, Object> variables = new HashMap<>();
                variables.put("after", after);
                JsonNode page = LinearApi.query(api, "Teams", TEAMS, variables).path("teams");
                return new Paginator.Page<>(Paginator.elements(page.path("nodes")), nextCursor(page));
            }).forEachRemaining(t -> visible.add(t.path("key").asText().toUpperCase(Locale.ROOT)));
            List<String> missing = teams.stream().filter(k -> !visible.contains(k)).toList();
            if (!missing.isEmpty()) {
                return AccessCheck.failed("Linear has no team " + String.join(", ", missing)
                        + ", or the connection cannot see it.");
            }
            return AccessCheck.verified();
        } catch (ConnectorHttpException e) {
            if (e.status() == 401) {
                return AccessCheck.failed("Linear did not accept the key. Replace it in Settings > Connections.");
            }
            return AccessCheck.failed(e.getMessage());
        } catch (ConnectorAuthException e) {
            return AccessCheck.failed(e.getMessage());
        }
    }

    // -- listing -------------------------------------------------------------------------------

    /**
     * The issues of the chosen teams. A full pass leaves archived and trashed issues out, so the
     * engine removes them; a CHANGES pass asks for archived ones too and lists them, so their fetch
     * can say they are gone.
     */
    @Override
    public Listing list(ListRequest request) {
        boolean changes = !request.isFull() && request.since() != null;
        Map<String, Object> filter = new LinkedHashMap<>();
        if (!teams.isEmpty()) filter.put("team", Map.of("key", Map.of("in", teams)));
        if (changes) filter.put("updatedAt", Map.of("gt", request.since().minus(OVERLAP).toString()));
        Iterator<JsonNode> nodes = Paginator.iterate(after -> {
            Map<String, Object> variables = new HashMap<>();
            variables.put("first", PAGE);
            variables.put("after", after);
            variables.put("filter", filter);
            variables.put("includeArchived", changes);
            JsonNode page = LinearApi.query(api, "Issues", ISSUES, variables).path("issues");
            return new Paginator.Page<>(Paginator.elements(page.path("nodes")), nextCursor(page));
        });
        return Listings.mapped(nodes, json -> {
            boolean gone = json.hasNonNull("archivedAt") || json.path("trashed").asBoolean(false);
            if (gone && !changes) return null;
            ItemMetadata item = new ItemMetadata();
            item.setId("issue:" + json.path("id").asText());
            item.setTitle(json.path("identifier").asText("") + " " + json.path("title").asText(""));
            item.setSourceType(ConnectorType.LINEAR);
            item.setLastModified(instant(json.path("updatedAt").asText(null)));
            item.setContentType(MARKDOWN);
            putExtra(item, "url", json.path("url").asText(null));
            putExtra(item, ItemMetadata.AUTHOR, json.path("creator").path("name").asText(null));
            return item;
        });
    }

    private static String nextCursor(JsonNode connection) {
        JsonNode info = connection.path("pageInfo");
        return info.path("hasNextPage").asBoolean(false) ? info.path("endCursor").asText(null) : null;
    }

    // -- fetching ------------------------------------------------------------------------------

    @Override
    public InputStream fetch(ItemMetadata item) {
        String id = item.getId() == null ? "" : item.getId();
        if (!id.startsWith("issue:") || id.length() == "issue:".length()) {
            throw new ConnectorException("That is not an item of a Linear source.");
        }
        String issueId = id.substring("issue:".length());
        try {
            return new ByteArrayInputStream(render(issueId).getBytes(StandardCharsets.UTF_8));
        } catch (ConnectorHttpException e) {
            if (notFound(e)) throw new ItemGoneException("Linear no longer has " + id + ".");
            throw e;
        }
    }

    // Linear answers an id it has no issue for with a GraphQL error, not a 404.
    private static boolean notFound(ConnectorHttpException e) {
        if (e.isNotFound()) return true;
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("entity not found") || message.contains("could not find");
    }

    String render(String issueId) {
        JsonNode issue = LinearApi.query(api, "Issue", ISSUE, Map.of("id", issueId)).path("issue");
        if (issue.isMissingNode() || issue.isNull()) {
            String id = "issue:" + issueId;
            throw new ItemGoneException("Linear no longer has " + id + ".");
        }
        if (issue.hasNonNull("archivedAt") || issue.path("trashed").asBoolean(false)) {
            throw new ItemGoneException("Linear has archived or deleted " + issue.path("identifier").asText(issueId) + ".");
        }
        StringBuilder md = new StringBuilder();
        md.append("# ").append(issue.path("identifier").asText("")).append(' ').append(issue.path("title").asText("")).append("\n\n");
        JsonNode team = issue.path("team");
        if (!team.isMissingNode() && !team.isNull()) {
            md.append("- Team: ").append(team.path("name").asText("")).append(" (").append(team.path("key").asText("")).append(")\n");
        }
        JsonNode state = issue.path("state");
        if (!state.isMissingNode() && !state.isNull()) {
            md.append("- State: ").append(state.path("name").asText(""));
            String type = state.path("type").asText("");
            if (!type.isEmpty()) md.append(" (").append(type).append(')');
            md.append('\n');
        }
        line(md, "Priority", issue.path("priorityLabel").asText(""));
        line(md, "Assignee", issue.path("assignee").path("name").asText(""));
        line(md, "Creator", issue.path("creator").path("name").asText(""));
        line(md, "Project", issue.path("project").path("name").asText(""));
        JsonNode cycle = issue.path("cycle");
        if (cycle.hasNonNull("number")) {
            String name = cycle.path("name").asText("");
            line(md, "Cycle", name.isBlank() ? "Cycle " + cycle.path("number").asInt() : name);
        }
        List<String> labels = new ArrayList<>();
        issue.path("labels").path("nodes").forEach(l -> labels.add(l.path("name").asText()));
        if (!labels.isEmpty()) line(md, "Labels", String.join(", ", labels));
        JsonNode parent = issue.path("parent");
        if (parent.hasNonNull("identifier")) {
            line(md, "Parent", parent.path("identifier").asText() + " " + parent.path("title").asText(""));
        }
        if (issue.hasNonNull("estimate")) line(md, "Estimate", issue.path("estimate").asText());
        line(md, "Due", issue.path("dueDate").asText(""));
        line(md, "Created", issue.path("createdAt").asText(""));
        line(md, "Updated", issue.path("updatedAt").asText(""));
        line(md, "Completed", issue.path("completedAt").asText(""));
        line(md, "Canceled", issue.path("canceledAt").asText(""));
        line(md, "Link", issue.path("url").asText(""));

        String description = issue.path("description").asText("");
        md.append("\n## Description\n\n").append(description.isBlank() ? "(no description)" : description.strip()).append('\n');
        comments(md, issueId, issue.path("comments"));
        return md.toString();
    }

    // Every page of the issue's comments: the first came with the issue, the rest are asked for.
    private void comments(StringBuilder md, String issueId, JsonNode first) {
        boolean any = false;
        JsonNode page = first;
        Set<String> seen = new TreeSet<>();
        while (page != null && !page.isMissingNode() && !page.isNull()) {
            for (JsonNode c : page.path("nodes")) {
                if (!any) md.append("\n## Comments\n");
                any = true;
                md.append("\n**").append(c.path("user").path("name").asText("")).append("**, ")
                        .append(c.path("createdAt").asText("")).append(":\n\n")
                        .append(c.path("body").asText("").strip()).append('\n');
            }
            String after = nextCursor(page);
            if (after == null || !seen.add(after)) break;
            Map<String, Object> variables = new HashMap<>();
            variables.put("id", issueId);
            variables.put("after", after);
            page = LinearApi.query(api, "IssueComments", ISSUE_COMMENTS, variables).path("issue").path("comments");
        }
    }

    private static void line(StringBuilder md, String label, String value) {
        if (value != null && !value.isBlank()) md.append("- ").append(label).append(": ").append(value).append('\n');
    }

    private static Instant instant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void putExtra(ItemMetadata item, String key, String value) {
        if (value != null && !value.isBlank()) item.putExtra(key, value);
    }
}
