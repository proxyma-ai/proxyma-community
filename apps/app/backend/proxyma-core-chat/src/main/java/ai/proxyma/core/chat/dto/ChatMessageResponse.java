package ai.proxyma.core.chat.dto;

import ai.proxyma.core.chat.domain.MessageRole;

import java.util.List;

public record ChatMessageResponse(
        // Null on a row that is tool calls and nothing else (see
        // ChatService.getMessagesWithToolCalls): it stands for no single stored message.
        Long id,
        MessageRole role,
        String content,
        List<ToolCallResponse> toolCalls,
        // Display-safe file names only (never the server-side absolute path baked into the
        // persisted USER row's content for the LLM's own benefit) - see
        // ChatService.sanitizeUserContent(). Null/omitted for non-USER rows.
        List<String> attachmentNames,
        // Document/skill names the user explicitly picked via the chat input's @/skill mentions
        // (see ChatService.sanitizeUserContent()) - the frontend renders these (and the matching
        // "@Name"/"/Name" tokens still present in `content` itself) as badges instead of plain
        // text. Null/omitted for non-USER rows.
        List<String> mentions,
        List<String> skillMentions,
        // Set on the USER row that invoked a workflow (see WorkflowRun.triggerMessageId): the id of
        // the run whose step table is drawn directly under this message. Only the id - the run
        // itself is fetched from GET .../workflow-runs, which returns the same object the live SSE
        // event carries, so a reloaded table and a watched one are built by one piece of client
        // code rather than two that have to agree. Null on every other row.
        String workflowRunId,
        // The step limit this row's turn stopped at, on the row the notice is drawn under - what
        // the 'step-limit' event says live. Null everywhere else.
        Integer stepLimit,
        // Set on the row that stands for a message putting background sub-agents' reports in front
        // of the main agent: which ones reported at this point of the thread - what the
        // 'sub-agent-reported' event says live. The row has no content; the report itself is in
        // the sub-agent's own panel. Null everywhere else.
        List<SubAgentReported> subAgentReports) {

    public ChatMessageResponse(Long id, MessageRole role, String content, List<ToolCallResponse> toolCalls,
                               List<String> attachmentNames, List<String> mentions, List<String> skillMentions,
                               String workflowRunId, Integer stepLimit) {
        this(id, role, content, toolCalls, attachmentNames, mentions, skillMentions, workflowRunId, stepLimit, null);
    }

    /**
     * One sub-agent whose report reached the main agent; status is lower case, as the events have it.
     * kind is "message" for a message a running sub-agent sent (messageMainAgent), else null.
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SubAgentReported(String agentId, String title, String status, String kind) {}

    // Field names match the frontend's ToolCallInfo shape exactly (see chat.types.ts) so no
    // frontend type change is needed to consume this. startedAt and endedAt are epoch
    // milliseconds, null for a call stored before 2026-10-01 or one that never got a result.
    // `agent` is set on a `delegate` call: the sub-agent it started, as the thread shows it.
    public record ToolCallResponse(String callId, String toolName, String arguments, String result, boolean done,
                                   Long startedAt, Long endedAt, SubAgentView agent) {

        public ToolCallResponse(String callId, String toolName, String arguments, String result, boolean done,
                                Long startedAt, Long endedAt) {
            this(callId, toolName, arguments, result, done, startedAt, endedAt, null);
        }
    }
}
