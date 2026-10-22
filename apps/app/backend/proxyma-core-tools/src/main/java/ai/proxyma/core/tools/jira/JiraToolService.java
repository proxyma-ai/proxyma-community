package ai.proxyma.core.tools.jira;

import ai.proxyma.core.common.net.HttpVerb;
import ai.proxyma.core.tools.ToolConnectorResolver;
import ai.proxyma.core.tools.ToolConnectorResolver.Access;
import ai.proxyma.core.tools.ToolIds;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.jira.JiraConnector;
import ai.proxyma.core.connector.spi.AtlassianCloud;
import ai.proxyma.core.connector.spi.AtlassianServer;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.settings.service.McpGuardService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

@Service
class JiraToolService {

    private static final String LABEL = "Jira";

    private final ToolConnectorResolver resolver;
    private final JiraConnector jiraConnector;
    private final McpGuardService mcpGuard;

    JiraToolService(ToolConnectorResolver resolver, JiraConnector jiraConnector, McpGuardService mcpGuard) {
        this.resolver = resolver;
        this.jiraConnector = jiraConnector;
        this.mcpGuard = mcpGuard;
    }

    // Each method resolves the connection's server and credential ONCE (on/run below; see
    // ToolConnectorResolver's class comment for which connection a call reaches and the credential
    // rule) and passes them straight into JiraConnector's explicit-token overloads - no lock and no
    // mutation of the shared connector, so a chat turn's parallel tool calls run concurrently.
    //
    // THE GATES. The connection's Read and Write switches are enforced by that resolution, with the
    // access the call is about to use. mcpGuard.checkJiraRead checks the license tier only. A
    // model-supplied id is checked for its shape (ToolIds) before it reaches a path.

    // fields is merged LAST by the connector, so a caller naming a field explicitly wins over the
    // value the named parameters built for it. It is what makes labels, components, versions and
    // custom fields settable AT creation rather than in a follow-up update - two audit entries and
    // a window in which the issue exists half-configured.
    String createJiraIssue(String projectKey, String issueType, String summary, String description,
                           String priority, String assignee, String parentKey,
                           Map<String, Object> fields) {
        ToolIds.projectKey(projectKey);
        if ("sub-task".equalsIgnoreCase(issueType) || "subtask".equalsIgnoreCase(issueType)) {
            if (parentKey == null || parentKey.isBlank()) {
                throw new IllegalArgumentException(
                        "issueType 'Sub-task' requires parentKey - the key of the parent issue, e.g. MYPROJ-100");
            }
        }
        return on(projectKey, Access.WRITE, (url, token) -> jiraConnector.createIssue(url, token, projectKey,
                issueType, summary, description, priority, assignee, parentKey, fields));
    }

    // Builds Jira's two-part edit payload rather than taking a fixed parameter list, because Jira
    // has two distinct ways to change a field and the caller's intent decides which. `fields`
    // REPLACES a value; `update` applies add/remove operations to a collection. Labels need both
    // available - "set the labels to X" and "add X to the labels" are different requests, and a
    // tool that could only do the first would silently drop labels somebody else had added.
    void updateJiraIssue(String issueKey, String summary, String description, String priority,
                         String assignee, List<String> labels, List<String> addLabels,
                         List<String> removeLabels, List<String> fixVersions,
                         List<String> components, String dueDate, Map<String, Object> customFields) {
        ToolIds.issueKey(issueKey);
        Map<String, Object> fields = new LinkedHashMap<>();
        if (notBlank(summary)) fields.put("summary", summary);
        if (notBlank(description)) fields.put("description", description);
        if (notBlank(priority)) fields.put("priority", Map.of("name", priority));
        if (notBlank(assignee)) fields.put("assignee", Map.of("name", assignee));
        if (notBlank(dueDate)) fields.put("duedate", dueDate);
        // Plain strings, unlike versions and components: Jira models labels as a string array and
        // rejects the {"name": ...} object shape the other two require.
        if (labels != null) fields.put("labels", labels);
        if (fixVersions != null) fields.put("fixVersions", named(fixVersions));
        if (components != null) fields.put("components", named(components));
        // Passed through untouched. A custom field is customfield_NNNNN and its value shape is
        // per-field (string, {"value": ...}, [{"value": ...}]), knowable only from that Jira's own
        // configuration - so this tool carries the value the caller supplied rather than guessing
        // a shape and turning a 400 into a silently wrong write.
        if (customFields != null) fields.putAll(customFields);

        Map<String, Object> update = new LinkedHashMap<>();
        List<Map<String, String>> labelOps = new ArrayList<>();
        if (addLabels != null) addLabels.forEach(l -> labelOps.add(Map.of("add", l)));
        if (removeLabels != null) removeLabels.forEach(l -> labelOps.add(Map.of("remove", l)));
        if (!labelOps.isEmpty()) update.put("labels", labelOps);

        if (fields.isEmpty() && update.isEmpty()) {
            throw new IllegalArgumentException("Nothing to update - pass at least one field");
        }
        run(projectOf(issueKey), Access.WRITE, (url, token) -> jiraConnector.updateIssue(url, token, issueKey, fields, update));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    // Jira's shape for version and component references.
    private static List<Map<String, String>> named(List<String> names) {
        return names.stream().map(n -> Map.of("name", n)).toList();
    }

    // Only GET is a read - the same rule CallJiraApiTool and WriteToolRegistry.isWriteCall apply.
    Map<String, Object> callJiraApi(String method, String path, String body) {
        return on(null, HttpVerb.isRead(method) ? Access.READ : Access.WRITE,
                (url, token) -> jiraConnector.callApi(url, token, method, path, body));
    }

    // Time estimates are their own call, not extra parameters on updateJiraIssue(): Jira models
    // them as sub-fields of the composite `timetracking` field, edited through a different verb
    // than the plain field sets that method performs (see JiraConnector.updateEstimate).
    void updateJiraEstimate(String issueKey, String originalEstimate, String remainingEstimate) {
        ToolIds.issueKey(issueKey);
        run(projectOf(issueKey), Access.WRITE, (url, token) -> jiraConnector.updateEstimate(url, token, issueKey, originalEstimate, remainingEstimate));
    }

    // targetStatus null/blank: returns the list of available transitions without changing state.
    // Otherwise: resolves targetStatus (case-insensitive) to a transition ID and performs it.
    //
    // fields is set AS PART OF the transition, which is the only way to set a Resolution: most
    // workflows keep that field off the edit screen, so updateJiraIssue either cannot write it or
    // writes it and leaves the issue resolved in a status that says it is not.
    //
    // Listing is a READ and resolves the read token, as TransitionJiraIssueTool already treats it
    // (it checks Write only when a targetStatus is given). Resolving the write token for it made
    // "which transitions are there?" fail on a connection whose Write switch is off.
    Object transitionJiraIssue(String issueKey, String targetStatus, Map<String, Object> fields) {
        ToolIds.issueKey(issueKey);
        String project = projectOf(issueKey);
        boolean listOnly = targetStatus == null || targetStatus.isBlank();
        if (listOnly) mcpGuard.checkJiraRead(SearchContext.getUserId());
        return on(project, listOnly ? Access.READ : Access.WRITE,
                (url, token) -> jiraConnector.transitionIssueByStatus(url, token, issueKey, targetStatus, fields));
    }

    // Listing the link types is a read and guards here; creating a link is a write and guards in
    // LinkJiraIssuesTool, where the blocked call still has a tool NAME for the approval prompt.
    List<Map<String, String>> listJiraIssueLinkTypes() {
        mcpGuard.checkJiraRead(SearchContext.getUserId());
        return on(null, Access.READ, (url, token) -> jiraConnector.fetchIssueLinkTypes(url, token));
    }

    // inwardIssueKey is the SUBJECT and takes the type's OUTWARD wording toward outwardIssueKey -
    // for "Blocks", the inward issue is the one doing the blocking. Passed straight through in that
    // order so the direction is decided in one place, by the caller that knows what it meant.
    void linkJiraIssues(String linkType, String inwardIssueKey, String outwardIssueKey, String comment) {
        ToolIds.issueKey(inwardIssueKey);
        ToolIds.issueKey(outwardIssueKey);
        String hint = projectOf(inwardIssueKey);
        run(hint, Access.WRITE, (url, token) -> jiraConnector.createIssueLink(url, token, linkType, inwardIssueKey, outwardIssueKey, comment));
    }

    // A link id names no project, so this resolves the caller's own server - there is no key to
    // pick a per-source override from, and inventing one would mean guessing.
    void deleteJiraIssueLink(String linkId) {
        ToolIds.number("link id", linkId);
        run(null, Access.WRITE, (url, token) -> jiraConnector.deleteIssueLink(url, token, linkId));
    }

    List<Map<String, Object>> listJiraVersions(String projectKey) {
        ToolIds.projectKey(projectKey);
        mcpGuard.checkJiraRead(SearchContext.getUserId());
        return on(projectKey, Access.READ, (url, token) -> jiraConnector.fetchProjectVersions(url, token, projectKey));
    }

    String createJiraVersion(String projectKey, String name, String description, String releaseDate) {
        ToolIds.projectKey(projectKey);
        return on(projectKey, Access.WRITE, (url, token) -> jiraConnector.createVersion(url, token, projectKey, name, description, releaseDate));
    }

    Map<String, Object> attachJiraFile(String issueKey, java.nio.file.Path file) throws java.io.IOException {
        ToolIds.issueKey(issueKey);
        byte[] content = java.nio.file.Files.readAllBytes(file);
        return on(projectOf(issueKey), Access.WRITE, (url, token) -> jiraConnector.addAttachment(url, token, issueKey,
                file.getFileName().toString(), content));
    }

    Map<String, Object> listJiraIssueTypes(String projectKey, String issueType) {
        ToolIds.projectKey(projectKey);
        mcpGuard.checkJiraRead(SearchContext.getUserId());
        return on(projectKey, Access.READ, (url, token) -> jiraConnector.listCreateMeta(url, token, projectKey, issueType));
    }

    List<Map<String, Object>> listJiraFields(String nameFilter) {
        mcpGuard.checkJiraRead(SearchContext.getUserId());
        return on(null, Access.READ, (url, token) -> jiraConnector.listFields(url, token, nameFilter));
    }

    String addJiraComment(String issueKey, String comment) {
        ToolIds.issueKey(issueKey);
        return on(projectOf(issueKey), Access.WRITE, (url, token) -> jiraConnector.addComment(url, token, issueKey, comment));
    }

    void updateJiraComment(String issueKey, String commentId, String comment) {
        ToolIds.issueKey(issueKey);
        ToolIds.number("comment id", commentId);
        run(projectOf(issueKey), Access.WRITE, (url, token) -> jiraConnector.updateComment(url, token, issueKey, commentId, comment));
    }

    void deleteJiraComment(String issueKey, String commentId) {
        ToolIds.issueKey(issueKey);
        ToolIds.number("comment id", commentId);
        run(projectOf(issueKey), Access.WRITE, (url, token) -> jiraConnector.deleteComment(url, token, issueKey, commentId));
    }

    void deleteJiraIssue(String issueKey) {
        ToolIds.issueKey(issueKey);
        run(projectOf(issueKey), Access.WRITE, (url, token) -> jiraConnector.deleteIssue(url, token, issueKey, false));
    }

    String getCurrentJiraUsername() {
        mcpGuard.checkJiraRead(SearchContext.getUserId());
        return on(null, Access.READ, (url, token) -> jiraConnector.fetchCurrentUsername(url, token));
    }

    String logJiraWork(String issueKey, String timeSpent, String comment, String started) {
        ToolIds.issueKey(issueKey);
        return on(projectOf(issueKey), Access.WRITE, (url, token) -> jiraConnector.logWork(url, token, issueKey, timeSpent, comment, started));
    }

    List<Map<String, String>> listJiraProjects() {
        mcpGuard.checkJiraRead(SearchContext.getUserId());
        return on(null, Access.READ, (url, token) -> jiraConnector.listAllProjects(url, token)).stream()
                .map(p -> Map.of("key", p.getKey(), "name", p.getName()))
                .toList();
    }

    // The one call whose endpoint differs by product line: Cloud's search is POST /rest/api/3/search/jql.
    List<Map<String, Object>> searchJiraIssues(String jql, int maxResults) {
        mcpGuard.checkJiraRead(SearchContext.getUserId());
        ToolConnectorResolver.Target target = target(null, Access.READ);
        return jiraConnector.searchIssuesSummary(target.baseUrl(), target.credential(), jql, maxResults,
                target.atlassianCloud());
    }

    Object getJiraIssue(String issueKey) {
        ToolIds.issueKey(issueKey);
        mcpGuard.checkJiraRead(SearchContext.getUserId());
        return on(projectOf(issueKey), Access.READ, (url, token) -> Map.of("url", url + "/browse/" + issueKey,
                "content", jiraConnector.fetchIssueDetailsAsMarkdown(url, token, issueKey)));
    }

    // A Jira SOURCE is a project, and that is what registration stores as the connector's name -
    // so the project key is what decides which connection a call uses (see
    // ConnectorEndpointResolver). A null key is a deliberate "no source in hand": listing projects,
    // searching by JQL and asking who I am are questions about the server itself, and a raw API
    // call names a path, not a project.
    //
    // ONE RESOLUTION PER CALL. The server and the credential were resolved separately, so a
    // useServer in the same batch of parallel tool calls could land between the two and pair one
    // connection's token with another connection's host - a credential sent to a server it was not
    // issued for. A write resolves with Access.WRITE, refused unless the connection allows writing.
    private <T> T on(String projectKey, Access access, BiFunction<String, String, T> call) {
        ToolConnectorResolver.Target target = target(projectKey, access);
        return call.apply(target.baseUrl(), target.credential());
    }

    private void run(String projectKey, Access access, BiConsumer<String, String> call) {
        ToolConnectorResolver.Target target = target(projectKey, access);
        call.accept(target.baseUrl(), target.credential());
    }

    // The connection's Jira. On an Atlassian Cloud site that is the site itself: one Cloud connection
    // serves Jira and Confluence, and it may have been typed with /wiki, /jira or a page below either.
    // On Data Center it is the server's root - an address pasted from an issue or board page is cut
    // before that page, keeping the path the server is installed under.
    private ToolConnectorResolver.Target target(String projectKey, Access access) {
        ToolConnectorResolver.Target target = resolver.target(ConnectorType.JIRA, LABEL, projectKey, access);
        String base = target.atlassianCloud()
                ? AtlassianCloud.site(target.baseUrl()) : AtlassianServer.jiraRoot(target.baseUrl());
        return new ToolConnectorResolver.Target(base, target.credential(), target.authKind());
    }

    /**
     * The project half of an issue key - {@code MYPROJ-100} to {@code MYPROJ}.
     *
     * <p>Split on the LAST hyphen, not the first: a project key may itself contain one
     * ({@code MY-PROJ-100} is a legal issue in project {@code MY-PROJ}), while the issue number
     * never does. A key with no hyphen, or a trailing one, resolves to nothing and the call falls
     * back to the caller's own server - which is the right outcome for a malformed key, since
     * guessing a project out of one would silently pick an override that was never asked for.
     */
    private static String projectOf(String issueKey) {
        if (issueKey == null) return null;
        int hyphen = issueKey.lastIndexOf('-');
        return hyphen <= 0 ? null : issueKey.substring(0, hyphen);
    }
}
