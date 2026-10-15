package ai.proxyma.core.provider.adapter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionChunk;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionFinishReason;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage.ToolCall;
import org.springframework.ai.openai.api.OpenAiStreamFunctionCallingHelper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A delta carrying several tool calls ended the turn inside Spring AI. Every case here is merged by
 * Spring AI's OWN stream merger, the way OpenAiApi merges a reply's tool-call deltas - so the test
 * fails if a later Spring AI stops needing the split, or starts needing something else.
 */
class ParallelToolCallDeltasTest {

    private static final String OPEN_A =
            "data: {\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_a\","
            + "\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"path\\\":\"}}]},\"finish_reason\":null}]}";
    private static final String END_A_OPEN_B =
            "data: {\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":["
            + "{\"index\":0,\"function\":{\"arguments\":\"\\\"a.txt\\\"}\"}},"
            + "{\"index\":1,\"id\":\"call_b\",\"type\":\"function\",\"function\":{\"name\":\"grep\",\"arguments\":\"{\\\"q\\\":\"}}"
            + "]},\"finish_reason\":null}]}";
    private static final String END_B =
            "data: {\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":1,"
            + "\"function\":{\"arguments\":\"\\\"x\\\"}\"}}]},\"finish_reason\":null}]}";
    private static final String FINISH =
            "data: {\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}";

    @Test
    @DisplayName("the stream that ended the turn: Spring AI refuses a second tool call in one delta")
    void whatSpringAiDoesWithoutTheSplit() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> merge(List.of(OPEN_A, END_A_OPEN_B, END_B, FINISH)));
        assertEquals("Currently only one tool call is supported per message!", refused.getMessage());
    }

    @Test
    @DisplayName("a delta that ends one call and opens the next becomes two, and both calls arrive whole")
    void aDeltaStraddlingTwoCalls() {
        List<ToolCall> calls = toolCalls(merge(splitAll(List.of(OPEN_A, END_A_OPEN_B, END_B, FINISH))));

        assertEquals(2, calls.size());
        assertEquals("call_a", calls.get(0).id());
        assertEquals("read", calls.get(0).function().name());
        assertEquals("{\"path\":\"a.txt\"}", calls.get(0).function().arguments());
        assertEquals("call_b", calls.get(1).id());
        assertEquals("grep", calls.get(1).function().name());
        assertEquals("{\"q\":\"x\"}", calls.get(1).function().arguments());
    }

    @Test
    @DisplayName("whole calls sent together after a first one are each kept, in the order they were sent")
    void wholeCallsSentTogether() {
        String first = "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                + "\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"n\\\":1}\"}}]}}]}";
        String rest = "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":["
                + "{\"index\":1,\"id\":\"call_2\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"n\\\":2}\"}},"
                + "{\"index\":2,\"id\":\"call_3\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"n\\\":3}\"}}"
                + "]}}]}";

        List<ToolCall> calls = toolCalls(merge(splitAll(List.of(first, rest, FINISH))));

        assertEquals(List.of("call_1", "call_2", "call_3"), calls.stream().map(ToolCall::id).toList());
        assertEquals(List.of("{\"n\":1}", "{\"n\":2}", "{\"n\":3}"),
                calls.stream().map(c -> c.function().arguments()).toList());
    }

    @Test
    @DisplayName("text stays on the first line; the finish reason and the usage move to the last")
    void whatTheDeltaSaidBesideItsCalls() {
        String line = "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Looking.\","
                + "\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"a\",\"arguments\":\"{}\"}},"
                + "{\"index\":1,\"id\":\"call_2\",\"type\":\"function\",\"function\":{\"name\":\"b\",\"arguments\":\"{}\"}}]},"
                + "\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3,\"total_tokens\":10}}";

        List<String> lines = ParallelToolCallDeltas.split(line);
        assertEquals(2, lines.size());
        ChatCompletionChunk first = chunk(lines.get(0));
        ChatCompletionChunk last = chunk(lines.get(1));

        assertEquals("Looking.", first.choices().get(0).delta().content());
        assertEquals(null, first.choices().get(0).finishReason());
        assertEquals(null, first.usage());
        assertEquals(null, last.choices().get(0).delta().content());
        assertEquals(ChatCompletionFinishReason.TOOL_CALLS, last.choices().get(0).finishReason());
        assertEquals(7, last.usage().promptTokens());
    }

    @Test
    @DisplayName("left as the bytes they arrived as: one call, no call, [DONE], a blank line, and the word inside text")
    void leavesEverythingElse() {
        String inText = "data: {\"choices\":[{\"delta\":{\"content\":\"\\\"tool_calls\\\": [1, 2]\"}}]}";
        for (String line : List.of(OPEN_A, END_B, FINISH, "data: [DONE]", "", ": keep-alive", inText,
                "data: {\"tool_calls\": not json")) {
            assertEquals(List.of(line), ParallelToolCallDeltas.split(line));
        }
    }

    private static List<String> splitAll(List<String> lines) {
        return lines.stream().flatMap(line -> ParallelToolCallDeltas.split(line).stream()).toList();
    }

    private static ChatCompletionChunk chunk(String line) {
        return ModelOptionsUtils.jsonToObject(line.substring("data:".length()), ChatCompletionChunk.class);
    }

    // OpenAiApi.chatCompletionStream reduces the deltas of a reply's tool calls from an empty seed.
    private static ChatCompletionChunk merge(List<String> lines) {
        OpenAiStreamFunctionCallingHelper merger = new OpenAiStreamFunctionCallingHelper();
        ChatCompletionChunk merged = new ChatCompletionChunk(null, null, null, null, null, null, null, null);
        for (String line : lines) merged = merger.merge(merged, chunk(line));
        return merged;
    }

    private static List<ToolCall> toolCalls(ChatCompletionChunk chunk) {
        return chunk.choices().get(0).delta().toolCalls();
    }
}
