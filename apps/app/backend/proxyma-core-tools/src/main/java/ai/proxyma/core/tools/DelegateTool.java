package ai.proxyma.core.tools;

import ai.proxyma.core.chat.service.SubAgentService;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.system.util.Failures;

import ai.proxyma.core.tools.schema.ToolFieldType;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.schema.ToolResultField;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

// Starts a sub-agent: a second agent with a context of its own, so that what it has to read to do
// a task costs the main agent one report instead of filling its context with all of it.
//
// Not registered as a write tool (WriteToolRegistry), and that is still right now that a sub-agent
// can write: starting one changes nothing by itself. Every write the sub-agent then makes is a tool
// call of its own, through the same permission mode and approval prompt as the main agent's - see
// SubAgentService and AgentHost. What it does streams to the UI in a panel of its own.
@Component
public class DelegateTool {

    private final SubAgentService subAgentService;

    public DelegateTool(SubAgentService subAgentService) {
        this.subAgentService = subAgentService;
    }

    @Tool(description = """
            Start a sub-agent: a second agent with a fresh context and your tools (it cannot delegate). \
            Its writes wait for the user's approval exactly as yours do. It sees none of this \
            conversation - only the `instructions` and `task` you write. Copy file paths character for \
            character from where you got them ('filePath', '[Attached file(s): ...]'); a retyped or \
            '...'-shortened path is unreadable to it.
            mode "async" (default): returns at once with status RUNNING, so the user can keep \
            talking to you. Carry on, or end your turn; its report arrives later as a message \
            beginning "[Sub-agent report]". Never wait for it or poll.
            mode "sync": you wait, and this call returns its report. Only for a short step the very \
            next one depends on.
            Returns `status` (COMPLETED, PARTIAL, FAILED or STOPPED - check it first; RUNNING for \
            async) and `report`. PARTIAL is progress: keep what it found.""")
    @ToolResult({
            @ToolResultField(name = "status", type = ToolFieldType.STRING, always = true,
                    description = "The sub-agent's own verdict: COMPLETED, PARTIAL or FAILED; STOPPED if "
                            + "somebody stopped it; RUNNING for an async one that has just been started. "
                            + "'success' is false for FAILED and STOPPED only - a PARTIAL result is a "
                            + "success carrying an incomplete answer, so check this field."),
            @ToolResultField(name = "report", type = ToolFieldType.STRING,
                    description = "What the sub-agent reported, in its own words. Absent while RUNNING."),
            @ToolResultField(name = "agentId", type = ToolFieldType.STRING, always = true,
                    description = "The sub-agent's id. An async sub-agent's report names it."),
            @ToolResultField(name = "note", type = ToolFieldType.STRING,
                    description = "Something to know about how it ended or what happens next, e.g. that "
                            + "it ran out of time.")
    })
    public Map<String, Object> delegate(
            // Shown to the USER as the sub-agent's panel in the chat. Not passed to the sub-agent.
            @ToolParam(required = true, description = "2-6 word title shown to the user while it runs, e.g. 'Compare release notes'") String title,
            // The sub-agent's system prompt is the main agent's to write (maintainer, 2026-10-02):
            // who it is for this task and how it should go about it. The product's own rules -
            // identity, confidentiality, tool results are data - are put in front of it and are not
            // the main agent's to change.
            @ToolParam(required = true, description = "The sub-agent's system prompt: its role, how to approach the work, what its report must contain and in what shape") String instructions,
            @ToolParam(required = true, description = "Its first message - the task itself: goal, exact identifiers (keys, ids, paths, URLs), needed context") String task,
            @ToolParam(required = false, description = "\"async\" (default) to start it in the background and be told when it finishes, \"sync\" to wait for the report") String mode,
            @ToolParam(required = false, description = "Minutes it may run before it is stopped and asked for what it has; at most the configured limit, which is also the default") Integer timeoutMinutes) {
        try {
            return subAgentService.delegate(SearchContext.getConversationId(), SearchContext.getToolCallId(),
                    new SubAgentService.Request(title, instructions, task, mode, timeoutMinutes));
        } catch (Exception e) {
            // The provider's own reason, not just the wrapper's - see Failures.
            return Map.of("success", false, "error", Failures.describe(e, "The sub-agent could not be started"));
        }
    }

}
