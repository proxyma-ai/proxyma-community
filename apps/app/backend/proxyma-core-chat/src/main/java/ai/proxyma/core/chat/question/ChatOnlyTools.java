package ai.proxyma.core.chat.question;

import java.util.Set;

/**
 * Tools only a chat with a person in it can offer - left out of the MCP export and workflow nodes,
 * which have nobody to ask. Here, in the chat module, because both sides need the name: the tools
 * module (which depends on this one) declares the tool, and ChatService answers it.
 *
 * <p>The agent tree's tools too (AgentTools): an agent, an alarm and a background job each belong
 * to a conversation, which none of those callers has.
 */
public final class ChatOnlyTools {

    /** The assistant asks the user multiple-choice questions - see ChatService.awaitUserAnswers. */
    public static final String ASK_USER = "askUser";

    public static final Set<String> ALL = Set.of(ASK_USER,
            // The agent tree, alarms and background work (AgentTools): each belongs to a conversation.
            "deployAgent", "tellAgent", "awaitAgents", "controlAgent", "alarm", "background",
            "deployAgents", "readAgentReport", "verifyReport", "deliberate", "recordDecision", "handFile",
            // A shell command runs in a conversation's workspace (RunCommandTool).
            "runCommand");

    private ChatOnlyTools() {}
}
