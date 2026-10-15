package ai.proxyma.connectors.asana;

import ai.proxyma.connectors.workitems.TaskMarkdown;
import ai.proxyma.connectors.workitems.WorkItems;
import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectorException;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemGoneException;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ApiResponse;
import ai.proxyma.core.connector.spi.ConnectorAuthException;
import ai.proxyma.core.connector.spi.ConnectorHttpException;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.Listing;
import ai.proxyma.core.connector.spi.Listings;
import ai.proxyma.core.connector.spi.SourceContext;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One Asana source, opened for one pass or one batch of fetches. Everything it needs is its
 * {@link SourceContext} - the workspace, the projects, the connection's address and credential.
 *
 * <p>Items are {@code task:<gid>}: a task in two of the chosen projects is one item, listed once.
 * Subtasks are not items of their own; each is listed in its parent's document.
 *
 * <p>The cursor is each project's Events sync token, as JSON ({@code {"<project gid>":"<token>"}}).
 * A full pass asks Asana for a fresh token per project before it lists that project's tasks, so a
 * change made while it lists is in the next feed. Asana keeps a token for a day at most; one it no
 * longer knows is answered 412 with a new token, and the deletions of that gap are left to the next
 * full pass. Where the Events feed is refused (a sign-in without it), the source works without it.
 */
final class AsanaSession implements ConnectorSession {

    // Clock skew between this host and Asana: a CHANGES pass asks from a minute before the last
    // complete pass started.
    static final Duration OVERLAP = Duration.ofMinutes(1);
    static final String LIST_FIELDS = "name,modified_at,permalink_url,created_by.name";
    static final String TASK_FIELDS = "name,resource_subtype,notes,assignee.name,created_by.name,start_on,due_on,due_at,"
            + "completed,completed_at,created_at,modified_at,permalink_url,workspace.gid,parent.name,tags.name,"
            + "memberships.project.gid,memberships.project.name,memberships.section.name,"
            + "custom_fields.name,custom_fields.display_value";
    static final String STORY_FIELDS = "type,resource_subtype,text,created_at,created_by.name";
    static final String SUBTASK_FIELDS = "name,completed,assignee.name,due_on";

    record Project(String gid, String name) {}

    private final SourceContext context;
    private final ApiClient api;
    private final String workspaceTyped;
    private final List<String> projectsTyped;
    private String workspaceGid;
    private List<Project> projects;
    // The sync token each project resumes from next - handed back as the cursor once listed.
    private final Map<String, String> tokens = new LinkedHashMap<>();
    // The tasks this listing has handed out - a task in two projects is listed once.
    private final Set<String> listed = new HashSet<>();

    AsanaSession(SourceContext context) {
        this.context = context;
        this.workspaceTyped = AsanaConnectorPlugin.workspace(context.config());
        this.projectsTyped = AsanaConnectorPlugin.projects(context.config());
        if (workspaceTyped.isEmpty()) throw new ConnectorException("An Asana source needs its workspace.");
        this.api = AsanaApi.client(context.http(), context.connection());
    }

    // -- the probe -----------------------------------------------------------------------------

    @Override
    public AccessCheck verifyAccess() {
        try {
            if (isGid(workspaceTyped)) {
                api.getJson("workspaces/" + ApiClient.segment(workspaceTyped), Map.of("opt_fields", "name"));
            }
            workspaceGid();
            projects();
            return AccessCheck.verified();
        } catch (ConnectorHttpException e) {
            if (e.isNotFound()) return AccessCheck.failed(noWorkspace());
            if (e.status() == 401) {
                return AccessCheck.failed("Asana did not accept the token. Replace it in Settings > Connections.");
            }
            return AccessCheck.failed(e.getMessage());
        } catch (ConnectorAuthException e) {
            return AccessCheck.failed(e.getMessage());
        } catch (ConnectorException e) {
            return AccessCheck.failed(e.getMessage());
        }
    }

    // -- listing -------------------------------------------------------------------------------

    /**
     * The tasks of the chosen projects. A full pass lists every one; a CHANGES pass the ones
     * modified since the last complete pass, and then the ones the Events feed says were deleted,
     * taken out of the project, or put back - their fetch decides which.
     */
    @Override
    public Listing list(ListRequest request) {
        boolean changes = !request.isFull() && request.since() != null;
        Map<String, String> stored = changes ? WorkItems.readCursor(request.cursor()) : Map.of();
        listed.clear();
        tokens.clear();
        List<Project> chosen;
        try {
            chosen = projects();
        } catch (ConnectorException e) {
            return Listings.failed(e.getMessage());
        }
        List<Supplier<Iterator<ItemMetadata>>> parts = new ArrayList<>();
        for (Project project : chosen) {
            String token = stored.get(project.gid());
            if (!changes || token == null || token.isBlank()) {
                parts.add(() -> {
                    String fresh = freshToken(project.gid());
                    if (fresh != null) tokens.put(project.gid(), fresh);
                    return taskItems(project, null);
                });
            } else {
                parts.add(() -> taskItems(project, request.since().minus(OVERLAP)));
                parts.add(() -> eventItems(project, token));
            }
        }
        return Listings.withCursor(Listings.concat(parts), () -> WorkItems.writeCursor(tokens));
    }

    private Iterator<ItemMetadata> taskItems(Project project, Instant modifiedSince) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("project", project.gid());
        query.put("opt_fields", LIST_FIELDS);
        if (modifiedSince != null) query.put("modified_since", modifiedSince.toString());
        return Listings.mapped(AsanaApi.paged(api, "tasks", query), json -> {
            String gid = json.path("gid").asText("");
            if (gid.isEmpty() || !listed.add(gid)) return null;
            ItemMetadata item = item(gid, json.path("name").asText(gid), WorkItems.instant(json.path("modified_at").asText(null)));
            WorkItems.putExtra(item, "url", json.path("permalink_url").asText(null));
            WorkItems.putExtra(item, ItemMetadata.AUTHOR, json.path("created_by").path("name").asText(null));
            return item;
        });
    }

    // A fresh sync token for one project: Asana answers a request without one 412, with the token.
    // Null when the feed is not open to this connection; a refused credential or a rate limit fails
    // the listing like any other request.
    private String freshToken(String projectGid) {
        ApiResponse response = api.get("events", Map.of("resource", projectGid));
        if (response.status() == 412 || response.ok()) {
            String sync = response.json().path("sync").asText("");
            return sync.isBlank() ? null : sync;
        }
        if (fatal(response)) throw api.failure(response);
        return null;
    }

    // What the Events feed says happened to the project's tasks since the stored token: the tasks
    // deleted, taken out of the project, put back or added, each listed so that its fetch decides.
    private Iterator<ItemMetadata> eventItems(Project project, String token) {
        List<ItemMetadata> out = new ArrayList<>();
        String sync = token;
        for (int page = 0; page < 1000; page++) {
            ApiResponse response = api.get("events", Map.of("resource", project.gid(), "sync", sync));
            if (response.status() == 412) {
                // Too old: a new token from here on; this gap's deletions wait for the next full pass.
                String fresh = response.json().path("sync").asText("");
                if (fresh.isBlank()) tokens.remove(project.gid());
                else tokens.put(project.gid(), fresh);
                return out.iterator();
            }
            if (!response.ok()) {
                if (fatal(response)) throw api.failure(response);
                tokens.remove(project.gid());
                return out.iterator();
            }
            JsonNode body = response.json();
            for (JsonNode event : body.path("data")) {
                ItemMetadata item = eventItem(project, event);
                if (item != null) out.add(item);
            }
            String next = body.path("sync").asText("");
            if (!next.isBlank()) sync = next;
            tokens.put(project.gid(), sync);
            if (!body.path("has_more").asBoolean(false)) break;
        }
        return out.iterator();
    }

    private ItemMetadata eventItem(Project project, JsonNode event) {
        JsonNode resource = event.path("resource");
        if (!"task".equals(resource.path("resource_type").asText())) return null;
        String action = event.path("action").asText("");
        boolean ofProject = project.gid().equals(event.path("parent").path("gid").asText(""));
        boolean relevant = switch (action) {
            case "deleted", "undeleted" -> true;
            case "removed", "added" -> ofProject;
            default -> false;
        };
        String gid = resource.path("gid").asText("");
        if (!relevant || gid.isEmpty() || !listed.add(gid)) return null;
        Instant at = WorkItems.instant(event.path("created_at").asText(null));
        return item(gid, resource.path("name").asText(gid), at != null ? at : context.clock().instant());
    }

    private static boolean fatal(ApiResponse response) {
        int status = response.status();
        return status == 401 || status == 429 || status >= 500;
    }

    private static ItemMetadata item(String gid, String title, Instant modified) {
        ItemMetadata item = new ItemMetadata();
        item.setId("task:" + gid);
        item.setTitle(title);
        item.setSourceType(ConnectorType.ASANA);
        item.setLastModified(modified);
        item.setContentType(WorkItems.MARKDOWN);
        return item;
    }

    // -- fetching ------------------------------------------------------------------------------

    @Override
    public InputStream fetch(ItemMetadata item) {
        String id = item.getId() == null ? "" : item.getId();
        String gid = id.startsWith("task:") ? id.substring("task:".length()) : "";
        if (!isGid(gid)) throw new ConnectorException("That is not an item of an Asana source.");
        try {
            return WorkItems.markdown(render(gid));
        } catch (ConnectorHttpException e) {
            if (e.isNotFound()) throw new ItemGoneException("Asana no longer has " + id + ".");
            throw e;
        }
    }

    String render(String gid) {
        JsonNode task = api.getJson("tasks/" + gid, Map.of("opt_fields", TASK_FIELDS)).path("data");
        String workspace = task.path("workspace").path("gid").asText("");
        if (!workspace.isEmpty() && !workspace.equals(workspaceGid())) {
            throw new ItemGoneException("Asana's task " + gid + " is no longer in the source's workspace.");
        }
        Set<String> read = projectsTyped.isEmpty() ? Set.of() : chosenGids();
        List<String> projectNames = new ArrayList<>();
        Set<String> sections = new LinkedHashSet<>();
        boolean inSource = false;
        for (JsonNode m : task.path("memberships")) {
            String projectGid = m.path("project").path("gid").asText("");
            if (projectGid.isEmpty()) continue;
            boolean chosen = projectsTyped.isEmpty() || read.contains(projectGid);
            inSource |= chosen;
            projectNames.add(m.path("project").path("name").asText(projectGid));
            String section = m.path("section").path("name").asText("");
            if (chosen && !section.isBlank()) sections.add(section);
        }
        if (!inSource) throw new ItemGoneException("Asana's task " + gid + " is no longer in a project this source reads.");

        TaskMarkdown md = TaskMarkdown.titled(task.path("name").asText(""))
                .field("Projects", projectNames)
                .field("Section", new ArrayList<>(sections));
        String subtype = task.path("resource_subtype").asText("");
        if (!subtype.isEmpty() && !"default_task".equals(subtype)) md.field("Type", subtype.replace('_', ' '));
        md.field("Assignee", task.path("assignee").path("name").asText(""))
                .field("Created by", task.path("created_by").path("name").asText(""))
                .field("Start", task.path("start_on").asText(""))
                .field("Due", task.hasNonNull("due_at") ? task.path("due_at").asText() : task.path("due_on").asText(""))
                .field("Completed", task.path("completed").asBoolean(false)
                        ? "yes" + (task.hasNonNull("completed_at") ? ", " + task.path("completed_at").asText() : "") : "no")
                .field("Parent task", task.path("parent").path("name").asText(""));
        List<String> tags = new ArrayList<>();
        task.path("tags").forEach(t -> tags.add(t.path("name").asText("")));
        md.field("Tags", tags);
        for (JsonNode field : task.path("custom_fields")) {
            md.field(field.path("name").asText("Custom field"), field.path("display_value").asText(""));
        }
        md.field("Created", task.path("created_at").asText(""))
                .field("Modified", task.path("modified_at").asText(""))
                .field("Link", task.path("permalink_url").asText(""))
                .body("Notes", task.path("notes").asText(""), "(no notes)");

        Iterator<JsonNode> stories = AsanaApi.paged(api, "tasks/" + gid + "/stories", Map.of("opt_fields", STORY_FIELDS));
        while (stories.hasNext()) {
            JsonNode story = stories.next();
            if (!"comment".equals(story.path("type").asText()) && !"comment_added".equals(story.path("resource_subtype").asText())) {
                continue;
            }
            md.comment(story.path("created_by").path("name").asText(""), story.path("created_at").asText(""),
                    story.path("text").asText(""));
        }
        Iterator<JsonNode> subtasks = AsanaApi.paged(api, "tasks/" + gid + "/subtasks", Map.of("opt_fields", SUBTASK_FIELDS));
        while (subtasks.hasNext()) {
            JsonNode sub = subtasks.next();
            StringBuilder line = new StringBuilder(sub.path("completed").asBoolean(false) ? "[x] " : "[ ] ")
                    .append(sub.path("name").asText(""));
            List<String> details = new ArrayList<>();
            String assignee = sub.path("assignee").path("name").asText("");
            if (!assignee.isBlank()) details.add(assignee);
            String due = sub.path("due_on").asText("");
            if (!due.isBlank()) details.add("due " + due);
            if (!details.isEmpty()) line.append(" (").append(String.join(", ", details)).append(')');
            md.bullet("Subtasks", line.toString());
        }
        return md.toString();
    }

    // -- the workspace and its projects --------------------------------------------------------

    private String workspaceGid() {
        if (workspaceGid != null) return workspaceGid;
        if (isGid(workspaceTyped)) return workspaceGid = workspaceTyped;
        Iterator<JsonNode> all = AsanaApi.paged(api, "workspaces", Map.of("opt_fields", "name"));
        while (all.hasNext()) {
            JsonNode w = all.next();
            if (w.path("name").asText("").strip().equalsIgnoreCase(workspaceTyped)) {
                return workspaceGid = w.path("gid").asText();
            }
        }
        throw new ConnectorException("Asana has no workspace " + workspaceTyped + ", or the connection cannot see it.");
    }

    private String noWorkspace() {
        return "Asana has no workspace " + workspaceTyped + ", or the connection cannot see it.";
    }

    /** The projects the source reads: those it names, or every unarchived one of the workspace. */
    List<Project> projects() {
        if (projects != null) return projects;
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("workspace", workspaceGid());
        query.put("archived", "false");
        query.put("opt_fields", "name");
        List<Project> all = new ArrayList<>();
        AsanaApi.paged(api, "projects", query).forEachRemaining(p -> all.add(new Project(p.path("gid").asText(), p.path("name").asText(""))));
        if (projectsTyped.isEmpty()) return projects = all;
        List<Project> chosen = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String typed : projectsTyped) {
            Project match = all.stream().filter(p -> p.gid().equals(typed)).findFirst()
                    .or(() -> all.stream().filter(p -> p.name().strip().equalsIgnoreCase(typed)).findFirst())
                    .orElse(null);
            if (match == null) missing.add(typed);
            else if (chosen.stream().noneMatch(p -> p.gid().equals(match.gid()))) chosen.add(match);
        }
        if (!missing.isEmpty()) {
            throw new ConnectorException("Asana has no project " + String.join(", ", missing) + " in the workspace "
                    + workspaceTyped + ", or it is archived, or the connection cannot see it.");
        }
        return projects = chosen;
    }

    // The chosen projects' IDs for a fetch: as typed when every one is an ID - no lookup - else
    // looked up by name.
    private Set<String> chosenGids() {
        Set<String> gids = new HashSet<>();
        if (projectsTyped.stream().allMatch(AsanaSession::isGid)) gids.addAll(projectsTyped);
        else projects().forEach(p -> gids.add(p.gid()));
        return gids;
    }

    static boolean isGid(String value) {
        return value != null && value.matches("\\d+");
    }
}
