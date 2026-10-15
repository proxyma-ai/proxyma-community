package ai.proxyma.core.chat.service;

import ai.proxyma.core.common.usage.TokenUsage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The two things that cannot be done by reading one chunk of a streamed response at a time.
 *
 * <p>Both live here rather than inline in ModelCall.aggregate (ChatService's, until 2026-10-02) because both are
 * pure functions over a list, both were wrong for the same reason, and neither was reachable by a
 * test while it sat inside a private method on a Spring bean that needs a dozen collaborators to
 * construct. See StreamChunkAggregationTest, which is the whole point of the extraction.
 *
 * <h2>The reason that was wrong, once, in two places</h2>
 *
 * <p>The aggregator assumed each chunk of a stream carries its own DISJOINT slice of the response -
 * so text could be appended, tool calls could be appended, and usage arrived on exactly one chunk.
 * That is true of an OpenAI-style stream and false of Anthropic's, where Spring AI re-emits a
 * RUNNING TOTAL on chunk after chunk. Appending a running total duplicates it; keeping only the
 * last one throws away everything the last one does not happen to restate.
 */
final class StreamChunkAggregation {

    private StreamChunkAggregation() {}

    /**
     * The tool calls a streamed response actually asked for, with the re-emitted ones removed.
     *
     * <p><b>Spring AI's {@code ToolUseAggregationEvent} accumulates and is never cleared.</b> Its
     * {@code toolContentBlocks} list is appended to on every {@code content_block_stop}, and the
     * whole list is published as the chunk's content each time - so a turn that calls two tools
     * emits {@code [A]} and then {@code [A, B]}. Appending both chunks' tool calls executes A
     * twice.
     *
     * <p>That cost a turn twice over. The duplicate ran as a real tool call - a second identical
     * retrieval, its latency and its tokens - and then the assistant message persisted with it went
     * back to the provider on the next iteration carrying two {@code tool_use} blocks with the same
     * id, which Anthropic rejects outright:
     * {@code 400 messages.3.content.2: `tool_use` ids must be unique}. The turn died there, after
     * the work had been paid for.
     *
     * <p><b>The LAST occurrence of an id wins</b>, not the first. Each re-emission is a fresh
     * squash of the accumulated JSON, so a later copy is at worst identical and at best more
     * complete; a partially-assembled argument list can only ever appear earlier.
     *
     * <p><b>A call with no id is kept as-is, every time it appears.</b> Some providers do not
     * assign one in streaming, and an id is the only thing that can distinguish "this is the same
     * call restated" from "the model genuinely asked for this twice" - two parallel searches for
     * the same term are a real thing a model does. Dropping those would be a silent wrong answer;
     * keeping them is at worst the duplicate work this method exists to remove, and only on
     * providers that never triggered it.
     */
    static List<ToolCall> distinctToolCalls(List<ToolCall> calls) {
        if (calls == null || calls.size() < 2) return calls == null ? List.of() : List.copyOf(calls);

        // Insertion-ordered, and re-put on a repeat so the LAST copy wins while the position stays
        // the one the id was first seen at. Order matters: tool results are matched back to calls
        // by id, but a model that asked for A then B expects to be told about them in that order.
        Map<String, ToolCall> byId = new LinkedHashMap<>();
        List<ToolCall> unidentified = new ArrayList<>();
        for (ToolCall call : calls) {
            if (call == null) continue;
            String id = call.id();
            if (id == null || id.isBlank()) {
                unidentified.add(call);
            } else {
                byId.put(id, call);
            }
        }
        List<ToolCall> out = new ArrayList<>(byId.size() + unidentified.size());
        out.addAll(byId.values());
        out.addAll(unidentified);
        return out;
    }

    /**
     * The calls with an id each: a blank or missing id is given one of our own, {@code call_} and
     * 32 hex digits, and every other id is the provider's, untouched.
     *
     * <p>Gemini sends every call with an EMPTY id (Spring AI builds each one with {@code ""}) and
     * Ollama may send none. The id is what pairs a call with its result, in memory, in the
     * transcript and in every per-call wait: two parallel Gemini calls both answered with id ""
     * left ToolPairing keeping the first result and answering the second "not run" - from the next
     * model call on, the agent was told a tool that ran had not - and a null id failed the
     * transcript outright. A sub-agent already did this for itself; it is done here so the main
     * agent's calls get it too, before anything is persisted, published or waited on.
     *
     * <p>Only blank ids are replaced. A provider's own id is what its API validates on the next
     * request (Mistral takes nine characters and nothing else), so it is never rewritten.
     */
    static List<ToolCall> withCallIds(List<ToolCall> calls) {
        if (calls == null || calls.stream().allMatch(c -> c.id() != null && !c.id().isBlank())) {
            return calls == null ? List.of() : calls;
        }
        return calls.stream()
                .map(c -> c.id() == null || c.id().isBlank()
                        ? new ToolCall("call_" + UUID.randomUUID().toString().replace("-", ""), c.type(), c.name(),
                                c.arguments())
                        : c)
                .toList();
    }

    /**
     * One call's token usage, folded from every chunk that reported any.
     *
     * <p><b>Field-wise MAXIMUM, not the last chunk and not a sum.</b> The three candidate rules are
     * right for different providers and only this one is right for all of them:
     *
     * <ul>
     *   <li><b>Last one wins</b> - what this used to do. Anthropic reports the prompt size in
     *       {@code message_start} and then only the completion count in {@code message_delta}, so
     *       the last chunk restates the output and says nothing about the input. Proxyma recorded
     *       {@code in=0} for every streamed Anthropic turn, which is most of what a retrieval agent
     *       pays for: the whole retrieved context is input. Chat cost, the month total and the
     *       lifetime total were all short by that amount, while the indexing figures beside them
     *       were right - those go through non-streaming calls and never met this.</li>
     *   <li><b>Sum</b> - correct only if each chunk carried a disjoint delta. Spring AI publishes a
     *       running total, so summing a 500-chunk stream would multiply the prompt by 500.</li>
     *   <li><b>Maximum</b> - correct for a running total (the last one is the largest), correct for
     *       a single report on one chunk (every other chunk is zero), and correct when the counts
     *       are split across chunks, which is the Anthropic case. A token count never decreases
     *       within one call.</li>
     * </ul>
     *
     * <p>{@code promptTokensTotal} is maximised alongside the rest rather than recomputed from
     * them: only TokenUsageExtractor knows whether a provider's input count already includes its
     * cache tokens, and re-deriving it here would be a second opinion on the one question that
     * class exists to answer.
     *
     * <p>Returns {@link TokenUsage#EMPTY} for an empty list or one of nothing but zeroes, so a
     * caller can keep treating "no usage reported" as a single state.
     */
    static TokenUsage mergeUsage(List<TokenUsage> reported) {
        if (reported == null || reported.isEmpty()) return TokenUsage.EMPTY;
        // TokenUsage.maxWith, which also keeps the one-hour cache writes a response stated - this
        // folded five fields by hand and dropped them.
        TokenUsage merged = TokenUsage.EMPTY;
        for (TokenUsage u : reported) merged = merged.maxWith(u);
        return merged.equals(TokenUsage.EMPTY) ? TokenUsage.EMPTY : merged;
    }
}
