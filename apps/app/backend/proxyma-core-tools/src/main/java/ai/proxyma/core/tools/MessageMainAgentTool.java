package ai.proxyma.core.tools;

import ai.proxyma.core.chat.service.SubAgentService;
import ai.proxyma.core.tools.schema.ToolFieldType;
import ai.proxyma.core.tools.schema.ToolResult;
import ai.proxyma.core.tools.schema.ToolResultField;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

// A background sub-agent's line to the agent that started it (maintainer's 0.36.2 feedback: the two
// should talk back and forth while the sub-agent works, not only be stopped). Registered here for
// its definition alone: the conversation's tool path answers the call itself, with the calling
// sub-agent's id (ChatService -> SubAgentService.messageMain), the way askUser is answered. Offered
// only to a background sub-agent (SubAgentService.toolsFor), never to the main agent, and chat-only.
@Component
public class MessageMainAgentTool {

    @Tool(name = SubAgentService.DelegateToolNames.MESSAGE_MAIN, description = """
            Message the agent that started you, while you work. To ask what only it or the user can \
            settle, set waitForReply true: you pause until it answers, and its answer is this call's \
            `reply`. To tell it something it needs before your report, leave waitForReply false and \
            carry on. Not for progress updates.""")
    @ToolResult({
            @ToolResultField(name = "reply", type = ToolFieldType.STRING, always = false,
                    description = "Its answer, when you waited for one and it came."),
            @ToolResultField(name = "note", type = ToolFieldType.STRING,
                    description = "What happens next, or that no reply came in time.")
    })
    public Map<String, Object> messageMainAgent(
            @ToolParam(required = true, description = "What to tell or ask it, self-contained") String message,
            @ToolParam(required = false, description = "true to pause until it answers") Boolean waitForReply) {
        // Reached only by a caller that is not a background sub-agent in a conversation.
        return Map.of("success", false, "error", "Only a sub-agent can message the agent that started it.");
    }
}
