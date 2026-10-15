package ai.proxyma.core.chat.service;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps every tool call answered, in the history a model is sent.
 *
 * <p>Every provider requires it and none tolerates a gap: an assistant message that calls tools
 * must be followed IMMEDIATELY by the results of exactly those calls. Anthropic refuses the request
 * with a 400 ("tool_use ids were found without tool_result blocks immediately after"), and so does
 * every OpenAI-compatible API ("must be followed by tool messages responding to each
 * 'tool_call_id'"). A tool RESULT with no call before it is refused just the same.
 *
 * <p>And a gap, once in memory, is permanent: every later request replays it, so one stopped turn
 * broke every message the user sent in that conversation afterwards. That is how it presented -
 * press Stop while tools are running, send anything, get the 400. ChatService no longer WRITES
 * such a gap (see react()), but a conversation that already holds one, or a turn cut off by a
 * crash between the two writes, still has to be sendable - so the history is repaired on its way
 * to the model, every time, and the stored rows are left exactly as they are.
 *
 * <p>A pure function of the list, so it is tested directly (ToolPairingTest).
 */
public final class ToolPairing {

    /** What a tool call that never returned is answered with - the same envelope a failed tool uses. */
    static final String NOT_RUN = "{\"success\":false,\"error\":\"Not run: the turn ended before this tool returned a result.\"}";

    private ToolPairing() {}

    /** The repaired history, and how many calls or results had to be added or dropped to get it. */
    public record Repaired(List<Message> messages, int repairs) {}

    /**
     * Returns {@code history} with every tool call followed at once by one result per call, in
     * call order:
     * <ul>
     *   <li>a call with no result is answered with {@link #NOT_RUN};</li>
     *   <li>results split over several consecutive messages are merged into one;</li>
     *   <li>a result for an id the calls did not ask for is dropped, as is a result message that no
     *       tool-calling assistant message precedes.</li>
     * </ul>
     * Everything else is returned untouched and in order, including the message objects
     * themselves wherever nothing about them had to change.
     */
    public static Repaired repair(List<Message> history) {
        List<Message> out = new ArrayList<>(history.size() + 2);
        int repairs = 0;
        int i = 0;
        while (i < history.size()) {
            Message message = history.get(i++);
            if (message instanceof ToolResponseMessage) {
                // Reached only when no tool-calling message came right before it: the pairing
                // branch below consumes every result that does belong to a call.
                repairs++;
                continue;
            }
            out.add(message);
            if (!(message instanceof AssistantMessage assistant) || !assistant.hasToolCalls()) continue;

            List<ToolResponseMessage> following = new ArrayList<>();
            while (i < history.size() && history.get(i) instanceof ToolResponseMessage results) {
                following.add(results);
                i++;
            }
            Map<String, ToolResponse> byId = new LinkedHashMap<>();
            for (ToolResponseMessage results : following) {
                for (ToolResponse response : results.getResponses()) byId.putIfAbsent(response.id(), response);
            }

            List<ToolResponse> answered = new ArrayList<>();
            for (ToolCall call : assistant.getToolCalls()) {
                ToolResponse response = byId.remove(call.id());
                if (response == null) {
                    response = new ToolResponse(call.id(), call.name(), NOT_RUN);
                    repairs++;
                }
                answered.add(response);
            }
            repairs += byId.size();

            // The stored message is kept as it is when it was already exactly right.
            if (following.size() == 1 && following.get(0).getResponses().equals(answered)) {
                out.add(following.get(0));
            } else {
                if (following.size() > 1) repairs++;
                out.add(ToolResponseMessage.builder().responses(answered).build());
            }
        }
        return new Repaired(out, repairs);
    }

    /**
     * The results for a tool-calling message whose tools will never run - every call answered
     * with {@code reason} as a failed-tool envelope.
     */
    public static ToolResponseMessage notRun(AssistantMessage assistant, String reason) {
        String payload = "{\"success\":false,\"error\":\"" + reason.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        List<ToolResponse> responses = new ArrayList<>();
        for (ToolCall call : assistant.getToolCalls()) {
            responses.add(new ToolResponse(call.id(), call.name(), payload));
        }
        return ToolResponseMessage.builder().responses(responses).build();
    }
}
