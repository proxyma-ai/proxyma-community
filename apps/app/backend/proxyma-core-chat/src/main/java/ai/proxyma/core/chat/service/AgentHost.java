package ai.proxyma.core.chat.service;

import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What a sub-agent needs from the conversation it runs in - and the whole of it.
 *
 * <p>ChatService is the host. An interface rather than the class itself for two reasons: Spring
 * builds ChatService with SubAgentService in its constructor, so the reverse edge has to be
 * resolved lazily (SubAgentService takes an ObjectProvider of this); and a sub-agent's loop can
 * then be tested against a host that runs no tool and starts no turn.
 */
public interface AgentHost {

    /**
     * Runs one round of a sub-agent's tool calls EXACTLY as the main agent's are run - in
     * parallel, each bounded by the tool timeout, each write through the permission mode, the
     * user's approval and the connection's own Write switch, each result offloaded if large and
     * screened by the retrieval guardrail. One path, so "a sub-agent respects the same
     * permissions" is true by construction rather than by a second copy kept in step.
     *
     * <p>Must be called on a thread that carries the caller's SearchContext (user id) and
     * SpendContext - the calls inherit both.
     */
    ToolResponseMessage callTools(String conversationId, List<ToolCall> calls, AtomicBoolean stop, AgentScope scope);

    /**
     * A background sub-agent has finished and its report is waiting. The host hands it to the
     * conversation's running turn at its next step, or starts a turn for it when none is running.
     */
    void subAgentSettled(String conversationId);

    /** The sub-agent a round of tool calls belongs to. */
    interface AgentScope {

        /** Stamped on every event the round publishes, as its {@code parentCallId}. */
        String agentId();

        /** What a call cut short is told, in place of "Aborted by user" - the agent knows why it stopped. */
        String stopNote();

        /** A call's TOOL_START is about to be published as event {@code seq}. */
        void toolStarted(String callId, String toolName, String arguments, long seq, long at);

        /** A call's TOOL_RESULT is about to be published as event {@code seq}. */
        void toolFinished(String callId, String result, long seq, long at);

        /**
         * One of its calls has begun ({@code true}) or stopped ({@code false}) waiting for the
         * user - an approval, a consent prompt, a question. That time is the user's, not the
         * agent's, and does not count against its timeout.
         */
        void waitingForUser(boolean waiting);
    }
}
