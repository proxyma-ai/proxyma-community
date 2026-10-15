package ai.proxyma.core.tools;

import ai.proxyma.core.common.audit.AuditEntry;
import ai.proxyma.core.common.audit.ToolAudit;
import ai.proxyma.core.common.diagnostics.DiagOp;
import ai.proxyma.core.common.diagnostics.Diagnostics;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.system.activity.ActivityCounterStore;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

// Decorates every registered tool callback to record per-user tool usage
// ("TOOL:<name>" + execution duration) into the shared ActivityCounterStore. One chokepoint
// covers both origins - the chat agent's ReAct loop and direct MCP protocol calls - because
// both execute tools through the same ToolCallbackProvider, and both set
// SearchContext.userId on the executing thread (ChatService.callTools / JwtAuthFilter's MCP
// token path). Anonymous executions (no userId) are ignored by recordFeature.
class ActivityRecordingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ActivityCounterStore counterStore;

    ActivityRecordingToolCallback(ToolCallback delegate, ActivityCounterStore counterStore) {
        this.delegate = delegate;
        this.counterStore = counterStore;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        long start = System.nanoTime();
        boolean ok = false;
        String result = null;
        try {
            result = delegate.call(toolInput);
            ok = true;
            return result;
        } finally {
            recorded(delegate.getToolDefinition().name(), counterStore, toolInput, result, ok, start);
        }
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        long start = System.nanoTime();
        boolean ok = false;
        String result = null;
        try {
            result = delegate.call(toolInput, toolContext);
            ok = true;
            return result;
        } finally {
            recorded(delegate.getToolDefinition().name(), counterStore, toolInput, result, ok, start);
        }
    }

    /**
     * Everything one finished call leaves behind: its timing in the daily diagnostics, its usage
     * count, and - where nobody above recorded it - its Audit Logs row. Static, so a handler that
     * runs a tool WITHOUT this wrapper (the image-aware webFetch over MCP) records the same three
     * things; it copied the usage count alone, so an MCP client's webFetch was never in the audit
     * log or the diagnostics - for the one tool that reaches arbitrary addresses.
     */
    static void recorded(String toolName, ActivityCounterStore counterStore, String input, String result,
                         boolean ok, long startNanos) {
        // Into the daily diagnostics too: which tools are used, how long they take and how often
        // they throw. The tool NAME is a fixed, code-defined vocabulary (these are the callbacks
        // Proxyma registers itself); the input and the result never leave this method.
        try {
            Diagnostics.timeSince(DiagOp.TOOL_CALL, startNanos, "tool", toolName, "outcome", ok ? "ok" : "failed");
        } catch (Exception ignored) {
            // As below.
        }
        try {
            counterStore.recordFeature(SearchContext.getUserId(), "TOOL:" + toolName,
                    (System.nanoTime() - startNanos) / 1_000_000);
        } catch (Exception ignored) {
            // Usage accounting must never fail a tool call.
        }
        // The Audit Logs page's record of a call nobody above this recorded - an MCP client's, a
        // workflow node's. The chat's tool path and a sub-agent's record their own, after the
        // retrieval guardrail, and say so (ToolAudit.recordedByCaller).
        if (ToolAudit.isRecordedByCaller()) return;
        try {
            String conversationId = SearchContext.getConversationId();
            ToolAudit.record(SearchContext.getUserId(), conversationId, toolName,
                    conversationId == null ? "MCP client" : "Workflow", null, input, result,
                    ok ? null : AuditEntry.FAILED, null, (System.nanoTime() - startNanos) / 1_000_000);
        } catch (Exception ignored) {
            // Auditing must never fail a tool call.
        }
    }
}
