package ai.proxyma.core.tools;

import ai.proxyma.core.chat.module.ChatModule;
import ai.proxyma.core.system.activity.ActivityCounterStore;
import ai.proxyma.core.tools.bitbucket.AddBitbucketPrCommentTool;
import ai.proxyma.core.tools.bitbucket.CreateBitbucketPrTool;
import ai.proxyma.core.tools.bitbucket.CreateBitbucketTagTool;
import ai.proxyma.core.tools.bitbucket.BitbucketCloudTools;
import ai.proxyma.core.tools.bitbucket.GetBitbucketCommitDiffTool;
import ai.proxyma.core.tools.bitbucket.GetBitbucketCommitsTool;
import ai.proxyma.core.tools.bitbucket.GetBitbucketPrDiffTool;
import ai.proxyma.core.tools.bitbucket.GetBitbucketPrTool;
import ai.proxyma.core.tools.bitbucket.ListBitbucketBranchesTool;
import ai.proxyma.core.tools.bitbucket.ListBitbucketTagsTool;
import ai.proxyma.core.tools.bitbucket.BrowseBitbucketPathTool;
import ai.proxyma.core.tools.bitbucket.DeleteBitbucketRepoTool;
import ai.proxyma.core.tools.bitbucket.DownloadBitbucketRepoTool;
import ai.proxyma.core.tools.bitbucket.CallBitbucketApiTool;
import ai.proxyma.core.tools.bitbucket.ListBitbucketFilesTool;
import ai.proxyma.core.tools.bitbucket.MergeBitbucketPrTool;
import ai.proxyma.core.tools.bitbucket.ReviewBitbucketPrTool;
import ai.proxyma.core.tools.confluence.AddConfluenceCommentTool;
import ai.proxyma.core.tools.confluence.CreateConfluencePageTool;
import ai.proxyma.core.tools.confluence.DeleteConfluencePageTool;
import ai.proxyma.core.tools.confluence.GetConfluencePageTool;
import ai.proxyma.core.tools.confluence.ListConfluenceSpacesTool;
import ai.proxyma.core.tools.confluence.SearchConfluencePagesTool;
import ai.proxyma.core.tools.confluence.CallConfluenceApiTool;
import ai.proxyma.core.tools.confluence.UpdateConfluencePageTool;
import ai.proxyma.core.tools.office.LocalFileTools;
import ai.proxyma.core.tools.office.RunPythonTool;
import ai.proxyma.core.tools.jira.AddJiraCommentTool;
import ai.proxyma.core.tools.jira.CallJiraApiTool;
import ai.proxyma.core.tools.jira.CreateJiraIssueTool;
import ai.proxyma.core.tools.jira.DeleteJiraCommentTool;
import ai.proxyma.core.tools.jira.DeleteJiraIssueTool;
import ai.proxyma.core.tools.jira.CreateJiraVersionTool;
import ai.proxyma.core.tools.jira.DeleteJiraIssueLinkTool;
import ai.proxyma.core.tools.jira.GetJiraCurrentUserTool;
import ai.proxyma.core.tools.jira.LinkJiraIssuesTool;
import ai.proxyma.core.tools.jira.ListJiraFieldsTool;
import ai.proxyma.core.tools.jira.ListJiraIssueTypesTool;
import ai.proxyma.core.tools.jira.AttachJiraFileTool;
import ai.proxyma.core.tools.confluence.UpdateConfluenceCommentTool;
import ai.proxyma.core.tools.confluence.DeleteConfluenceCommentTool;
import ai.proxyma.core.tools.confluence.MoveConfluencePageTool;
import ai.proxyma.core.tools.confluence.UpdateConfluenceLabelsTool;
import ai.proxyma.core.tools.confluence.AttachConfluenceFileTool;
import ai.proxyma.core.tools.bitbucket.CreateBitbucketBranchTool;
import ai.proxyma.core.tools.bitbucket.DeleteBitbucketBranchTool;
import ai.proxyma.core.tools.jira.ListJiraVersionsTool;
import ai.proxyma.core.tools.jira.GetJiraIssueTool;
import ai.proxyma.core.tools.jira.ListJiraProjectsTool;
import ai.proxyma.core.tools.jira.LogJiraWorkTool;
import ai.proxyma.core.tools.jira.SearchJiraIssuesTool;
import ai.proxyma.core.tools.jira.TransitionJiraIssueTool;
import ai.proxyma.core.tools.jira.UpdateJiraCommentTool;
import ai.proxyma.core.tools.jira.UpdateJiraEstimateTool;
import ai.proxyma.core.tools.jira.UpdateJiraIssueTool;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.sources.SourceTools;
import ai.proxyma.core.tools.schema.ToolResultDriftCheck;
import ai.proxyma.core.tools.schema.ToolResultSchemaRegistry;
import ai.proxyma.core.tools.svn.ListSvnFilesTool;
import ai.proxyma.core.tools.svn.ReadSvnFileTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// All tools below are always registered - this @Bean is a singleton built once near app
// startup, so gating registration itself on a settings flag (as this used to do) meant enabling
// a previously-off category never took effect until a restart, while disabling one left it
// listed but silently rejected on every call. Instead every connector-management/write tool
// enforces its own live settings check on each invocation via McpGuardService (org-wide flag +,
// for write tools, the calling user's own consent - see McpGuardService.checkXxxWrite()), so
// enabling/disabling either gate takes effect immediately, no restart required.
@Configuration
public class McpToolConfig {

    /**
     * The registered tool beans, as one value both the callback provider and the result-schema
     * registry are built from.
     *
     * <p>A wrapper record rather than a bare {@code List<Object>} because injecting that by type
     * asks Spring for every bean in the context.
     */
    public record McpToolObjects(List<Object> tools) {}

    @Bean
    public McpToolObjects mcpToolObjects(
            // Enterprise Server - the only edition with Microsoft 365, see below
            @org.springframework.beans.factory.annotation.Value("${proxyma.multi-user:false}") boolean multiUser,
            // Where runPython runs - see SandboxConfig. The local-file tools exist only where it runs
            // on the user's own machine.
            @org.springframework.beans.factory.annotation.Value("${proxyma.sandbox.url:}") String sandboxUrl,
            @org.springframework.beans.factory.annotation.Value("${proxyma.sandbox.local-enabled:true}") boolean sandboxLocal,
            @org.springframework.beans.factory.annotation.Value("${proxyma.home-server:false}") boolean homeServer,
            RunPythonTool runPythonTool,
            UseServerTool useServerTool,
            LocalFileTools localFileTools,
            // Always-on tools
            SearchTool searchTool,
            SearchKnowledgeGraphTool searchKnowledgeGraphTool,
            GetStatusTool getStatusTool,
            GetPlatformInfoTool getPlatformInfoTool,
            ReadTextFileTool readTextFileTool,
            SearchTextFileTool searchTextFileTool,
            ReadSkillTool readSkillTool,
            AskUserTool askUserTool,
            ConvertDocumentTool convertDocumentTool,
            WebFetchTool webFetchTool,
            WebSearchTool webSearchTool,
            RememberTool rememberTool,
            RecallTool recallTool,
            SearchConversationsTool searchConversationsTool,
            DelegateTool delegateTool,
            SteerSubAgentTool steerSubAgentTool,
            MessageMainAgentTool messageMainAgentTool,
            SearchJiraIssuesTool searchJiraIssuesTool,
            GetJiraIssueTool getJiraIssueTool,
            ListJiraProjectsTool listJiraProjectsTool,
            GetJiraCurrentUserTool getJiraCurrentUserTool,
            ListJiraVersionsTool listJiraVersionsTool,
            ListJiraFieldsTool listJiraFieldsTool,
            ListJiraIssueTypesTool listJiraIssueTypesTool,
            GetConfluencePageTool getConfluencePageTool,
            SearchConfluencePagesTool searchConfluencePagesTool,
            ListConfluenceSpacesTool listConfluenceSpacesTool,
            ListBitbucketBranchesTool listBitbucketBranchesTool,
            GetBitbucketCommitsTool getBitbucketCommitsTool,
            GetBitbucketPrTool getBitbucketPrTool,
            ListBitbucketTagsTool listBitbucketTagsTool,
            GetBitbucketPrDiffTool getBitbucketPrDiffTool,
            GetBitbucketCommitDiffTool getBitbucketCommitDiffTool,
            BitbucketCloudTools bitbucketCloudTools,
            // Sources of every type - add, remove, list the types, read an item live - built from the
            // connector catalog (live-guarded, see class comment)
            SourceTools sourceTools,
            ListSvnFilesTool listSvnFilesTool,
            ReadSvnFileTool readSvnFileTool,
            // Jira write tools (live-guarded, see class comment)
            CreateJiraIssueTool createJiraIssueTool,
            UpdateJiraIssueTool updateJiraIssueTool,
            CallJiraApiTool callJiraApiTool,
            UpdateJiraEstimateTool updateJiraEstimateTool,
            TransitionJiraIssueTool transitionJiraIssueTool,
            AddJiraCommentTool addJiraCommentTool,
            UpdateJiraCommentTool updateJiraCommentTool,
            DeleteJiraCommentTool deleteJiraCommentTool,
            DeleteJiraIssueTool deleteJiraIssueTool,
            LogJiraWorkTool logJiraWorkTool,
            LinkJiraIssuesTool linkJiraIssuesTool,
            DeleteJiraIssueLinkTool deleteJiraIssueLinkTool,
            CreateJiraVersionTool createJiraVersionTool,
            // Confluence write tools (live-guarded, see class comment)
            CreateConfluencePageTool createConfluencePageTool,
            UpdateConfluencePageTool updateConfluencePageTool,
            CallConfluenceApiTool callConfluenceApiTool,
            AddConfluenceCommentTool addConfluenceCommentTool,
            DeleteConfluencePageTool deleteConfluencePageTool,
            // Bitbucket write tools (live-guarded, see class comment)
            AddBitbucketPrCommentTool addBitbucketPrCommentTool,
            CreateBitbucketPrTool createBitbucketPrTool,
            ReviewBitbucketPrTool reviewBitbucketPrTool,
            MergeBitbucketPrTool mergeBitbucketPrTool,
            CallBitbucketApiTool callBitbucketApiTool,
            ListBitbucketFilesTool listBitbucketFilesTool,
            BrowseBitbucketPathTool browseBitbucketPathTool,
            DownloadBitbucketRepoTool downloadBitbucketRepoTool,
            DeleteBitbucketRepoTool deleteBitbucketRepoTool,
            CreateBitbucketTagTool createBitbucketTagTool,
            AttachJiraFileTool attachJiraFileTool,
            UpdateConfluenceCommentTool updateConfluenceCommentTool,
            DeleteConfluenceCommentTool deleteConfluenceCommentTool,
            MoveConfluencePageTool moveConfluencePageTool,
            UpdateConfluenceLabelsTool updateConfluenceLabelsTool,
            AttachConfluenceFileTool attachConfluenceFileTool,
            CreateBitbucketBranchTool createBitbucketBranchTool,
            DeleteBitbucketBranchTool deleteBitbucketBranchTool,
            // The product modules built into this deployable (ChatModule) - the key-account tools on
            // Enterprise Server, nothing anywhere else. See ChatModule for why this is not a tenant
            // extension point.
            org.springframework.beans.factory.ObjectProvider<ChatModule> chatModules) {

        List<Object> tools = new ArrayList<>();

        // Always-on tools
        tools.add(searchTool);
        // Registered unconditionally, advertised conditionally. A tool over an empty graph is a
        // guaranteed wasted iteration, and most deployments will never turn extraction on - so
        // ToolSkillGating drops it from the advertised list while the graph has no data, the same
        // live gate `delegate` gets. Registering it here regardless is what lets that take effect
        // on the very next call rather than at the next restart.
        tools.add(searchKnowledgeGraphTool);
        tools.add(getStatusTool);
        tools.add(getPlatformInfoTool);
        tools.add(readTextFileTool);
        tools.add(searchTextFileTool);
        tools.add(readSkillTool);
        // Chat only - left out of the MCP export, sub-agents and workflows (AskUserTool.CHAT_ONLY).
        tools.add(askUserTool);
        tools.add(convertDocumentTool);
        tools.add(webFetchTool);
        tools.add(webSearchTool);
        tools.add(rememberTool);
        tools.add(recallTool);
        tools.add(searchConversationsTool);
        tools.add(delegateTool);
        tools.add(steerSubAgentTool);
        tools.add(messageMainAgentTool);
        tools.add(searchJiraIssuesTool);
        tools.add(getJiraIssueTool);
        tools.add(listJiraProjectsTool);
        tools.add(getJiraCurrentUserTool);
        tools.add(listJiraVersionsTool);
        tools.add(listJiraFieldsTool);
        tools.add(listJiraIssueTypesTool);
        tools.add(getConfluencePageTool);
        tools.add(searchConfluencePagesTool);
        tools.add(listConfluenceSpacesTool);
        tools.add(listBitbucketBranchesTool);
        tools.add(getBitbucketCommitsTool);
        tools.add(getBitbucketPrTool);
        tools.add(listBitbucketTagsTool);
        tools.add(getBitbucketPrDiffTool);
        tools.add(getBitbucketCommitDiffTool);
        // Bitbucket Cloud, read live through its connector's own session (pull requests, files).
        tools.add(bitbucketCloudTools);

        // Sources of every type. One set of tools for every connector, built from the catalog - so a new
        // connector is offered to the agent with no tool of its own, and an ENTERPRISE type
        // (Microsoft 365) is listed only where the edition and the license carry it (listSourceTypes),
        // refused anywhere else (ConnectorService.register).
        tools.add(sourceTools);
        tools.add(listSvnFilesTool);
        tools.add(readSvnFileTool);

        // Jira write tools
        tools.add(createJiraIssueTool);
        tools.add(updateJiraIssueTool);
        tools.add(callJiraApiTool);
        tools.add(updateJiraEstimateTool);
        tools.add(transitionJiraIssueTool);
        tools.add(addJiraCommentTool);
        tools.add(updateJiraCommentTool);
        tools.add(deleteJiraCommentTool);
        tools.add(deleteJiraIssueTool);
        tools.add(logJiraWorkTool);
        tools.add(linkJiraIssuesTool);
        tools.add(deleteJiraIssueLinkTool);
        tools.add(createJiraVersionTool);
        tools.add(attachJiraFileTool);

        // Confluence write tools
        tools.add(createConfluencePageTool);
        tools.add(updateConfluencePageTool);
        tools.add(callConfluenceApiTool);
        tools.add(addConfluenceCommentTool);
        tools.add(deleteConfluencePageTool);
        tools.add(updateConfluenceCommentTool);
        tools.add(deleteConfluenceCommentTool);
        tools.add(moveConfluencePageTool);
        tools.add(updateConfluenceLabelsTool);
        tools.add(attachConfluenceFileTool);

        // Bitbucket write tools
        tools.add(addBitbucketPrCommentTool);
        tools.add(createBitbucketPrTool);
        tools.add(reviewBitbucketPrTool);
        tools.add(mergeBitbucketPrTool);
        tools.add(callBitbucketApiTool);
        tools.add(listBitbucketFilesTool);
        tools.add(browseBitbucketPathTool);
        // A local snapshot is for a coding agent on the SAME machine - Desktop. On a server it was
        // up to 4 GB a call into one directory shared by every user, under a path nobody but the
        // server could read.
        if (!multiUser && !homeServer) {
            tools.add(downloadBitbucketRepoTool);
            tools.add(deleteBitbucketRepoTool);
        }
        tools.add(createBitbucketTagTool);
        tools.add(createBitbucketBranchTool);
        tools.add(deleteBitbucketBranchTool);

        // Office files: model-written Python in a sandbox (see RunPythonTool). Over MCP it refuses
        // without a conversation, which is where its workspace lives.
        tools.add(runPythonTool);
        // Which of the user's servers a Jira/Confluence/Bitbucket tool reaches (see ActiveServers).
        tools.add(useServerTool);
        // Desktop alone, by edition and not only by the sandbox properties: a Home Server image
        // started without its compose environment has no sandbox URL and local-enabled at its
        // default, and offered the local-file tools inside the container.
        if (sandboxUrl.isBlank() && sandboxLocal && !multiUser && !homeServer) tools.add(localFileTools);

        // A module's own tools. Registered like every other tool - over MCP too, where they are inert
        // without a conversation - and OFFERED only where the module says (ToolSkillGating).
        chatModules.orderedStream().forEach(module -> tools.addAll(module.toolObjects()));

        return new McpToolObjects(List.copyOf(tools));
    }

    /**
     * Every registered tool's declared result schema (see {@link ToolResult}).
     *
     * <p>Scanned from {@link #mcpToolObjects} rather than from a list of its own, so "the tools" is
     * one statement in this file and not two that can disagree - the same reason
     * ChatToolCatalogController builds itself from the callback provider instead of enumerating
     * tools again.
     */
    @Bean
    public ToolResultSchemaRegistry toolResultSchemaRegistry(McpToolObjects toolObjects) {
        return ToolResultSchemaRegistry.scan(toolObjects.tools());
    }

    @Bean
    public ToolCallbackProvider toolCallbackProvider(McpToolObjects toolObjects,
                                                    ActivityCounterStore activityCounterStore,
                                                    ToolResultSchemaRegistry schemaRegistry) {
        // Two decorations, both on every callback and both applying to the chat agent's ReAct loop
        // and direct MCP protocol calls alike, because both execute through this one provider:
        //
        //  - ActivityRecordingToolCallback records per-user usage ("TOOL:<name>" + duration)
        //  - ToolResultDriftCheck compares the first calls of each tool against its @ToolResult and
        //    logs any disagreement, which is what keeps a hand-written declaration honest
        //
        // Drift checking is outermost so it sees exactly the string the caller gets back.
        ToolCallback[] wrapped = Arrays.stream(
                        MethodToolCallbackProvider.builder()
                                .toolObjects(toolObjects.tools().toArray())
                                .build()
                                .getToolCallbacks())
                .map(cb -> (ToolCallback) new ActivityRecordingToolCallback(cb, activityCounterStore))
                .map(cb -> (ToolCallback) new ToolResultDriftCheck(cb, schemaRegistry))
                .toArray(ToolCallback[]::new);
        return ToolCallbackProvider.from(wrapped);
    }
}
