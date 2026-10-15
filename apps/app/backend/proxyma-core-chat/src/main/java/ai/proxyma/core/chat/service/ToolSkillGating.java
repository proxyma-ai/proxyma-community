package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.module.ChatModule;
import ai.proxyma.core.graph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// Keeps the LLM-facing tool catalog small: only a handful of general-purpose tools are always
// advertised (see ChatService.coreToolCallbacks); every connector-specific tool is only added to
// a conversation's advertised list once the model has called readSkill for that connector's
// matching skill earlier in the SAME conversation. Re-resolved on every ReAct iteration (see
// ChatService.reactStep) from the conversation's persisted set of unlocked skills
// (Conversation.unlockedSkillNames), with a scan of the history only for a conversation that
// predates that column.
//
// This is a VISIBILITY gate only, never an authorization gate - WriteToolRegistry/McpGuardService
// and the human-approval flow in ChatService.awaitApprovalIfNeeded() are completely unaffected,
// because the model always ends up calling the real tool by its real name once it's advertised
// (no invoke-by-name indirection layer that would need to be unwrapped for those checks).
//
// As of the Skills ownership/sharing feature, a skill is no longer limited to the static
// BASE_GROUP_TOOL_NAMES groups below - any skill (including a custom one a user created) can
// carry its own `tools` list (ChatSkillService.SkillDetail.tools()), which is unlocked the same
// way: read the skill via readSkill, and its tools become advertised. See resolveAdvertised()'s
// union of groupToolNames with ChatSkillService.toolsForSkillName() below - still visibility-only,
// same reasoning as above.
@Component
class ToolSkillGating {

    private static final Logger log = LoggerFactory.getLogger(ToolSkillGating.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String READ_SKILL_TOOL_NAME = "readSkill";
    private static final String SKILL_NAME_ARG = "skillName";

    // Skill name (must exactly match a ChatSkill.name - see DefaultChatSkillSeeder) -> the tool
    // names it unlocks. An admin renaming one of these skills silently orphans its entry here -
    // no runtime detection of that is attempted (matches WriteToolRegistry's own precedent of a
    // flat, code-owned, string-keyed registry).
    private static final Map<String, Set<String>> BASE_GROUP_TOOL_NAMES = Map.ofEntries(
            Map.entry("Jira", Set.of(
                    "searchJiraIssues", "getJiraIssue", "listJiraProjects",
                    "createJiraIssue", "updateJiraIssue", "updateJiraEstimate", "transitionJiraIssue",
                    // logJiraWork was missing from this group entirely, so it was registered as an
                    // MCP tool and a write tool but never advertised to the chat agent - a tool no
                    // readSkill call could ever unlock. Listed with updateJiraEstimate, its
                    // time-tracking sibling.
                    "logJiraWork",
                    "addJiraComment", "updateJiraComment", "deleteJiraComment", "deleteJiraIssue",
                    // Issue links, versions and the field catalog. Without these entries the tools
                    // exist and no readSkill call can ever unlock them, which is the trap the
                    // logJiraWork note above records.
                    "linkJiraIssues", "deleteJiraIssueLink",
                    "listJiraVersions", "createJiraVersion", "listJiraFields", "listJiraIssueTypes", "attachJiraFile",
                    "callJiraApi",
                    // Ungrouped until 2026-09-26, which made it a CORE tool: advertised on every call of
                    // every conversation, Jira or not.
                    "getJiraCurrentUser", "useServer")),
            Map.entry("Confluence", Set.of(
                    "searchConfluencePages", "getConfluencePage", "listConfluenceSpaces",
                    "createConfluencePage", "updateConfluencePage",
                    "addConfluenceComment", "deleteConfluencePage", "callConfluenceApi",
                    "updateConfluenceComment", "deleteConfluenceComment", "moveConfluencePage",
                    "updateConfluenceLabels", "attachConfluenceFile", "useServer")),
            // addBitbucketPrComment is deliberately listed BOTH here and under "Connector Management"
            // below, whose text names it. Groups are unioned, so double-listing is harmless.
            Map.entry("Bitbucket", Set.of(
                    "listBitbucketBranches", "getBitbucketCommits", "getBitbucketPr",
                    "listBitbucketTags", "getBitbucketPrDiff", "getBitbucketCommitDiff",
                    "createBitbucketPr", "reviewBitbucketPr",
                    "mergeBitbucketPr", "createBitbucketTag",
                    "createBitbucketBranch", "deleteBitbucketBranch",
                    "addBitbucketPrComment",
                    "callBitbucketApi", "listBitbucketFiles", "browseBitbucketPath", "useServer",
                    // Bitbucket Cloud's reads (BitbucketCloudTools): the same skill, another API.
                    "getBitbucketCloudPr", "readBitbucketCloudFile")),
            // Sources of every type through four tools built from the connector catalog (SourceTools),
            // since the connector framework v2 - they replaced eighteen register and remove tools, one
            // pair per type, which every new connector would have added to.
            Map.entry("Connector Management", Set.of(
                    "listSourceTypes", "addSource", "removeSource", "readSourceItem", "saveToSource",
                    "addBitbucketPrComment",
                    // useServer switches SVN servers too, and an SVN-only conversation reads only this.
                    "listSvnFiles", "readSvnFile", "useServer")),
            // Reading one item live beside the search that found it.
            Map.entry("Search", Set.of("readSourceItem")),
            Map.entry("Web", Set.of("webFetch", "renderWebFetch", "webSearch")),
            // recall and searchConversations are CORE since 2026-10-03 (maintainer's feedback: the
            // agent searched the sources from scratch for what it had already saved). Checking
            // memory first cannot be the rule while it takes a readSkill call to be able to.
            Map.entry("Memory & Knowledge", Set.of("remember")),
            Map.entry("Documents & Files", Set.of("convertDocument")),
            // Registered or not per edition (McpToolConfig), so a group naming a tool this build
            // lacks simply unlocks nothing for it.
            Map.entry("Office Files", Set.of("runPython", "openLocalFile", "saveLocalFile", "convertDocument")),
            Map.entry("Platform Info & Documentation", Set.of("getPlatformInfo", "getConnectorStatus"))
    );

    // The graph tools - the general walk and its two typed answers - offered and withheld together.
    private static final Set<String> GRAPH_TOOLS = Set.of("searchKnowledgeGraph", "findGraphPath", "findGraphPeople",
            "findGraphTimeline", "findGraphThemes");

    // Tools for MCP clients only, never advertised in chat. downloadBitbucketRepo writes a snapshot to
    // {dataRoot}/repos, which a coding agent on the same machine can open - and which no chat tool can:
    // read and grep are confined to the conversations directory (OffloadFileAccess). So in chat it was
    // a download the agent could only report the size of, sent with every Bitbucket turn. Filtered in
    // resolveAdvertised rather than only dropped from the group, because a Bitbucket skill file seeded
    // before 2026-09-26 still lists both in its own `tools`, and those are unioned in.
    private static final Set<String> MCP_ONLY = Set.of("downloadBitbucketRepo", "deleteBitbucketRepo");

    private final Map<String, Set<String>> groupToolNames;
    private final ChatSkillService chatSkillService;
    private final SubAgentService subAgentService;
    private final KnowledgeGraphService knowledgeGraphService;
    private final LicenseToolScope licenseScope;
    // The product modules built into this deployable (ChatModule) - none on Desktop and Home Server.
    private final List<ChatModule> modules;

    ToolSkillGating(ChatSkillService chatSkillService, SubAgentService subAgentService,
                    KnowledgeGraphService knowledgeGraphService, LicenseToolScope licenseScope) {
        this(chatSkillService, subAgentService, knowledgeGraphService, licenseScope, List.of());
    }

    @org.springframework.beans.factory.annotation.Autowired
    ToolSkillGating(ChatSkillService chatSkillService, SubAgentService subAgentService,
                    KnowledgeGraphService knowledgeGraphService, LicenseToolScope licenseScope,
                    org.springframework.beans.factory.ObjectProvider<ChatModule> modules) {
        this(chatSkillService, subAgentService, knowledgeGraphService, licenseScope,
                modules.orderedStream().toList());
    }

    ToolSkillGating(ChatSkillService chatSkillService, SubAgentService subAgentService,
                    KnowledgeGraphService knowledgeGraphService, LicenseToolScope licenseScope,
                    List<ChatModule> modules) {
        // A module's skill joins the code-owned groups. Unioned rather than replaced, so a module
        // that named a base group's skill could only add to it - never take a tool out of it.
        Map<String, Set<String>> groups = new HashMap<>(BASE_GROUP_TOOL_NAMES);
        for (ChatModule module : modules) {
            module.skillToolGroups().forEach((skill, tools) -> groups.merge(skill, Set.copyOf(tools),
                    (a, b) -> Stream.concat(a.stream(), b.stream()).collect(Collectors.toUnmodifiableSet())));
        }
        this.groupToolNames = Map.copyOf(groups);
        this.chatSkillService = chatSkillService;
        this.subAgentService = subAgentService;
        this.knowledgeGraphService = knowledgeGraphService;
        this.licenseScope = licenseScope;
        this.modules = List.copyOf(modules);
    }

    // THE LICENSE TIER DECIDES WHAT IS OFFERED, since 2026-09-29 - see LicenseToolScope. Applied last,
    // to core and unlocked tools alike, so a tool the plan leaves out is never advertised however it
    // got into the list (a group, a skill file's own `tools`, or none).
    private List<ToolCallback> withinLicense(List<ToolCallback> callbacks) {
        return callbacks.stream().filter(cb -> isLicensed(cb.getToolDefinition().name())).toList();
    }

    // One tool, by the same rule - also what the tool catalog endpoint lists (LicensedTools).
    boolean isLicensed(String name) {
        Set<String> skills = groupToolNames.entrySet().stream()
                .filter(e -> e.getValue().contains(name)).map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
        return licenseScope.isToolIncluded(name, skills);
    }

    // MCP_ONLY counts as gated: a tool in no group is a CORE tool, advertised on every call, which
    // is the opposite of what those two need.
    Set<String> allGatedToolNames() {
        return Stream.concat(groupToolNames.values().stream().flatMap(Set::stream), MCP_ONLY.stream())
                .collect(Collectors.toUnmodifiableSet());
    }

    // The tool names this map associates with a skill name, for DefaultChatSkillSeeder to write
    // into the skill file itself. Exposed so the seeded skills and this map cannot drift: there is
    // one list, here, and the seeder copies it rather than restating it. The map stays the runtime
    // fallback regardless - see resolveAdvertised() - so a deployment seeded before skills carried
    // their own tools keeps working unchanged.
    Set<String> toolNamesForGroup(String skillName) {
        return groupToolNames.getOrDefault(skillName, Set.of());
    }

    // `delegate` is a core tool, so it would otherwise be advertised on every call even where no
    // sub-agent can run (no model to run one on) - the model would spend an iteration calling it
    // just to be told it is unavailable. Filtered here rather than at registration because
    // resolveAdvertised() re-runs every iteration: a model configured for sub-agents takes effect
    // on the very next LLM call, with no restart, which is exactly the property McpToolConfig's
    // "always register, gate live" rule exists to protect.
    // Same reasoning as withDelegateIfAvailable below, for the same reason: knowledge-graph
    // extraction is OFF by default and most deployments will never turn it on, so advertising a
    // traversal tool over an empty graph spends an iteration on every conversation to be told
    // there is nothing there. Gated on DATA, not on the setting - a deployment that enabled
    // extraction five minutes ago has nothing extracted yet, and the honest answer for that
    // window is the same as for a deployment that never enabled it.
    private List<ToolCallback> withGraphIfPopulated(List<ToolCallback> callbacks) {
        if (knowledgeGraphService.hasAnyData()) {
            return callbacks;
        }
        return callbacks.stream()
                .filter(cb -> !GRAPH_TOOLS.contains(cb.getToolDefinition().name()))
                .toList();
    }

    /**
     * The traversal tool, kept only for someone who can READ graph data. withGraphIfPopulated asks
     * whether the graph holds anything at all, which on Enterprise Server is somebody else's
     * graph as often as not - and a user who could read none of it was offered the tool, and spent
     * a call on every conversation to be told there was nothing. Asked of the sources this user
     * can read, so it is answered for each user.
     */
    private List<ToolCallback> withGraphVisibleTo(List<ToolCallback> callbacks, String userId) {
        if (connectorService == null
                || callbacks.stream().noneMatch(cb -> GRAPH_TOOLS.contains(cb.getToolDefinition().name()))) {
            return callbacks;
        }
        boolean visible;
        try {
            List<java.util.UUID> readable = connectorService.listVisibleTo(userId).stream()
                    .filter(c -> !c.isDeleting())
                    .map(ai.proxyma.core.registry.model.RegisteredConnector::getId)
                    .toList();
            visible = knowledgeGraphService.hasDataIn(readable);
        } catch (RuntimeException e) {
            log.debug("[ToolSkillGating] could not ask which graph data this user can read: {}", e.getMessage());
            visible = false;
        }
        if (visible) return callbacks;
        return callbacks.stream()
                .filter(cb -> !GRAPH_TOOLS.contains(cb.getToolDefinition().name()))
                .toList();
    }

    private ai.proxyma.core.registry.service.ConnectorService connectorService;

    // Optional, so the tests that build this class by hand need not build a ConnectorService.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setConnectorService(ai.proxyma.core.registry.service.ConnectorService connectorService) {
        this.connectorService = connectorService;
    }

    private List<ToolCallback> withDelegateIfAvailable(List<ToolCallback> callbacks) {
        if (subAgentService.isAvailable()) {
            return callbacks;
        }
        return callbacks.stream()
                .filter(cb -> !SubAgentService.DelegateToolNames.DELEGATE.equals(cb.getToolDefinition().name()))
                .toList();
    }

    // Returns coreToolCallbacks plus every connector-group's tools whose matching skill has been
    // read (readSkill call, regardless of outcome - see class javadoc) in this conversation.
    //
    // The unlocked set is supplied by the caller rather than scanned out of message history here:
    // history is lossy (condensation folds away the very assistant messages a scan reads, which
    // un-advertised a connector's tools mid-conversation) and it is also unrevocable (a user
    // unloading a skill cannot delete the tool call that unlocked it). ChatService owns that set
    // and persists it - see its unlockedSkillNames()/recordSkillReads().
    List<ToolCallback> resolveAdvertised(List<ToolCallback> allToolCallbacks,
                                          List<ToolCallback> coreToolCallbacks,
                                          Set<String> unlockedGroups) {
        if (unlockedGroups.isEmpty()) {
            return withinLicense(withGraphIfPopulated(withDelegateIfAvailable(coreToolCallbacks)));
        }

        Set<String> unlockedToolNames = new HashSet<>();
        for (String group : unlockedGroups) {
            unlockedToolNames.addAll(groupToolNames.getOrDefault(group, Set.of()));
            unlockedToolNames.addAll(chatSkillService.toolsForSkillName(group).orElse(Set.of()));
        }

        unlockedToolNames.removeAll(MCP_ONLY);
        // A tool already offered as core is never added twice - Spring AI refuses the whole call
        // over a duplicate name. Found 2026-10-03 (0.26.0 feedback): a Memory & Knowledge skill file
        // seeded before recall became core still lists it in its own `tools`, and the seeder only
        // ever backfills an EMPTY list, so reading that skill ended the turn. A custom skill naming
        // a core tool did the same.
        coreToolCallbacks.forEach(cb -> unlockedToolNames.remove(cb.getToolDefinition().name()));
        List<ToolCallback> advertised = new ArrayList<>(coreToolCallbacks);
        for (ToolCallback cb : allToolCallbacks) {
            if (unlockedToolNames.contains(cb.getToolDefinition().name())) {
                advertised.add(cb);
            }
        }
        return withinLicense(withGraphIfPopulated(withDelegateIfAvailable(advertised)));
    }

    /**
     * {@link #resolveAdvertised(List, List, Set)} for one conversation and the user the turn runs
     * for: a product module's own tools
     * stay only while that module offers them THERE (ChatModule.offersToolsIn) - the key-account
     * tools, for instance, only in a conversation bound to an account, on a licensed server. Asked
     * only when one of a module's tools is actually in the list, so an ordinary conversation pays
     * nothing for it. A module that fails to answer loses its own tools for this call, never the turn.
     */
    List<ToolCallback> resolveAdvertised(List<ToolCallback> allToolCallbacks,
                                          List<ToolCallback> coreToolCallbacks,
                                          Set<String> unlockedGroups, String conversationId, String userId) {
        List<ToolCallback> advertised = withGraphVisibleTo(
                resolveAdvertised(allToolCallbacks, coreToolCallbacks, unlockedGroups), userId);
        if (modules.isEmpty()) return advertised;
        Set<String> withheld = new HashSet<>();
        Set<String> names = advertised.stream().map(cb -> cb.getToolDefinition().name())
                .collect(Collectors.toSet());
        for (ChatModule module : modules) {
            Set<String> own = module.ownToolNames();
            if (own.stream().noneMatch(names::contains)) continue;
            boolean offered;
            try {
                offered = module.offersToolsIn(conversationId, userId);
            } catch (RuntimeException e) {
                log.warn("[ToolSkillGating] a chat module could not say whether its tools are offered: {}", e.getMessage());
                offered = false;
            }
            if (!offered) withheld.addAll(own);
        }
        if (withheld.isEmpty()) return advertised;
        return advertised.stream().filter(cb -> !withheld.contains(cb.getToolDefinition().name())).toList();
    }

    // Records every skill name seen via a readSkill call in history, regardless of whether it's
    // one of the static BASE_GROUP_TOOL_NAMES groups - a custom skill with its own `tools` list
    // (see ChatSkillService) won't be a key in that map, so filtering on containsKey here would
    // silently drop it. resolveAdvertised() above is what actually resolves a name to tool names,
    // from either source (or both).
    //
    // No longer the live source of truth for what is unlocked (see resolveAdvertised) - this is now
    // only the one-time backfill for a conversation that predates Conversation.unlockedSkillNames,
    // so those conversations don't lose their already-unlocked tools on upgrade.
    Set<String> scanUnlockedGroups(List<Message> history) {
        Set<String> unlocked = new HashSet<>();
        for (Message message : history) {
            if (!(message instanceof AssistantMessage assistantMessage) || !assistantMessage.hasToolCalls()) {
                continue;
            }
            for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                if (!READ_SKILL_TOOL_NAME.equals(toolCall.name())) {
                    continue;
                }
                String skillName = extractSkillName(toolCall.arguments());
                if (skillName != null) {
                    unlocked.add(skillName);
                }
            }
        }
        return unlocked;
    }

    private static String extractSkillName(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(argumentsJson).path(SKILL_NAME_ARG);
            return node.isTextual() ? node.asText() : null;
        } catch (Exception e) {
            log.debug("[ToolSkillGating] failed to parse readSkill arguments: {}", e.getMessage());
            return null;
        }
    }
}
