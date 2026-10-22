package ai.proxyma.core.chat.question;

import java.util.Set;

/**
 * Tools only a chat with a person in it can offer - left out of the MCP export, workflow nodes and
 * a sub-agent started outside a conversation, which have nobody to ask. (A sub-agent inside a
 * conversation is offered them: its questions go to that conversation's person - see
 * SubAgentService.toolsFor.) Here, in the chat module, because both sides need the name: the tools
 * module (which depends on this one) declares the tool, and ChatService answers it.
 *
 * <p>{@code steerSubAgent} and {@code messageMainAgent} too: the main agent and its background
 * sub-agents talking to each other, and none of those callers has either. (No sub-agent is offered
 * the first - ChatService.refusedToSubAgents - and only a background one the second.)
 */
public final class ChatOnlyTools {

    /** The assistant asks the user multiple-choice questions - see ChatService.awaitUserAnswers. */
    public static final String ASK_USER = "askUser";

    public static final Set<String> ALL = Set.of(ASK_USER,
            ai.proxyma.core.chat.service.SubAgentService.DelegateToolNames.STEER,
            ai.proxyma.core.chat.service.SubAgentService.DelegateToolNames.MESSAGE_MAIN);

    private ChatOnlyTools() {}
}
