package ai.proxyma.core.settings.service;

import ai.proxyma.core.connector.ConnectorType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

// Flat registry of every write-capable MCP tool's name (the @Tool method name, e.g.
// "createJiraIssue"), guarded live by McpGuardService inside proxyma-core-tools. Lives here
// (rather than in proxyma-core-tools, where the tool classes themselves are defined) because
// proxyma-core-chat depends on proxyma-core-settings but not on proxyma-core-tools - ChatService
// needs this set to decide when a tool call requires a live user approval prompt before
// execution, without introducing a backwards module dependency. Split per-connector so
// McpGuardService.checkWriteAllowed() can route a tool name to the right settings check.
public final class WriteToolRegistry {

    // Every tool that calls McpGuardService.checkConnectorManagement() belongs here, and the two
    // web-page ones were missing until 0.2.3 - which cost them BOTH things this registry buys.
    // ChatService gates the approval pause and the in-chat "enable it now" offer on membership of
    // NAMES, so registerWebPage and removeWebPage ran with no approval prompt at all when the
    // permission was on, and hard-failed with no way to grant it when it was off. A tool that
    // deletes a source and its whole index is not the one to leave out.
    //
    // Two names since the connector framework v2: one pair of tools adds and removes a source of any
    // type (SourceTools), so a new connector needs no line here.
    public static final Set<String> CONNECTOR_MANAGEMENT_NAMES = Set.of("addSource", "removeSource");

    public static final Set<String> JIRA_NAMES = Set.of(
            "createJiraIssue", "updateJiraIssue", "updateJiraEstimate", "transitionJiraIssue",
            "addJiraComment", "updateJiraComment", "deleteJiraComment", "deleteJiraIssue",
            // linkJiraIssues reads (it lists link types) when called without a link type and
            // writes when called with one. Listed here for the same reason callJiraApi is: the
            // approval pause is decided per call from the tool NAME, so a tool with a write form
            // takes the pause in both forms rather than slipping through in its read form.
            "linkJiraIssues", "deleteJiraIssueLink", "createJiraVersion",
            // Listed as a WRITE tool even though a GET through it only reads. The registry drives
            // the per-call approval pause and the sub-agent block, and a tool that can reach any
            // endpoint should get both regardless of the verb it happens to be called with.
            "logJiraWork", "attachJiraFile", "callJiraApi");

    public static final Set<String> CONFLUENCE_NAMES = Set.of(
            "createConfluencePage", "updateConfluencePage",
            "addConfluenceComment", "deleteConfluencePage", "callConfluenceApi",
            "updateConfluenceComment", "deleteConfluenceComment", "moveConfluencePage",
            "updateConfluenceLabels", "attachConfluenceFile");

    public static final Set<String> BITBUCKET_NAMES = Set.of(
            "addBitbucketPrComment", "createBitbucketPr", "reviewBitbucketPr",
            "mergeBitbucketPr", "createBitbucketTag", "callBitbucketApi",
            "createBitbucketBranch", "deleteBitbucketBranch");

    // Desktop's two tools that cross between the user's disk and a conversation's workspace. Write
    // tools here for the approval pause alone: each call asks, showing the path - reading a file off
    // the disk included (the maintainer's rule, 2026-09-26). No settings switch gates them, so
    // McpGuardService lets them through to that pause.
    public static final Set<String> LOCAL_FILE_NAMES = Set.of("openLocalFile", "saveLocalFile");

    // The key-account agent's writes (proxyma-core-keyaccount, spec 11 section 8; blueprint 17.5).
    // Declared HERE, unconditionally, rather than by the module: a tool that exists without its
    // declaration runs with no approval, and this registry is static, so the declaration cannot be
    // missing on a build that has the tools. Read-only mode refuses them; Ask asks, and "accept all"
    // is offered and honored - a fact, a rating or a move is only a PROPOSAL a person confirms on the
    // page later (P2), and the report only a draft until the Owner signs it, so a prompt per proposal
    // on a review of sixteen ratings would make the agent useless without making anything safer. The
    // rest - account fields, evidence, stakeholders, opportunities, risks, actions, revenue - are
    // written as made: Ask's approval is the only check on them, and Auto has none. No settings switch
    // gates them (McpGuardService lets them through to the pause); the module checks the caller's
    // membership and role on every call. Names must match the @Tool methods exactly - a typo is silent.
    public static final Set<String> KEY_ACCOUNT_NAMES = Set.of(
            "updateKeyAccountFields", "proposeFact", "proposeRating", "addEvidence",
            "upsertStakeholder", "upsertOpportunity", "upsertRisk", "proposeMove", "addAction",
            "importRevenue", "draftReport");

    // What a custom source holds: saveToSource writes a note into one of the caller's own (SourceTools).
    // A write for the approval pause and Read-only, like the key-account writes above; no settings
    // switch gates it (McpGuardService lets it through to the pause), and the tool itself refuses any
    // source the caller does not own.
    public static final Set<String> SOURCE_CONTENT_NAMES = Set.of("saveToSource");

    // Tools that write only inside the conversation's own workspace. NOT write tools - no approval,
    // the maintainer's choice - but still kept from an agent that runs outside any conversation,
    // which has no workspace.
    public static final Set<String> WORKSPACE_NAMES = Set.of("runPython");

    // Tools that change what the rest of the CONVERSATION does. NOT write tools either, but kept from
    // EVERY sub-agent (ChatService.refusedToSubAgents): a sub-agent runs under its parent's
    // conversation id beside other sub-agents, so a useServer it called would switch the Jira,
    // Confluence or Bitbucket server for all of them, mid-task.
    public static final Set<String> CONVERSATION_STATE_NAMES = Set.of("useServer");

    public static final Set<String> NAMES = buildNames();

    /**
     * Everything an agent with nobody to ask must not be handed: a sub-agent started from outside
     * a conversation (`delegate` over MCP), where no approval can be shown or answered. A
     * sub-agent INSIDE a conversation is offered the write tools since 2026-10-02 - each call
     * through the same approval the main agent's takes.
     */
    public static final Set<String> NOT_FOR_UNATTENDED_AGENTS = notForUnattendedAgents();

    private static Set<String> notForUnattendedAgents() {
        Set<String> all = new HashSet<>(NAMES);
        all.addAll(WORKSPACE_NAMES);
        all.addAll(CONVERSATION_STATE_NAMES);
        return Set.copyOf(all);
    }

    private static Set<String> buildNames() {
        Set<String> all = new HashSet<>();
        all.addAll(CONNECTOR_MANAGEMENT_NAMES);
        all.addAll(JIRA_NAMES);
        all.addAll(CONFLUENCE_NAMES);
        all.addAll(BITBUCKET_NAMES);
        all.addAll(LOCAL_FILE_NAMES);
        all.addAll(KEY_ACCOUNT_NAMES);
        all.addAll(SOURCE_CONTENT_NAMES);
        return Set.copyOf(all);
    }

    /** The generic REST tools: a read or a write depending on the HTTP verb they are called with. */
    public static final Set<String> PASSTHROUGH_NAMES = Set.of("callJiraApi", "callConfluenceApi", "callBitbucketApi");

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Whether THIS CALL writes - decided from its arguments where the tool has a read form.
     *
     * <p>{@link #NAMES} answers "can this tool write", which is right for keeping a tool away from
     * an unattended agent altogether. It is wrong for asking a user to switch write access ON: a GET through
     * callJiraApi, or linkJiraIssues listing the link types, needs only READ - the tools themselves
     * check exactly that - and the chat used to stop such a call with the "enable Jira write tools"
     * prompt, on a first use where read was on and write was off by default. Fixed 2026-09-24.
     *
     * <p>Unparseable arguments count as a write: the stricter answer when this cannot tell.
     */
    public static boolean isWriteCall(String toolName, String argumentsJson) {
        if (isMcpTool(toolName)) return true;
        if (!NAMES.contains(toolName)) return false;
        if (!PASSTHROUGH_NAMES.contains(toolName) && !READ_FORM_NAMES.contains(toolName)) return true;
        try {
            JsonNode args = argumentsJson == null || argumentsJson.isBlank() ? JSON.createObjectNode()
                    : JSON.readTree(argumentsJson);
            return isWriteCall(toolName, JSON.convertValue(args, Map.class));
        } catch (Exception e) {
            return true;
        }
    }

    /** The same, for arguments already parsed. */
    public static boolean isWriteCall(String toolName, Map<?, ?> arguments) {
        if (isMcpTool(toolName)) return true;
        if (!NAMES.contains(toolName)) return false;
        Map<?, ?> args = arguments == null ? Map.of() : arguments;
        if (PASSTHROUGH_NAMES.contains(toolName)) {
            // The one rule the tools and the services also follow: missing or blank is GET, and only
            // GET is a read (HttpVerb).
            Object method = args.get("method");
            return !ai.proxyma.core.common.net.HttpVerb.isRead(method == null ? null : method.toString());
        }
        if ("linkJiraIssues".equals(toolName)) {
            // Without a link type it only lists the types this server has.
            Object linkType = args.get("linkType");
            return linkType != null && !linkType.toString().isBlank();
        }
        if ("transitionJiraIssue".equals(toolName)) {
            // Without a target status it only lists the transitions the issue has - the tool reads
            // it with the READ token, and a Read-only conversation may ask it.
            Object target = args.get("targetStatus");
            return target != null && !target.toString().isBlank();
        }
        return true;
    }

    // Write tools with a READ form, decided from the arguments above.
    private static final Set<String> READ_FORM_NAMES = Set.of("linkJiraIssues", "transitionJiraIssue");

    /** The prefix every tool from a connected MCP server carries - see McpServerToolSource. */
    public static final String MCP_TOOL_PREFIX = "mcp__";

    /**
     * A tool from a connected MCP server. EVERY call to one is treated as a write (maintainer's
     * decision, 2026-10-13): an external server's own readOnlyHint is the server's claim about
     * itself, and nothing here can check it. So each call takes the approval pause unless the
     * conversation is in Auto, and Read-only refuses it.
     */
    public static boolean isMcpTool(String toolName) {
        return toolName != null && toolName.startsWith(MCP_TOOL_PREFIX);
    }

        // What ChatService's tool-enable-request flow needs to name the switch a blocked write turns on.
    // `type` is the service whose CONNECTION carries the Write switch; null for connector management,
    // which is still one per-user setting (`field`, in connector-tools) because it spans every type.
    // `label` is what the consent modal calls it.
    public record Connector(String field, String label, ConnectorType type) {}

    public static Connector connectorFor(String toolName) {
        if (CONNECTOR_MANAGEMENT_NAMES.contains(toolName)) return new Connector("connector-management", "Connector Management", null);
        if (JIRA_NAMES.contains(toolName)) return new Connector(null, "Jira", ConnectorType.JIRA);
        if (CONFLUENCE_NAMES.contains(toolName)) return new Connector(null, "Confluence", ConnectorType.CONFLUENCE);
        if (BITBUCKET_NAMES.contains(toolName)) return new Connector(null, "Bitbucket", ConnectorType.BITBUCKET);
        return null;
    }

    private WriteToolRegistry() {}
}
