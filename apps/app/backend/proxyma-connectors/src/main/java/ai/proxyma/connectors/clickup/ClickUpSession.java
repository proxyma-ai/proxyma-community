package ai.proxyma.connectors.clickup;

import ai.proxyma.connectors.workitems.TaskMarkdown;
import ai.proxyma.connectors.workitems.WorkItems;
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

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One ClickUp source, opened for one pass or one batch of fetches. Everything it needs is its
 * {@link SourceContext} - the workspace, the chosen spaces, folders and lists, the connection's
 * address and credential.
 *
 * <p>Items are {@code task:<id>}, by ClickUp's task id - a subtask is an item of its own as well as a
 * line in its parent's document. Every listing is the workspace's filtered task list
 * ({@code GET /team/{id}/task}), {@value #PAGE} tasks to a page; spaces, folders and lists are each
 * asked for apart and their tasks listed once, so choosing a list and the space it is in reads that
 * list once.
 */
final class ClickUpSession implements ConnectorSession {

    // Clock skew between this host and ClickUp: a CHANGES pass asks from a minute before the last
    // complete pass started.
    static final Duration OVERLAP = Duration.ofMinutes(1);
    static final int PAGE = 100;
    // ClickUp answers the 25 newest comments, and the ones before a comment when asked.
    static final int COMMENT_PAGE = 25;

    private final ApiClient api;
    private final String workspaceTyped;
    private final List<String> spaces;
    private final List<String> folders;
    private final List<String> lists;
    private String teamId;
    // The tasks this listing has handed out - a task in a chosen list and a chosen space is listed once.
    private final Set<String> listed = new HashSet<>();

    ClickUpSession(SourceContext context) {
        Map<String, String> config = context.config();
        this.workspaceTyped = ClickUpConnectorPlugin.workspace(config);
        if (workspaceTyped.isEmpty()) throw new ConnectorException("A ClickUp source needs its workspace.");
        this.spaces = WorkItems.sortedList(config.get(ClickUpConnectorPlugin.SPACES));
        this.folders = WorkItems.sortedList(config.get(ClickUpConnectorPlugin.FOLDERS));
        this.lists = WorkItems.sortedList(config.get(ClickUpConnectorPlugin.LISTS));
        this.api = ClickUpApi.client(context.http(), context.connection());
    }

    private boolean everything() {
        return spaces.isEmpty() && folders.isEmpty() && lists.isEmpty();
    }

    // -- the probe -----------------------------------------------------------------------------

    @Override
    public AccessCheck verifyAccess() {
        try {
            teamId();
            for (String id : spaces) {
                if (!exists("space/", id)) throw new ConnectorException("ClickUp has no space " + id + ", or the connection cannot see it.");
            }
            for (String id : folders) {
                if (!exists("folder/", id)) throw new ConnectorException("ClickUp has no folder " + id + ", or the connection cannot see it.");
            }
            for (String id : lists) {
                if (!exists("list/", id)) throw new ConnectorException("ClickUp has no list " + id + ", or the connection cannot see it.");
            }
            return AccessCheck.verified();
        } catch (ConnectorHttpException e) {
            if (e.status() == 401) {
                return AccessCheck.failed("ClickUp did not accept the token. Replace it in Settings > Connections.");
            }
            return AccessCheck.failed(e.getMessage());
        } catch (ConnectorAuthException e) {
            return AccessCheck.failed(e.getMessage());
        } catch (ConnectorException e) {
            return AccessCheck.failed(e.getMessage());
        }
    }

    private boolean exists(String path, String id) {
        try {
            api.getJson(path + ApiClient.segment(id));
            return true;
        } catch (ConnectorHttpException e) {
            if (e.isNotFound()) return false;
            throw e;
        }
    }

    // -- listing -------------------------------------------------------------------------------

    /** The tasks in scope, closed ones and subtasks included; on a CHANGES pass, those updated since. */
    @Override
    public Listing list(ListRequest request) {
        Instant since = !request.isFull() && request.since() != null ? request.since().minus(OVERLAP) : null;
        listed.clear();
        String team;
        try {
            team = teamId();
        } catch (ConnectorException e) {
            return Listings.failed(e.getMessage());
        }
        List<Supplier<Iterator<ItemMetadata>>> parts = new ArrayList<>();
        if (everything()) parts.add(() -> tasks(team, null, null, since));
        if (!spaces.isEmpty()) parts.add(() -> tasks(team, "space_ids[]", spaces, since));
        if (!folders.isEmpty()) parts.add(() -> tasks(team, "project_ids[]", folders, since));
        if (!lists.isEmpty()) parts.add(() -> tasks(team, "list_ids[]", lists, since));
        return Listings.concat(parts);
    }

    private Iterator<ItemMetadata> tasks(String team, String filter, List<String> ids, Instant since) {
        Iterator<JsonNode> pages = Paginator.iterate(next -> {
            int page = next == null ? 0 : Integer.parseInt(next);
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("page", page);
            query.put("include_closed", "true");
            query.put("subtasks", "true");
            query.put("order_by", "updated");
            if (filter != null) query.put(filter, ids);
            if (since != null) query.put("date_updated_gt", since.toEpochMilli());
            JsonNode body = api.getJson("team/" + ApiClient.segment(team) + "/task", query);
            List<JsonNode> got = Paginator.elements(body.path("tasks"));
            boolean last = body.has("last_page") ? body.path("last_page").asBoolean(true) : got.size() < PAGE;
            return new Paginator.Page<>(got, last || got.isEmpty() ? null : String.valueOf(page + 1));
        });
        return Listings.mapped(pages, task -> {
            String id = task.path("id").asText("");
            if (id.isEmpty() || !listed.add(id)) return null;
            ItemMetadata item = new ItemMetadata();
            item.setId("task:" + id);
            item.setTitle(task.path("name").asText(id));
            item.setSourceType(ConnectorType.CLICKUP);
            item.setLastModified(WorkItems.epochMillis(task.path("date_updated").asText(null)));
            item.setContentType(WorkItems.MARKDOWN);
            WorkItems.putExtra(item, "url", task.path("url").asText(null));
            WorkItems.putExtra(item, ItemMetadata.AUTHOR, task.path("creator").path("username").asText(null));
            return item;
        });
    }

    // -- fetching ------------------------------------------------------------------------------

    @Override
    public InputStream fetch(ItemMetadata item) {
        String id = item.getId() == null ? "" : item.getId();
        String taskId = id.startsWith("task:") ? id.substring("task:".length()) : "";
        if (!taskId.matches("[A-Za-z0-9_-]+")) throw new ConnectorException("That is not an item of a ClickUp source.");
        try {
            return WorkItems.markdown(render(taskId));
        } catch (ConnectorHttpException e) {
            if (e.isNotFound()) throw new ItemGoneException("ClickUp no longer has " + id + ".");
            throw e;
        }
    }

    String render(String taskId) {
        JsonNode task = api.getJson("task/" + ApiClient.segment(taskId),
                Map.of("include_subtasks", "true", "include_markdown_description", "true"));
        if (task.path("archived").asBoolean(false)) throw new ItemGoneException("ClickUp's task " + taskId + " is archived.");
        if (!inScope(task)) {
            throw new ItemGoneException("ClickUp's task " + taskId + " is no longer in a space, folder or list this source reads.");
        }
        TaskMarkdown md = TaskMarkdown.titled(task.path("name").asText(""))
                .field("Task ID", task.path("custom_id").asText(""))
                .field("Status", task.path("status").path("status").asText(""))
                .field("Priority", task.path("priority").path("priority").asText(""))
                .field("Assignees", usernames(task.path("assignees")))
                .field("Creator", task.path("creator").path("username").asText(""))
                .field("Tags", names(task.path("tags"), "name"))
                .field("Folder", task.path("folder").path("hidden").asBoolean(false) ? "" : task.path("folder").path("name").asText(""))
                .field("List", task.path("list").path("name").asText(""))
                .field("Parent task", task.path("parent").asText(""))
                .field("Start", millis(task.path("start_date")))
                .field("Due", millis(task.path("due_date")))
                .field("Created", millis(task.path("date_created")))
                .field("Updated", millis(task.path("date_updated")))
                .field("Closed", millis(task.path("date_closed")));
        for (JsonNode field : task.path("custom_fields")) {
            md.field(field.path("name").asText("Custom field"), ClickUpFields.value(field));
        }
        md.field("Link", task.path("url").asText(""));
        String description = task.path("markdown_description").asText("");
        if (description.isBlank()) description = task.path("text_content").asText("");
        if (description.isBlank()) description = task.path("description").asText("");
        md.body("Description", description);

        for (JsonNode checklist : task.path("checklists")) {
            String heading = "Checklist: " + checklist.path("name").asText("");
            for (JsonNode check : checklist.path("items")) {
                md.bullet(heading, (check.path("resolved").asBoolean(false) ? "[x] " : "[ ] ") + check.path("name").asText(""));
            }
        }
        for (JsonNode attachment : task.path("attachments")) {
            md.bullet("Attachments", attachment.path("title").asText(attachment.path("url").asText("")));
        }
        comments(md, taskId);
        for (JsonNode sub : task.path("subtasks")) {
            StringBuilder line = new StringBuilder(sub.path("name").asText(""));
            List<String> details = new ArrayList<>();
            String status = sub.path("status").path("status").asText("");
            if (!status.isBlank()) details.add(status);
            List<String> who = usernames(sub.path("assignees"));
            if (!who.isEmpty()) details.add(String.join(", ", who));
            if (!details.isEmpty()) line.append(" (").append(String.join("; ", details)).append(')');
            md.bullet("Subtasks", line.toString());
        }
        return md.toString();
    }

    // Every comment, oldest first: ClickUp answers the newest 25, and the 25 before a given one.
    private void comments(TaskMarkdown md, String taskId) {
        List<JsonNode> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, Object> query = new LinkedHashMap<>();
        for (int page = 0; page < 1000; page++) {
            List<JsonNode> got = Paginator.elements(api.getJson("task/" + ApiClient.segment(taskId) + "/comment", query)
                    .path("comments"));
            boolean added = false;
            for (JsonNode c : got) {
                if (seen.add(c.path("id").asText())) {
                    all.add(c);
                    added = true;
                }
            }
            if (got.size() < COMMENT_PAGE || !added) break;
            JsonNode oldest = got.get(got.size() - 1);
            query = new LinkedHashMap<>();
            query.put("start", oldest.path("date").asText(""));
            query.put("start_id", oldest.path("id").asText(""));
        }
        all.sort((a, b) -> Long.compare(a.path("date").asLong(0), b.path("date").asLong(0)));
        for (JsonNode c : all) {
            md.comment(c.path("user").path("username").asText(""), millis(c.path("date")), c.path("comment_text").asText(""));
        }
    }

    // Whether a fetched task is still the source's: in its workspace, and in a chosen space, folder
    // or list when some are chosen.
    private boolean inScope(JsonNode task) {
        String team = task.path("team_id").asText("");
        if (!team.isEmpty() && !team.equals(teamId())) return false;
        if (everything()) return true;
        return spaces.contains(task.path("space").path("id").asText(""))
                || folders.contains(task.path("folder").path("id").asText(""))
                || lists.contains(task.path("list").path("id").asText(""));
    }

    private static List<String> usernames(JsonNode people) {
        List<String> out = new ArrayList<>();
        people.forEach(p -> out.add(p.path("username").asText(p.path("email").asText(""))));
        return out;
    }

    private static List<String> names(JsonNode array, String key) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.path(key).asText("")));
        return out;
    }

    static String millis(JsonNode value) {
        return WorkItems.iso(WorkItems.epochMillis(value == null || value.isNull() ? null : value.asText(null)));
    }

    // -- the workspace -------------------------------------------------------------------------

    private String teamId() {
        if (teamId != null) return teamId;
        JsonNode teams = api.getJson("team").path("teams");
        for (JsonNode t : teams) {
            if (t.path("id").asText("").equals(workspaceTyped)) return teamId = workspaceTyped;
        }
        for (JsonNode t : teams) {
            if (t.path("name").asText("").strip().equalsIgnoreCase(workspaceTyped)) return teamId = t.path("id").asText();
        }
        throw new ConnectorException("ClickUp has no workspace " + workspaceTyped + ", or the connection cannot see it.");
    }
}
