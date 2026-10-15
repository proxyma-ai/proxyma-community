package ai.proxyma.core.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The history a model is sent always has every tool call answered, immediately and in call order.
 *
 * <p>The failure this pins: press Stop while tools run, send anything, and the provider refuses
 * the request with a 400 - "tool_use ids were found without tool_result blocks immediately after"
 * - on that turn and every turn after it, because the stopped turn left a call with no result in
 * the conversation's memory.
 */
class ToolPairingTest {

    private static final SystemMessage SYSTEM = new SystemMessage("system");

    @Test
    @DisplayName("an intact history comes back unchanged - the same message objects, nothing repaired")
    void intactHistoryIsUntouched() {
        AssistantMessage calls = calling(call("a", "search"), call("b", "read"));
        ToolResponseMessage results = results(result("a", "search"), result("b", "read"));
        List<Message> history = List.of(SYSTEM, new UserMessage("q"), calls, results, new AssistantMessage("answer"));

        ToolPairing.Repaired repaired = ToolPairing.repair(history);

        assertEquals(0, repaired.repairs());
        assertEquals(history.size(), repaired.messages().size());
        for (int i = 0; i < history.size(); i++) assertSame(history.get(i), repaired.messages().get(i));
    }

    @Test
    @DisplayName("the reported case: calls stopped before their results, then a new message - answered in between")
    void aStoppedTurnIsAnsweredBeforeTheNextMessage() {
        AssistantMessage calls = calling(call("a", "search"), call("b", "read"));
        UserMessage next = new UserMessage("and now?");

        List<Message> out = ToolPairing.repair(List.of(SYSTEM, new UserMessage("q"), calls, next)).messages();

        assertEquals(5, out.size());
        assertSame(calls, out.get(2));
        ToolResponseMessage answered = assertInstanceOf(ToolResponseMessage.class, out.get(3));
        assertEquals(List.of("a", "b"), answered.getResponses().stream().map(ToolResponse::id).toList());
        assertEquals(List.of("search", "read"), answered.getResponses().stream().map(ToolResponse::name).toList());
        assertTrue(answered.getResponses().stream().allMatch(r -> r.responseData().equals(ToolPairing.NOT_RUN)));
        assertSame(next, out.get(4), "the new message follows the results, where the provider requires them");
    }

    @Test
    @DisplayName("calls at the very end of the history are answered too")
    void trailingCallsAreAnswered() {
        AssistantMessage calls = calling(call("a", "search"));
        List<Message> out = ToolPairing.repair(List.of(new UserMessage("q"), calls)).messages();
        assertEquals(3, out.size());
        assertInstanceOf(ToolResponseMessage.class, out.get(2));
    }

    @Test
    @DisplayName("some results present: kept, the missing ones added, all in the order of the calls")
    void partialResultsAreCompletedInCallOrder() {
        AssistantMessage calls = calling(call("a", "search"), call("b", "read"), call("c", "grep"));
        ToolResponseMessage partial = results(result("c", "grep"), result("a", "search"));

        ToolPairing.Repaired repaired = ToolPairing.repair(List.of(new UserMessage("q"), calls, partial));

        ToolResponseMessage answered = (ToolResponseMessage) repaired.messages().get(2);
        assertEquals(List.of("a", "b", "c"), answered.getResponses().stream().map(ToolResponse::id).toList());
        assertEquals("result of search", answered.getResponses().get(0).responseData());
        assertEquals(ToolPairing.NOT_RUN, answered.getResponses().get(1).responseData());
        assertEquals("result of grep", answered.getResponses().get(2).responseData());
        assertEquals(1, repaired.repairs());
    }

    @Test
    @DisplayName("results split over consecutive messages are merged into the one message the provider expects")
    void splitResultsAreMerged() {
        AssistantMessage calls = calling(call("a", "search"), call("b", "read"));
        List<Message> out = ToolPairing.repair(List.of(new UserMessage("q"), calls,
                results(result("a", "search")), results(result("b", "read")))).messages();

        assertEquals(3, out.size());
        ToolResponseMessage merged = (ToolResponseMessage) out.get(2);
        assertEquals(List.of("a", "b"), merged.getResponses().stream().map(ToolResponse::id).toList());
    }

    @Test
    @DisplayName("a result nobody called for is dropped, and so is a result message with no call before it")
    void strayResultsAreDropped() {
        AssistantMessage calls = calling(call("a", "search"));
        ToolResponseMessage withStray = results(result("a", "search"), result("zzz", "ghost"));
        ToolResponseMessage orphan = results(result("q", "search"));

        ToolPairing.Repaired repaired = ToolPairing.repair(
                List.of(new UserMessage("q"), orphan, calls, withStray, new AssistantMessage("done")));

        List<Message> out = repaired.messages();
        assertEquals(4, out.size());
        assertInstanceOf(UserMessage.class, out.get(0));
        assertSame(calls, out.get(1));
        assertEquals(List.of("a"), ((ToolResponseMessage) out.get(2)).getResponses().stream().map(ToolResponse::id).toList());
        assertEquals(2, repaired.repairs());
    }

    @Test
    @DisplayName("notRun answers every call with the reason, as a failed-tool envelope")
    void notRunAnswersEveryCall() {
        ToolResponseMessage answered = ToolPairing.notRun(
                calling(call("a", "search"), call("b", "read")), "Not run: \"step\" limit");
        assertEquals(List.of("a", "b"), answered.getResponses().stream().map(ToolResponse::id).toList());
        assertEquals("{\"success\":false,\"error\":\"Not run: \\\"step\\\" limit\"}",
                answered.getResponses().get(0).responseData());
    }

    private static ToolCall call(String id, String name) {
        return new ToolCall(id, "function", name, "{}");
    }

    private static AssistantMessage calling(ToolCall... calls) {
        return AssistantMessage.builder().content("").toolCalls(List.of(calls)).build();
    }

    private static ToolResponse result(String id, String name) {
        return new ToolResponse(id, name, "result of " + name);
    }

    private static ToolResponseMessage results(ToolResponse... responses) {
        return ToolResponseMessage.builder().responses(List.of(responses)).build();
    }
}
