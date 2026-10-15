package ai.proxyma.core.chat.service;

import ai.proxyma.core.common.usage.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The two things a streamed response cannot be read one chunk at a time, both of which Proxyma got
 * wrong in production against Anthropic through a LiteLLM gateway on 2026-09-12.
 *
 * <p>Every case below is the SHAPE THAT ACTUALLY HAPPENED rather than a shape that could. The tool
 * sequences are Spring AI's {@code ToolUseAggregationEvent} re-emitting an accumulating list; the
 * usage sequences are its Anthropic stream helper reporting the prompt size once at the start and
 * the completion count at the end. Both were invisible to every test that existed, because the
 * logic lived in a private method on a bean with a dozen collaborators.
 *
 * <p>Every assertion here was proved by breaking the code under it and watching this file fail:
 * pass the tool calls straight through and {@code dropsReEmittedToolCalls} reports the production
 * list {@code [toolu_A, toolu_A, toolu_B]}; take the last chunk's usage and
 * {@code recoversInputTokensFromAnEarlierChunk} reports the production {@code in=0}.
 */
class StreamChunkAggregationTest {

    private static ToolCall call(String id, String args) {
        return new ToolCall(id, "function", "searchIndexedSources", args);
    }

    // ── distinctToolCalls ─────────────────────────────────────────────────────

    /**
     * THE PRODUCTION FAILURE, exactly. Two tools were called; Spring AI published [A] on the first
     * content_block_stop and [A, B] on the second, because its aggregator appends to a list it
     * never clears. Flattening both chunks ran A twice - a duplicate retrieval nobody asked for -
     * and then sent an assistant message carrying two tool_use blocks with one id, which the
     * provider rejected: 400 "messages.3.content.2: `tool_use` ids must be unique". The turn died
     * after the work had been paid for.
     */
    @Test
    @DisplayName("tool calls: the re-emitted copy goes when the aggregator republishes its list")
    void dropsReEmittedToolCalls() {
        List<ToolCall> flattened = new ArrayList<>();
        flattened.addAll(List.of(call("toolu_A", "{\"query\":\"uds\"}")));
        flattened.addAll(List.of(call("toolu_A", "{\"query\":\"uds\"}"),
                                 call("toolu_B", "{\"query\":\"can\"}")));

        List<ToolCall> result = StreamChunkAggregation.distinctToolCalls(flattened);

        assertEquals(List.of("toolu_A", "toolu_B"), result.stream().map(ToolCall::id).toList(),
                "one call per id, in the order the ids were first seen");
    }

    /**
     * Two parallel calls to the SAME tool with the SAME arguments are a real thing a model does,
     * and the log that exposed this bug contains one. Only the id can tell that apart from a
     * re-emission, which is why nothing here compares names or arguments.
     */
    @Test
    @DisplayName("tool calls: genuinely parallel calls differing only by id are all kept")
    void keepsParallelToolCallsWithDistinctIds() {
        List<ToolCall> result = StreamChunkAggregation.distinctToolCalls(List.of(
                call("toolu_A", "{\"query\":\"can\"}"),
                call("toolu_B", "{\"query\":\"can\"}")));

        assertEquals(2, result.size());
    }

    /**
     * A later copy is a fresh squash of more accumulated JSON, so it is at worst identical to the
     * earlier one and at best more complete. Keeping the FIRST would be how a tool gets invoked
     * with half its arguments.
     */
    @Test
    @DisplayName("tool calls: the last copy of an id wins, being the most complete one")
    void lastCopyOfAToolCallIdWins() {
        List<ToolCall> result = StreamChunkAggregation.distinctToolCalls(List.of(
                call("toolu_A", "{\"que"),
                call("toolu_A", "{\"query\":\"uds\"}")));

        assertEquals(1, result.size());
        assertEquals("{\"query\":\"uds\"}", result.get(0).arguments());
    }

    /**
     * An id is the only evidence of a re-emission. Without one, dropping a call would be a silent
     * wrong answer - a search the model asked for that never ran - and the duplicate work it would
     * save has never been observed on a provider that omits ids.
     */
    @Test
    @DisplayName("tool calls: calls with no id are all kept rather than guessed about")
    void keepsUnidentifiedToolCalls() {
        List<ToolCall> result = StreamChunkAggregation.distinctToolCalls(List.of(
                call(null, "{\"query\":\"a\"}"),
                call("", "{\"query\":\"a\"}"),
                call(null, "{\"query\":\"a\"}")));

        assertEquals(3, result.size());
    }

    @Test
    @DisplayName("tool calls: a list with nothing to dedupe comes back as it was")
    void shortToolCallListsPassThrough() {
        assertEquals(List.of(), StreamChunkAggregation.distinctToolCalls(null));
        ToolCall only = call("toolu_A", "{}");
        assertEquals(List.of(only), StreamChunkAggregation.distinctToolCalls(List.of(only)));
    }

    // ── mergeUsage ────────────────────────────────────────────────────────────

    /**
     * THE COST BUG. Anthropic puts the prompt size on message_start and only the completion count
     * on message_delta, so the LAST chunk to report usage says nothing about the input. Keeping
     * that one recorded in=0 for every streamed turn - and for a retrieval agent the input IS the
     * bill, since the whole retrieved context is in it. Settings > Usage & Cost under-reported the
     * month, the lifetime and every per-model row by that amount, while the indexing figures
     * beside them stayed right: those go through non-streaming calls.
     */
    @Test
    @DisplayName("usage: the prompt size only the first chunk reported is recovered")
    void recoversInputTokensFromAnEarlierChunk() {
        TokenUsage merged = StreamChunkAggregation.mergeUsage(List.of(
                // message_start: the prompt is counted, one output token so far.
                new TokenUsage(12_000, 1, 3_400, 900, 16_300),
                // message_delta: the completion is counted and the prompt is not restated.
                new TokenUsage(0, 821, 0, 0, 0)));

        assertEquals(12_000, merged.inputTokens(), "the prompt was counted and must survive");
        assertEquals(821, merged.outputTokens(), "the final completion count wins");
        assertEquals(3_400, merged.cacheReadTokens());
        assertEquals(900, merged.cacheWriteTokens());
        assertEquals(16_300, merged.promptTokensTotal());
    }

    /**
     * The other half of why this is a maximum and not a sum. Spring AI republishes a RUNNING TOTAL
     * on chunk after chunk, so adding them up multiplies the call by the number of chunks - a
     * 500-chunk answer would have billed 500 times its prompt.
     */
    @Test
    @DisplayName("usage: a running total republished on every chunk is not summed")
    void doesNotSumARunningTotal() {
        TokenUsage merged = StreamChunkAggregation.mergeUsage(List.of(
                new TokenUsage(500, 10, 0, 0, 500),
                new TokenUsage(500, 40, 0, 0, 500),
                new TokenUsage(500, 90, 0, 0, 500)));

        assertEquals(500, merged.inputTokens(), "one call's prompt, not three copies of it");
        assertEquals(90, merged.outputTokens());
    }

    @Test
    @DisplayName("usage: a single report passes through unchanged - the OpenAI-style stream")
    void singleUsageReportIsUnchanged() {
        TokenUsage only = new TokenUsage(700, 120, 64, 0, 700);
        assertEquals(only, StreamChunkAggregation.mergeUsage(List.of(only)));
    }

    /**
     * react() now decides "did this call report any usage" from EMPTY rather than from a null
     * metadata object, so a stream that reported nothing has to arrive as exactly EMPTY.
     */
    @Test
    @DisplayName("usage: nothing reported is EMPTY, which is what the caller tests against")
    void nothingReportedIsEmpty() {
        assertSame(TokenUsage.EMPTY, StreamChunkAggregation.mergeUsage(List.of()));
        assertSame(TokenUsage.EMPTY, StreamChunkAggregation.mergeUsage(null));
        assertSame(TokenUsage.EMPTY, StreamChunkAggregation.mergeUsage(
                List.of(TokenUsage.EMPTY, TokenUsage.EMPTY)));
    }

    /**
     * chat-agent-02: Gemini sends every tool call with an empty id, and Ollama may send none. Two
     * parallel calls answered with the same id "" left ToolPairing keeping the first result and
     * answering the second "not run", so the agent was told a tool that ran had not.
     */
    @Test
    @DisplayName("tool calls: a blank or missing id is given one of its own; the provider's ids are kept")
    void blankIdsAreGivenIds() {
        List<ToolCall> calls = StreamChunkAggregation.withCallIds(java.util.Arrays.asList(
                call("", "{\"query\":\"a\"}"), call(null, "{\"query\":\"b\"}"), call("  ", "{}"), call("toolu_X", "{}")));
        List<String> ids = calls.stream().map(ToolCall::id).toList();
        assertEquals(4, ids.stream().distinct().count(), "every call has an id of its own: " + ids);
        org.junit.jupiter.api.Assertions.assertTrue(ids.subList(0, 3).stream().allMatch(id -> id.matches("call_[0-9a-f]{32}")),
                ids.toString());
        assertEquals("toolu_X", ids.get(3), "a provider's id is never rewritten");
        assertEquals("{\"query\":\"b\"}", calls.get(1).arguments());

        List<ToolCall> provided = List.of(call("toolu_A", "{}"));
        assertSame(provided, StreamChunkAggregation.withCallIds(provided));
    }

    @Test
    @DisplayName("tool calls: two parallel calls that came without ids both keep their results")
    void parallelCallsWithoutIdsArePairedApart() {
        List<ToolCall> calls = StreamChunkAggregation.withCallIds(List.of(call("", "{\"query\":\"a\"}"),
                call("", "{\"query\":\"b\"}")));
        org.springframework.ai.chat.messages.AssistantMessage assistant =
                org.springframework.ai.chat.messages.AssistantMessage.builder().content("").toolCalls(calls).build();
        org.springframework.ai.chat.messages.ToolResponseMessage results =
                org.springframework.ai.chat.messages.ToolResponseMessage.builder().responses(List.of(
                        new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(calls.get(0).id(), "searchIndexedSources", "A"),
                        new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(calls.get(1).id(), "searchIndexedSources", "B")))
                        .build();

        ToolPairing.Repaired repaired = ToolPairing.repair(List.of(
                new org.springframework.ai.chat.messages.UserMessage("go"), assistant, results));

        assertEquals(0, repaired.repairs(), "nothing to repair: each result answers its own call");
    }

    @Test
    @DisplayName("usage: the one-hour writes a response stated survive the fold")
    void statedLifetimeSurvives() {
        // 0.29.2 feedback: the fold rebuilt five fields by hand and dropped the split, so a stated
        // five-minute write was re-marked as one-hour by the session that asked for an hour.
        TokenUsage merged = StreamChunkAggregation.mergeUsage(List.of(
                new TokenUsage(0, 0, 0, 0, 0),
                new TokenUsage(3, 5, 0, 10827, 10830, 0, true)));
        assertEquals(10827, merged.cacheWriteTokens());
        assertEquals(true, merged.cacheLifetimeStated());
        assertEquals(0, merged.withLongCacheWrites().longCacheWriteTokens());
    }
}
