package ai.proxyma.connectors.gitlab;

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
import ai.proxyma.core.connector.spi.Paginator;
import ai.proxyma.core.connector.spi.SourceContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpMethod;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * One GitLab project or group, opened for one pass or one batch of fetches. Everything it needs is
 * its {@link SourceContext} - the path, its switches, the connection's address and credential - and
 * nothing it holds is seen by any other source.
 *
 * <p>Items, by id - each names its project by GitLab's numeric id, which survives a rename or a move
 * between groups, so a renamed project is not re-indexed under new ids:
 * <ul>
 *   <li>{@code issue:<project>:<iid>} and {@code mr:<project>:<iid>} - listed from the project's or
 *       the group's {@code /issues} and {@code /merge_requests}, rendered on fetch as one markdown
 *       document with the comments (GitLab's system notes - "changed the label" - left out);</li>
 *   <li>{@code file:<project>:<path>} - a documentation file on the branch, listed from the
 *       project's repository tree with its blob id as the content hash, fetched raw.</li>
 * </ul>
 */
final class GitLabSession implements ConnectorSession {

    // Clock skew between this host and GitLab: a CHANGES pass asks from a minute before the last
    // complete pass started.
    static final Duration OVERLAP = Duration.ofMinutes(1);
    // A documentation file larger than this is not read. GitLab's tree does not state sizes, so the
    // file's size is asked for (a HEAD) when it is fetched.
    static final long MAX_DOC_BYTES = 1_000_000;
    static final Set<String> DOC_EXTENSIONS = Set.of("md", "mdx", "markdown", "rst", "txt", "adoc", "asciidoc");
    static final String MARKDOWN = "text/markdown";
    static final int PER_PAGE = 100;

    private final SourceContext context;
    private final ApiClient api;
    private final String path;
    private JsonNode project;
    private JsonNode group;
    private final Map<String, JsonNode> projects = new HashMap<>();
    private String headsDigest;

    GitLabSession(SourceContext context) {
        this.context = context;
        this.path = GitLabConnectorPlugin.path(context.config());
        if (path.isEmpty()) throw new ConnectorException("A GitLab source needs the path of its project or group.");
        this.api = GitLabApi.client(context.http(), context.connection());
    }

    // -- the probe -----------------------------------------------------------------------------

    @Override
    public AccessCheck verifyAccess() {
        try {
            resolve();
            return AccessCheck.verified();
        } catch (ConnectorHttpException e) {
            if (e.status() == 404) {
                return AccessCheck.failed("GitLab has no project or group " + path + ", or the token cannot see it.");
            }
            if (e.status() == 401) {
                return AccessCheck.failed("GitLab did not accept the token. Replace it in Settings > Connections.");
            }
            return AccessCheck.failed(e.getMessage());
        } catch (ConnectorAuthException e) {
            return AccessCheck.failed(e.getMessage());
        }
    }

    // What the path names: a project when GitLab has one there, else a group. Both are looked up by
    // their full path, encoded as one segment as GitLab's API asks.
    private void resolve() {
        if (project != null || group != null) return;
        ApiResponse asProject = api.get("projects/" + ApiClient.segment(path));
        if (asProject.ok()) {
            project = asProject.json();
            projects.put(project.path("id").asText(), project);
            return;
        }
        if (asProject.status() != 404) throw api.failure(asProject);
        group = api.getJson("groups/" + ApiClient.segment(path), Map.of("with_projects", "false"));
    }

    private boolean isGroup() {
        resolve();
        return group != null;
    }

    // The issues and merge requests of the project, or of every project under the group.
    private String scope() {
        resolve();
        return group != null ? "groups/" + group.path("id").asText() : "projects/" + project.path("id").asText();
    }

    // -- listing -------------------------------------------------------------------------------

    @Override
    public Listing list(ListRequest request) {
        boolean issues = context.flag(GitLabConnectorPlugin.ISSUES, true);
        boolean mergeRequests = context.flag(GitLabConnectorPlugin.MERGE_REQUESTS, true);
        boolean docs = context.flag(GitLabConnectorPlugin.DOCS, false);
        List<Supplier<Iterator<ItemMetadata>>> parts = new ArrayList<>();
        if (issues) parts.add(() -> workItems(request, "issues", "issue:", "#"));
        if (mergeRequests) parts.add(() -> workItems(request, "merge_requests", "mr:", "!"));
        if (docs) parts.add(() -> fileItems(request));
        return Listings.withCursor(Listings.concat(parts), () -> docs && headsDigest != null ? "heads:" + headsDigest : null);
    }

    // Issues or merge requests, oldest change first. On a CHANGES pass, only those changed since the
    // last complete pass - a new comment moves updated_at.
    private Iterator<ItemMetadata> workItems(ListRequest request, String kind, String prefix, String sigil) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("scope", "all");
        query.put("order_by", "updated_at");
        query.put("sort", "asc");
        query.put("per_page", PER_PAGE);
        if (!request.isFull() && request.since() != null) {
            query.put("updated_after", request.since().minus(OVERLAP).toString());
        }
        boolean inGroup = isGroup();
        Iterator<JsonNode> pages = Paginator.linkHeader(api, scope() + "/" + kind, query, page -> page);
        return Listings.mapped(pages, json -> {
            ItemMetadata item = new ItemMetadata();
            String iid = json.path("iid").asText();
            item.setId(prefix + json.path("project_id").asText() + ":" + iid);
            String reference = inGroup ? json.path("references").path("full").asText(sigil + iid) : sigil + iid;
            item.setTitle(reference + " " + json.path("title").asText(""));
            item.setSourceType(ConnectorType.GITLAB);
            item.setLastModified(instant(json.path("updated_at").asText(null)));
            item.setContentType(MARKDOWN);
            putExtra(item, "url", json.path("web_url").asText(null));
            putExtra(item, ItemMetadata.AUTHOR, json.path("author").path("username").asText(null));
            return item;
        });
    }

    // The documentation files on each project's branch. The branch heads are read first; on a
    // CHANGES pass, heads whose digest is the stored cursor have nothing new, and no tree is read.
    private Iterator<ItemMetadata> fileItems(ListRequest request) {
        List<JsonNode> all = new ArrayList<>();
        if (isGroup()) {
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("include_subgroups", "true");
            query.put("archived", "false");
            query.put("with_shared", "false");
            query.put("order_by", "id");
            query.put("sort", "asc");
            query.put("per_page", PER_PAGE);
            Paginator.linkHeader(api, scope() + "/projects", query, page -> page).forEachRemaining(p -> {
                projects.put(p.path("id").asText(), p);
                all.add(p);
            });
        } else {
            all.add(project);
        }

        Map<String, String> heads = new TreeMap<>();
        Map<String, JsonNode> headCommits = new HashMap<>();
        for (JsonNode p : all) {
            String id = p.path("id").asText();
            String branch = branchOf(p);
            if (branch == null || p.path("empty_repo").asBoolean(false)) continue;
            ApiResponse response = api.get("projects/" + id + "/repository/branches/" + ApiClient.segment(branch));
            if (response.status() == 404) {
                // A group's project without that branch has no documentation to list; a project
                // source asked for a branch it does not have is told so.
                if (isGroup()) continue;
                throw new ConnectorException("GitLab has no branch " + branch + " in " + path + ".");
            }
            JsonNode commit = api.requireOk(response).json().path("commit");
            heads.put(id, commit.path("id").asText(""));
            headCommits.put(id, commit);
        }
        headsDigest = digest(heads);
        if (!request.isFull() && ("heads:" + headsDigest).equals(request.cursor())) return Collections.emptyIterator();

        String under = docsPath();
        List<Supplier<Iterator<ItemMetadata>>> trees = new ArrayList<>();
        for (JsonNode p : all) {
            String id = p.path("id").asText();
            if (!heads.containsKey(id)) continue;
            trees.add(() -> treeItems(p, branchOf(p), under, instant(headCommits.get(id).path("committed_date").asText(null))));
        }
        return Listings.concat(trees);
    }

    private Iterator<ItemMetadata> treeItems(JsonNode p, String branch, String under, Instant committed) {
        String id = p.path("id").asText();
        String web = p.path("web_url").asText("");
        String prefix = isGroup() ? p.path("path_with_namespace").asText(id) + "/" : "";
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("ref", branch);
        query.put("recursive", "true");
        query.put("per_page", PER_PAGE);
        query.put("pagination", "keyset");
        Iterator<JsonNode> entries = Paginator.linkHeader(api, "projects/" + id + "/repository/tree", query, page -> page);
        return Listings.mapped(entries, entry -> {
            if (!"blob".equals(entry.path("type").asText())) return null;
            String file = entry.path("path").asText("");
            if (!under.isEmpty() && !file.startsWith(under)) return null;
            if (!isDocumentation(file)) return null;
            ItemMetadata item = new ItemMetadata();
            item.setId("file:" + id + ":" + file);
            item.setTitle(prefix + file);
            item.setSourceType(ConnectorType.GITLAB);
            item.setLastModified(committed);
            item.setContentType(contentTypeOf(file));
            putExtra(item, ItemMetadata.CONTENT_HASH, entry.path("id").asText(null));
            if (!web.isEmpty()) putExtra(item, "url", web + "/-/blob/" + branch + "/" + file);
            return item;
        });
    }

    static boolean isDocumentation(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (name.startsWith("readme") || name.startsWith("changelog")) return true;
        int dot = name.lastIndexOf('.');
        return dot > 0 && DOC_EXTENSIONS.contains(name.substring(dot + 1));
    }

    private static String contentTypeOf(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".mdx") || lower.endsWith(".markdown") ? MARKDOWN : "text/plain";
    }

    // The heads, in project order, as one short value: a cursor that changes when any branch moves.
    private static String digest(Map<String, String> heads) {
        StringBuilder all = new StringBuilder();
        heads.forEach((id, sha) -> all.append(id).append('=').append(sha).append(';'));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(all.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // -- fetching ------------------------------------------------------------------------------

    @Override
    public InputStream fetch(ItemMetadata item) {
        String id = item.getId() == null ? "" : item.getId();
        String[] parts = id.split(":", 3);
        if (parts.length != 3 || !parts[1].matches("\\d+")) {
            throw new ConnectorException("That is not an item of a GitLab project.");
        }
        String projectId = parts[1];
        try {
            switch (parts[0]) {
                case "issue" -> {
                    return markdown(renderIssue(projectId, iid(parts[2])));
                }
                case "mr" -> {
                    return markdown(renderMergeRequest(projectId, iid(parts[2])));
                }
                case "file" -> {
                    return file(projectId, parts[2]);
                }
                default -> throw new ConnectorException("That is not an item of a GitLab project.");
            }
        } catch (ConnectorHttpException e) {
            if (e.isNotFound()) throw new ItemGoneException("GitLab no longer has " + id + " in " + path + ".");
            throw e;
        }
    }

    private static int iid(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new ConnectorException("That is not an item of a GitLab project.");
        }
    }

    // A documentation file, raw. Its size is asked for first: GitLab's tree does not say it, and a
    // file past the cap is not documentation however it is named.
    private InputStream file(String projectId, String file) {
        String branch = branchOf(project(projectId));
        if (branch == null) throw new ItemGoneException("GitLab has no branch to read " + file + " from.");
        String base = "projects/" + projectId + "/repository/files/" + ApiClient.segment(file);
        ApiResponse head = api.send(HttpMethod.HEAD, base, Map.of("ref", branch), null, Map.of());
        if (head.status() == 404 || head.status() == 410) throw api.failure(head);
        String size = head.ok() ? head.header("X-Gitlab-Size") : null;
        if (size != null && size.trim().matches("\\d+") && Long.parseLong(size.trim()) > MAX_DOC_BYTES) {
            throw new ConnectorException(file + " is larger than 1 MB, so it is not read as documentation.");
        }
        return api.stream(base + "/raw", Map.of("ref", branch), Map.of());
    }

    String renderIssue(String projectId, int iid) {
        JsonNode issue = api.getJson("projects/" + projectId + "/issues/" + iid);
        String type = issue.path("issue_type").asText("issue");
        String kind = "issue".equalsIgnoreCase(type) ? "Issue" : "Issue (" + type.toLowerCase(Locale.ROOT).replace('_', ' ') + ")";
        StringBuilder md = header(issue, "#", kind, state(issue.path("state").asText("")));
        if (issue.path("confidential").asBoolean(false)) md.append("- Confidential: yes\n");
        if (issue.hasNonNull("due_date")) md.append("- Due: ").append(issue.path("due_date").asText()).append('\n');
        footer(md, issue);
        body(md, issue);
        notes(md, "projects/" + projectId + "/issues/" + iid + "/notes");
        return md.toString();
    }

    String renderMergeRequest(String projectId, int iid) {
        JsonNode mr = api.getJson("projects/" + projectId + "/merge_requests/" + iid);
        String state = state(mr.path("state").asText(""));
        if (mr.path("draft").asBoolean(false) || mr.path("work_in_progress").asBoolean(false)) state = state + " (draft)";
        StringBuilder md = header(mr, "!", "Merge request", state);
        md.append("- Branch: ").append(mr.path("source_branch").asText("")).append(" -> ")
                .append(mr.path("target_branch").asText("")).append('\n');
        List<String> reviewers = usernames(mr.path("reviewers"));
        if (!reviewers.isEmpty()) md.append("- Reviewers: ").append(String.join(", ", reviewers)).append('\n');
        if (mr.hasNonNull("merged_at")) md.append("- Merged: ").append(mr.path("merged_at").asText()).append('\n');
        footer(md, mr);
        body(md, mr);
        notes(md, "projects/" + projectId + "/merge_requests/" + iid + "/notes");
        return md.toString();
    }

    // GitLab says "opened"; a reader says "open".
    private static String state(String raw) {
        return "opened".equals(raw) ? "open" : raw;
    }

    private static StringBuilder header(JsonNode json, String sigil, String kind, String state) {
        StringBuilder md = new StringBuilder();
        String full = json.path("references").path("full").asText("");
        String project = full.contains(sigil) ? full.substring(0, full.lastIndexOf(sigil)) : "";
        md.append("# ").append(sigil).append(json.path("iid").asInt()).append(' ').append(json.path("title").asText("")).append("\n\n");
        if (!project.isEmpty()) md.append("- Project: ").append(project).append('\n');
        md.append("- Type: ").append(kind).append('\n');
        md.append("- State: ").append(state).append('\n');
        md.append("- Author: ").append(json.path("author").path("username").asText("")).append('\n');
        List<String> assignees = usernames(json.path("assignees"));
        if (!assignees.isEmpty()) md.append("- Assignees: ").append(String.join(", ", assignees)).append('\n');
        List<String> labels = new ArrayList<>();
        json.path("labels").forEach(l -> labels.add(l.isTextual() ? l.asText() : l.path("name").asText()));
        if (!labels.isEmpty()) md.append("- Labels: ").append(String.join(", ", labels)).append('\n');
        if (json.hasNonNull("milestone")) md.append("- Milestone: ").append(json.path("milestone").path("title").asText()).append('\n');
        return md;
    }

    private static void footer(StringBuilder md, JsonNode json) {
        md.append("- Created: ").append(json.path("created_at").asText("")).append('\n');
        md.append("- Updated: ").append(json.path("updated_at").asText("")).append('\n');
        if (json.hasNonNull("closed_at")) md.append("- Closed: ").append(json.path("closed_at").asText()).append('\n');
        md.append("- Link: ").append(json.path("web_url").asText("")).append('\n');
    }

    private static List<String> usernames(JsonNode people) {
        List<String> out = new ArrayList<>();
        people.forEach(p -> out.add(p.path("username").asText()));
        return out;
    }

    private static void body(StringBuilder md, JsonNode json) {
        String body = json.path("description").asText("");
        md.append("\n## Description\n\n").append(body.isBlank() ? "(no description)" : body.strip()).append('\n');
    }

    // Every page of an issue's or merge request's comments, oldest first. A comment on a line of a
    // merge request's diff says which file it is on.
    private void notes(StringBuilder md, String notesPath) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("sort", "asc");
        query.put("order_by", "created_at");
        query.put("per_page", PER_PAGE);
        Iterator<JsonNode> all = Paginator.linkHeader(api, notesPath, query, page -> page);
        boolean any = false;
        while (all.hasNext()) {
            JsonNode note = all.next();
            if (note.path("system").asBoolean(false)) continue;
            if (!any) md.append("\n## Comments\n");
            any = true;
            md.append("\n**").append(note.path("author").path("username").asText("")).append("**");
            String file = note.path("position").path("new_path").asText("");
            if (!file.isEmpty()) md.append(" on `").append(file).append('`');
            md.append(", ").append(note.path("created_at").asText("")).append(":\n\n")
                    .append(note.path("body").asText("").strip()).append('\n');
        }
    }

    // -- projects ------------------------------------------------------------------------------

    private JsonNode project(String projectId) {
        JsonNode known = projects.get(projectId);
        if (known == null) {
            known = api.getJson("projects/" + projectId);
            projects.put(projectId, known);
        }
        return known;
    }

    // The configured branch, else the project's default; null for a project with no repository yet.
    private String branchOf(JsonNode p) {
        String configured = context.get(GitLabConnectorPlugin.BRANCH);
        if (configured != null && !configured.isBlank()) return configured.strip();
        String fromProject = p.path("default_branch").asText("");
        return fromProject.isEmpty() ? null : fromProject;
    }

    private String docsPath() {
        String raw = context.get(GitLabConnectorPlugin.DOCS_PATH);
        if (raw == null || raw.isBlank()) return "";
        String p = raw.strip().replace('\\', '/').replaceAll("^/+", "");
        return p.isEmpty() || p.endsWith("/") ? p : p + "/";
    }

    private static InputStream markdown(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Instant instant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso);
        } catch (RuntimeException e) {
            try {
                return java.time.OffsetDateTime.parse(iso).toInstant();
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }

    private static void putExtra(ItemMetadata item, String key, String value) {
        if (value != null && !value.isBlank()) item.putExtra(key, value);
    }
}
