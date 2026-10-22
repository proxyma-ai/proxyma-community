package ai.proxyma.core.tools;

import ai.proxyma.core.chat.service.SubAgentService;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.tools.schema.ToolFieldType;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.schema.ToolResultField;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

// The main agent's side of talking with a background sub-agent it started: an answer to the
// sub-agent's question (messageMainAgent), a message - more information, a change of course - or
// a stop. Until 2026-10-17 only the user's Stop button reached
// a running sub-agent, and nothing reached it with words (maintainer's 0.36.2 feedback). Only this
// conversation's sub-agents, by the agentId delegate returned; a sub-agent is refused it
// (ChatService.refusedToSubAgents), and it is chat-only (ChatOnlyTools), since nothing else has
// sub-agents to direct.
@Component
public class SteerSubAgentTool {

    private final SubAgentService subAgentService;

    public SteerSubAgentTool(SubAgentService subAgentService) {
        this.subAgentService = subAgentService;
    }

    @Tool(name = SubAgentService.DelegateToolNames.STEER, description = """
            Talk to a background sub-agent you started, by the agentId delegate returned. \
            message: the answer to its question ("[Sub-agent message]" ... QUESTION - it is paused \
            until you answer), or more information or a change of course - read before its next \
            step. stop=true: ends it now; its report arrives as STOPPED. Do not ask how it is doing - \
            its report comes by itself.""")
    @ToolResult({
            @ToolResultField(name = "status", type = ToolFieldType.STRING, always = false,
                    description = "ANSWERED when it was waiting on a question and has this now, QUEUED when "
                            + "it reads it before its next step, STOPPING when it was told to stop."),
            @ToolResultField(name = "note", type = ToolFieldType.STRING,
                    description = "What happens next.")
    })
    public Map<String, Object> steerSubAgent(
            @ToolParam(required = true, description = "The sub-agent's agentId, from delegate") String agentId,
            @ToolParam(required = false, description = "What to tell it, written as an instruction to it") String message,
            @ToolParam(required = false, description = "true to stop it now") Boolean stop) {
        String conversationId = SearchContext.getConversationId();
        if (conversationId == null) {
            return refused("There are no sub-agents to direct outside a conversation.");
        }
        if (agentId == null || agentId.isBlank()) {
            return refused("agentId is required - delegate returned it.");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (Boolean.TRUE.equals(stop)) {
            if (!subAgentService.stopByAgent(conversationId, agentId)) return notRunning(agentId);
            out.put("success", true);
            out.put("status", "STOPPING");
            out.put("note", "It stops now. Its report, STOPPED, will reach you with what it had done.");
            return out;
        }
        if (message == null || message.isBlank()) {
            return refused("Give it a message, or stop=true.");
        }
        return switch (subAgentService.tell(conversationId, agentId, message)) {
            case ANSWERED -> {
                out.put("success", true);
                out.put("status", "ANSWERED");
                out.put("note", "It was waiting on its question and goes on with your answer now.");
                yield out;
            }
            case QUEUED -> {
                out.put("success", true);
                out.put("status", "QUEUED");
                out.put("note", "It reads this before its next step and goes on working. Its report "
                        + "arrives by itself - do not wait for it or poll.");
                yield out;
            }
            case FINISHING -> refused("That sub-agent has finished or is stopping, so it will not read this. "
                    + "Its report is on its way; start another sub-agent if the work needs it.");
            case NOT_RUNNING -> notRunning(agentId);
        };
    }

    private static Map<String, Object> notRunning(String agentId) {
        return refused("No sub-agent " + agentId.strip() + " is running in this conversation - it may "
                + "already have reported.");
    }

    private static Map<String, Object> refused(String why) {
        return Map.of("success", false, "error", why);
    }
}
