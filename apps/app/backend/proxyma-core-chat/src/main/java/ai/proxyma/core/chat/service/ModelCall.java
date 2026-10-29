package ai.proxyma.core.chat.service;

import ai.proxyma.core.common.audit.AuditCategory;
import ai.proxyma.core.common.audit.AuditEntry;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.common.usage.TokenUsage;
import ai.proxyma.core.guardrail.StreamingScrubber;
import ai.proxyma.core.provider.gateway.LlmSession;
import ai.proxyma.core.provider.gateway.ModelThinking;
import ai.proxyma.core.provider.usage.TokenUsageExtractor;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ONE MODEL CALL of an agent loop: the request out, the answer streamed back as it is written,
 * and the whole response assembled at the end.
 *
 * <p>The main agent's loop ({@code ChatService.reactStep}) and a sub-agent's
 * ({@code SubAgentService}) both make their calls through this, since 2026-10-02. It was the
 * middle of {@code reactStep} until then, and a sub-agent made a plain blocking call of its own -
 * no streaming, no thinking count, no retry on a busy provider, no Stop until the call returned.
 * "A sub-agent can do what the main agent can" is not something to keep true by hand in two
 * copies of two hundred lines whose every paragraph records a bug; so there is one.
 *
 * <p>What the caller supplies is what differs between the two: where the text goes, what a retry
 * is told to, and what counts as stopped. Everything else is the same call.
 */
final class ModelCall {

    // A CONNECTION that fails - proxy, TLS, refused, unresolvable - gets only this many retries.
    // The patience below is for a provider saying "busy" (429/503/529), which clears by waiting. A
    // proxy that cannot connect does not, and each attempt costs its own connect timeout on top
    // of the delay: a feedback log (2026-09-24) showed ten attempts 15 seconds apart, two and a
    // half minutes of nothing, until the user pressed Stop.
    static final int MAX_CONNECTION_RETRIES = 2;
    // A provider's server that failed and says it was transient (isTransientServerError) - a dropped
    // upstream connection behind a gateway, a 502, a 504 - gets this many quick retries, a second
    // apart and doubling, scattered. Not the busy patience: nothing says it will clear by waiting.
    static final int MAX_SERVER_RETRIES = 3;
    static final Duration SERVER_FIRST_DELAY = Duration.ofSeconds(1);
    // THE WAIT GROWS AND IS SCATTERED, since 2026-10-03. It was a fixed five seconds: when a
    // provider began refusing (429, 503, 529), every turn in flight waited the same five seconds
    // and came back together, which is the load that made it refuse. Each wait is moved by up to
    // half of itself, so the agents that were refused together do not return together.
    //
    // A BUSY PROVIDER IS WAITED OUT FOR TEN MINUTES, since 2026-10-08 (0.29.1 feedback). The
    // budget was twelve attempts doubling from one second to eight - about eighty seconds - and
    // a rate limit is counted per MINUTE: twenty sub-agents on one key were refused for longer
    // than that, and each one that gave up was a lost piece of the job. Now the wait doubles from
    // two seconds to thirty, a provider's own Retry-After is taken where it sends one, and the
    // call gives up only once ten minutes of waiting have not been enough. Stop still ends it
    // within a moment, even mid-wait.
    static final Duration BUSY_PATIENCE = Duration.ofMinutes(10);
    static final Duration BUSY_FIRST_DELAY = Duration.ofSeconds(2);
    static final Duration BUSY_MAX_DELAY = Duration.ofSeconds(30);
    // A Retry-After longer than this is not believed - one provider's "come back in an hour" is
    // its daily quota, and ten minutes of patience could not outlast it anyway.
    static final Duration RETRY_AFTER_MAX = Duration.ofSeconds(60);
    static final Duration CONNECTION_FIRST_DELAY = Duration.ofSeconds(1);
    static final double RETRY_JITTER = 0.5;
    // The longest one call is waited for, retries and all: the busy patience above, plus as long
    // again for the attempts themselves and a generation that is simply long. It was ten minutes -
    // the patience alone - so the waits of a sustained 429 ran into it before the budget was spent:
    // the turn failed with "Timeout on blocking read", the 429 was lost, no rate-limit entry was
    // written, and a sub-agent could not report RATE LIMITED (chat-agent-07).
    static final Duration CALL_LIMIT = BUSY_PATIENCE.plus(Duration.ofMinutes(10));
    // Raw provider chunks arrive per-token (or per small delta) - publishing one ChatEvent per
    // chunk means a long response can fire thousands of individual SSE frames, each triggering a
    // full re-render/re-parse client-side. Coalescing into ~80ms windows cuts event volume by
    // 10-50x while staying visually indistinguishable from per-token streaming.
    private static final long TEXT_FLUSH_INTERVAL_NANOS = Duration.ofMillis(80).toNanos();
    // How often a THINKING count is re-sent while a model thinks. A counter moving four times a
    // second reads as live; one event per streamed token would be hundreds a second for nothing.
    private static final long THINKING_EVENT_INTERVAL_NANOS = Duration.ofMillis(250).toNanos();
    // Spring AI's key for the per-tool-call thought signatures Gemini requires back on replay -
    // see mergeMetadata() and JpaChatMemoryRepository, which persists them under the same name.
    private static final String THOUGHT_SIGNATURES_KEY = "thoughtSignatures";

    private ModelCall() {}

    /** What a caller is told while the call runs. Every method is called from one thread at a time. */
    interface Listener {
        /** The first chunk (or the whole answer, when not streaming) has arrived. */
        default void firstChunk() {}

        /** Answer text to show, already through the output guardrail's scrubber. Never empty. */
        void text(String text);

        /** The model is thinking; {@code tokens} is its estimated thinking so far in this call. */
        default void thinking(int tokens) {}

        /** The provider failed in a way worth waiting out, and attempt {@code attempt} is next. */
        default void retry(int attempt, Throwable failure) {}

        /** An attempt at the call starts: the first, or the next one once a retry's wait is over. */
        default void attempt() {}

        /** The call failed for good, and not because the caller stopped it. Thrown right after. */
        default void failed(Throwable failure) {}
    }

    /**
     * The whole response, assembled from the chunks its stream produced.
     *
     * <p>The usage comes back BESIDE the response rather than on its metadata, because it is no
     * longer one chunk's metadata to hand over - see {@link StreamChunkAggregation#mergeUsage},
     * which folds every chunk that reported anything. A synthetic metadata object carrying the
     * merged figure would have to invent a native usage payload for TokenUsageExtractor to read,
     * and inventing a provider's own response shape to satisfy a reader of it is how the two come
     * to disagree.
     *
     * <p>{@code stopped} means the caller's {@code stopped} supplier turned true while the call
     * ran. The response is then whatever had arrived - possibly truncated mid-tool-call - and is
     * not to be persisted or acted on.
     */
    record Outcome(ChatResponse response, TokenUsage usage, boolean stopped) {
        AssistantMessage assistant() {
            return response.getResult().getOutput();
        }
    }

    /**
     * Makes the call. Streams when {@code streaming}, otherwise one blocking completion run off the
     * event loop (for gateways that ignore stream=true when tools are present - see
     * ChatSettings.streaming); both feed the same operators below.
     *
     * @throws RuntimeException the provider's failure, once the retry budget is spent - never for
     *         a stop, which returns an {@link Outcome} with {@code stopped} set instead.
     */
    static Outcome run(LlmSession session, boolean streaming, List<Message> prompt, List<ToolCallback> tools,
                       BooleanSupplier stopped, StreamingScrubber scrubber, Listener listener) {
        return run(session, streaming, prompt, tools, stopped, scrubber, listener, CALL_LIMIT);
    }

    /** The same, given no longer than {@code limit} - retries and all. */
    static Outcome run(LlmSession session, boolean streaming, List<Message> prompt, List<ToolCallback> tools,
                       BooleanSupplier stopped, StreamingScrubber scrubber, Listener listener, Duration limit) {
        // Buffers raw per-token deltas and flushes them as a single piece every
        // TEXT_FLUSH_INTERVAL_NANOS instead of one per chunk. Plain (non-atomic) fields are safe
        // here: doOnNext/doFinally on a single Flux subscription are called sequentially per the
        // Reactive Streams spec, never concurrently.
        //
        // THAT ARGUMENT COVERS doOnNext AGAINST doFinally AND NOTHING ELSE, which is what
        // streamLock below is for: the code after block() is a third thread, and for a year it
        // raced the final flush and won.
        StringBuilder textBuffer = new StringBuilder();
        long[] lastFlushNanos = { System.nanoTime() };

        /*
         * Orders the LAST flush against the drain that follows it.
         *
         * THE TURN THAT STREAMED AN ANSWER NOBODY SAW. doFinally runs AFTER the terminal signal
         * has been propagated downstream, so block() can - and under load routinely does - return
         * before the flush callback has run. The code after block() then drained a scrubber the
         * final flush had not yet filled, and the flush afterwards pushed the whole answer into a
         * holdback nobody would ever drain again. Measured against a booted instance: every single
         * turn lost its text once the reply arrived in many small chunks, and roughly one in
         * twenty-five when it arrived in one.
         *
         * The symptom was an assistant bubble that stayed empty while the answer sat correctly in
         * the database, so a reload fixed it - and the trace, in order, was flush bufferLen=77,
         * drain tailLen=0, accept rawLen=77 outLen=0.
         *
         * Taken by both the flush and the flush-then-drain pair below, so whichever thread arrives
         * first completes its whole step: either the flush hands the text to the scrubber and the
         * drain releases it, or the drain's own flush empties the buffer first and doFinally's
         * later flush correctly finds nothing to do.
         */
        Object streamLock = new Object();

        Runnable flushTextBuffer = () -> {
            synchronized (streamLock) {
                if (textBuffer.length() == 0) {
                    return;
                }
                String raw = textBuffer.toString();
                textBuffer.setLength(0);
                lastFlushNanos[0] = System.nanoTime();
                // The OUTPUT guardrail's streaming half. Checking only the finished answer is not
                // enough: by the time it exists, a leaked credential has been rendered in front of
                // the reader and is in their scrollback. The scrubber holds back its last few
                // hundred characters so a pattern split across two flushes is still caught - see
                // StreamingScrubber - and is inert (a pass-through) when no output rule is on.
                String text = scrubber.accept(raw);
                // Empty: everything so far is still inside the holdback, or was removed entirely.
                if (!text.isEmpty()) listener.text(text);
            }
        };

        // What the model THINKS is kept out of textBuffer entirely: only the answer is streamed,
        // scrubbed and persisted. The thinking is counted instead. One splitter per model call -
        // it carries an inline <think> block, and a tag cut in half, from one chunk to the next.
        ModelThinking.Splitter splitter = new ModelThinking.Splitter();
        int[] thinkingChars = {0};
        long[] lastThinkingNanos = {0L};
        boolean[] thinkingAnnounced = {false};
        boolean[] first = {true};
        java.util.function.BiConsumer<ChatResponse, Boolean> absorb = (chunk, streamed) -> {
            if (first[0]) {
                first[0] = false;
                listener.firstChunk();
            }
            ModelThinking.Piece piece = splitter.accept(chunk);
            if (piece.thinkingSignal()) {
                thinkingChars[0] += piece.thinking().length();
                long now = System.nanoTime();
                if (!thinkingAnnounced[0] || now - lastThinkingNanos[0] >= THINKING_EVENT_INTERVAL_NANOS) {
                    thinkingAnnounced[0] = true;
                    lastThinkingNanos[0] = now;
                    listener.thinking(ModelThinking.estimateTokens(thinkingChars[0]));
                }
            }
            if (!piece.answer().isEmpty()) {
                textBuffer.append(piece.answer());
                if (streamed && System.nanoTime() - lastFlushNanos[0] >= TEXT_FLUSH_INTERVAL_NANOS) {
                    flushTextBuffer.run();
                }
            }
        };

        Flux<ChatResponse> source = streaming
                ? session.stream(prompt, tools).doOnNext(chunk -> absorb.accept(chunk, true))
                // model.call() is blocking, so it runs off the event loop and the whole answer is
                // buffered once, letting the shared doFinally flush emit it as a single piece. A
                // blocking call cannot be cut off mid-flight the way a stream's takeWhile cancels
                // upstream - a stop is honored once the call returns. On the threads made for waiting
                // on a model (BlockingWork), not the bounded-elastic pool every database read
                // shares: a whole reply can take minutes.
                : Mono.fromCallable(() -> session.chat(prompt, tools).response())
                        .subscribeOn(ChatService.TURNS)
                        .doOnNext(response -> absorb.accept(response, false))
                        .flux();

        // Counted per call: see MAX_CONNECTION_RETRIES and BUSY_PATIENCE.
        RetryBudget budget = new RetryBudget();
        // The provider's last failure, for a call that runs out of time while retrying it.
        java.util.concurrent.atomic.AtomicReference<Throwable> lastFailure = new java.util.concurrent.atomic.AtomicReference<>();
        Aggregated aggregated;
        try {
            aggregated = source
                    // Per attempt: a retry subscribes again, so this marks the end of its wait.
                    .doOnSubscribe(subscription -> listener.attempt())
                    // Stops consuming (and cancels the upstream provider call) as soon as a stop
                    // lands, instead of only being noticed between whole calls - a generation that
                    // degenerates into a long/repeating stream otherwise could not be interrupted
                    // until it finished or the call limit below (CALL_LIMIT) tripped.
                    .takeWhile(chunk -> !stopped.getAsBoolean())
                    // takeWhile only re-evaluates when the NEXT chunk arrives, so a provider that
                    // has gone quiet mid-generation ignored the stop until it spoke again. This
                    // cancels the subscription on a timer of its own, within ~150ms.
                    .takeUntilOther(AbortWatch.signal(stopped))
                    // Flushes whatever text is still buffered when this attempt's flux ends, for
                    // any reason - normal completion, a stop, or an error about to trigger a retry
                    // - so nothing buffered is ever silently dropped.
                    .doFinally(signal -> flushTextBuffer.run())
                    // Collected PER ATTEMPT, before the retry: an attempt that failed part way - an
                    // error event in the middle of a stream - takes the chunks it had delivered with
                    // it. Collected after the retry, they stayed, and the answer that was stored
                    // began with the failed attempt's opening words followed by the whole of the
                    // retried one.
                    .collectList()
                    // The stop is re-checked on every retry decision, and the wait itself ends when a
                    // stop lands - a provider's Retry-After can be a minute, and a Stop that waited
                    // it out would not be a Stop. A failure that is not retried, or a budget that is
                    // spent, rethrows the provider's own failure.
                    .retryWhen(Retry.from(signals -> signals.concatMap(signal -> {
                        Throwable e = signal.failure();
                        lastFailure.set(e);
                        long retried = signal.totalRetries();
                        Optional<Duration> wait = stopped.getAsBoolean() ? Optional.empty()
                                : budget.next(e, ThreadLocalRandom.current().nextDouble());
                        if (wait.isEmpty()) {
                            if (!stopped.getAsBoolean() && isRateLimited(e)) {
                                AuditEntry.of(AuditCategory.LIMITS, "provider.rate-limited")
                                        .outcome(AuditEntry.FAILED)
                                        .user(SearchContext.getUserId())
                                        .conversation(SearchContext.getConversationId())
                                        .target(session.model())
                                        .summary("The provider kept refusing for its rate limit; gave up after "
                                                + budget.attempts() + " attempt(s)")
                                        .record();
                            }
                            return Mono.error(e);
                        }
                        // What the failed attempt wrote and had not yet passed on is dropped: the
                        // retry writes the answer again from its start. Under streamLock, so the
                        // attempt's own last flush either went before this or finds nothing.
                        synchronized (streamLock) {
                            textBuffer.setLength(0);
                        }
                        listener.retry(budget.attempts(), e);
                        return Mono.delay(wait.get())
                                .takeUntilOther(AbortWatch.signal(stopped))
                                .then(Mono.defer(() -> stopped.getAsBoolean()
                                        ? Mono.<Long>error(e) : Mono.just(retried)));
                    })))
                    .doOnError(e -> {
                        Throwable cause = e.getCause() != null ? e.getCause() : e;
                        if (!(cause instanceof InterruptedException) && !stopped.getAsBoolean()) {
                            listener.failed(e);
                        }
                    })
                    .map(ModelCall::aggregate)
                    .block(limit);
        } catch (RuntimeException e) {
            // The stop-aware filter above turns a stop into an immediate (non-retried) failure of
            // the underlying stream, so block() throws the ORIGINAL provider error here rather than
            // some distinct "stopped" signal - asking the supplier is the only way to tell "this
            // was stopped" apart from "the provider genuinely failed".
            if (stopped.getAsBoolean()) {
                return new Outcome(null, TokenUsage.EMPTY, true);
            }
            throw failureOf(e, lastFailure.get());
        }

        // Releases the scrubber's held-back tail, once the stream is genuinely over. Deliberately
        // NOT in the doFinally above, which fires per ATTEMPT: draining there would release the
        // tail before a retry, and the retry then re-streams the same response from the start.
        //
        // Before the stop check, on purpose: the reader has already read everything up to the
        // holdback, and swallowing the last few hundred characters would make Stop look like it
        // also truncated the answer.
        //
        // FLUSH FIRST, AND UNDER streamLock - see its note.
        String scrubbedTail;
        synchronized (streamLock) {
            // The few characters the splitter held back in case they began a <think> tag, which
            // the stream ended without completing - they were answer after all.
            textBuffer.append(splitter.finish().answer());
            flushTextBuffer.run();
            scrubbedTail = scrubber.drain();
        }
        if (!scrubbedTail.isEmpty()) listener.text(scrubbedTail);

        // takeWhile completes the stream normally (no exception) once a stop lands mid-generation,
        // so this is the only place that case surfaces.
        boolean wasStopped = stopped.getAsBoolean();
        if (aggregated == null) {
            return new Outcome(null, TokenUsage.EMPTY, wasStopped);
        }
        return new Outcome(aggregated.response(), aggregated.usage(), wasStopped);
    }

    /**
     * What a call that failed throws: {@code failure} itself - unless it is the call running out of
     * time while the provider was being retried, which is the provider's failure and not a timeout
     * of ours, and is thrown as that. Its status is what says "rate limited" to everything after it.
     */
    static RuntimeException failureOf(RuntimeException failure, Throwable lastProviderFailure) {
        boolean timedOut = failure.getCause() instanceof java.util.concurrent.TimeoutException;
        if (!timedOut || lastProviderFailure == null) return failure;
        RuntimeException provider = lastProviderFailure instanceof RuntimeException r ? r
                : new RuntimeException(lastProviderFailure.getMessage(), lastProviderFailure);
        provider.addSuppressed(failure);
        return provider;
    }

    private record Aggregated(ChatResponse response, TokenUsage usage) {}

    private static Aggregated aggregate(List<ChatResponse> chunks) {
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        Map<String, Object> md = new HashMap<>();
        // Every chunk that reported usage, folded field-wise afterwards. This used to keep ONE
        // chunk's metadata - whichever spoke last - on the belief that usage arrives exactly once
        // per stream. It does for an OpenAI-style stream; Anthropic reports the prompt size in
        // message_start and only the completion count in message_delta, so "last one wins" kept a
        // figure that says nothing about the input and Proxyma recorded in=0 for every streamed
        // Anthropic turn. See StreamChunkAggregation.mergeUsage for why the fold is a maximum.
        List<TokenUsage> reportedUsage = new ArrayList<>();
        ChatResponseMetadata usageMetadata = null;
        for (ChatResponse chunk : chunks) {
            // Iterate ALL generations, not just getResult() (the first). A streaming chunk carries a
            // single generation, so getResult() sufficed there - but AnthropicChatModel.call() (the
            // non-streaming path) packs text and tool_use into SEPARATE generations of one response
            // (text in generation 0, tool calls in generation 1). Reading only getResult() silently
            // dropped the tool calls, so the agent never acted on them. getResults() is equivalent
            // for the one-generation streaming case and correct for the multi-generation call case.
            if (chunk.getResults() == null) continue;
            for (Generation gen : chunk.getResults()) {
                AssistantMessage out = gen.getOutput();
                if (out == null) continue;
                // A thinking generation (Anthropic's own, told apart by its metadata) is not part
                // of the answer and its keys are not the answer's: its text would be persisted and
                // replayed as if the model had said it, and its `signature` would ride along.
                if (ModelThinking.isThinkingGeneration(gen)) continue;
                if (out.getText() != null) text.append(out.getText());
                if (out.getToolCalls() != null) calls.addAll(out.getToolCalls());
                if (out.getMetadata() != null) mergeMetadata(md, out.getMetadata());
            }
            if (chunk.getMetadata() != null && chunk.getMetadata().getUsage() != null) {
                usageMetadata = chunk.getMetadata();
                reportedUsage.add(TokenUsageExtractor.extract(chunk));
            }
        }
        AssistantMessage assembled = AssistantMessage.builder()
                // Inline <think> blocks out of what is persisted, too - including the one a
                // template opens in the PROMPT, which streams as a bare "</think>" the splitter
                // cannot see coming. See ModelThinking.stripInline.
                .content(ModelThinking.stripInline(text.toString()))
                // Re-emitted tool calls dropped here, not at the call site: this is where the
                // accumulation happens, and a caller cannot tell a duplicate from a genuine
                // parallel call once the list has been flattened. See
                // StreamChunkAggregation.distinctToolCalls - a duplicate ran as a real second tool
                // call and then got the NEXT request rejected outright for repeating a tool_use id.
                // Then every call is given an id of its own where the provider gave none.
                .toolCalls(StreamChunkAggregation.withCallIds(StreamChunkAggregation.distinctToolCalls(calls)))
                .properties(md)
                .build();
        // The metadata still travels, because the finish reason and the model id on it are read
        // elsewhere; only the usage is no longer taken from it.
        ChatResponse response = usageMetadata != null
                ? new ChatResponse(List.of(new Generation(assembled)), usageMetadata)
                : new ChatResponse(List.of(new Generation(assembled)));
        return new Aggregated(response, StreamChunkAggregation.mergeUsage(reportedUsage));
    }

    // Gemini's thought signatures pair positionally with the tool calls of the message they belong
    // to, and the tool calls above are ACCUMULATED across every generation/chunk - so the
    // signatures have to be accumulated the same way. A plain putAll kept only the last chunk's
    // list, which left a multi-tool-call turn short of signatures and got the follow-up request
    // rejected. Every other key is per-response and last-one-wins is right for it.
    @SuppressWarnings("unchecked")
    private static void mergeMetadata(Map<String, Object> target, Map<String, Object> incoming) {
        for (Map.Entry<String, Object> entry : incoming.entrySet()) {
            if (THOUGHT_SIGNATURES_KEY.equals(entry.getKey()) && entry.getValue() instanceof List<?> signatures) {
                ((List<Object>) target.computeIfAbsent(THOUGHT_SIGNATURES_KEY, k -> new ArrayList<>()))
                        .addAll(signatures);
            } else {
                target.put(entry.getKey(), entry.getValue());
            }
        }
    }

    // Allowlist: retry ONLY transient failures, everything else fails fast on the first attempt.
    // This is deliberately an allowlist rather than the old "isNonRetryable" denylist - a denylist
    // over an open-ended, per-provider-SDK exception space defaults every unrecognized error to
    // "retry", which is exactly how a provider returning an unparseable body (e.g. a gateway that
    // ignores stream=true and returns a non-SSE JSON that Jackson can't map -> InvalidTypeIdException)
    // or a dead-model 404 from a non-Spring SDK burned the entire retry budget before surfacing.
    // Retryable = a transient HTTP status (see isRetryableStatus) or a transient network fault, per
    // the two client stacks in play: WebClient (streaming path) wraps I/O errors as
    // WebClientRequestException; RestClient (non-streaming path) wraps them as ResourceAccessException.
    // UnknownHostException, other 4xx/5xx, and deserialization errors are all treated as permanent -
    // re-issuing them just wastes time.
    static boolean isRetryable(Throwable e) {
        if (isBusy(e) || isTransientServerError(e)) return true;
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.springframework.web.reactive.function.client.WebClientRequestException) return true;
            if (t instanceof org.springframework.web.client.ResourceAccessException) return true;
            if (t instanceof java.net.ConnectException
                    || t instanceof java.net.SocketTimeoutException
                    || t instanceof java.net.SocketException) return true;
        }
        return false;
    }

    /**
     * Whether the provider answered "busy" - 429 rate-limited, 503 unavailable, 529 Anthropic's
     * "overloaded" - which clears by waiting. Any other status is permanent.
     */
    static boolean isBusy(Throwable e) {
        int status = httpStatus(e);
        return status == 429 || status == 503 || status == 529;
    }

    /** Whether the provider refused for its RATE LIMIT in particular - what a failed agent's report says. */
    static boolean isRateLimited(Throwable e) {
        return httpStatus(e) == 429;
    }

    /**
     * Whether the provider's SERVER failed in a way that clears by asking again: a 502 or a 504, or a
     * 500 that says what went wrong was a connection, a fallback, an overload or a timeout. A
     * 500 that says anything else is a fault in the request or the server that asking again repeats,
     * and is not one of these.
     *
     * <p>Found in the maintainer's own feedback (0.35.0, a LiteLLM gateway on the Anthropic API):
     * three sub-agents died on "500 api_error - litellm.MidStreamFallbackError:
     * litellm.APIConnectionError", an upstream connection dropped mid-answer and reported in the
     * stream after its 200, on the first attempt. Asking again is safe: a model call is made before
     * any tool of its step has run. These get a few quick retries of their own, apart from the long
     * patience a busy provider is given.
     */
    static boolean isTransientServerError(Throwable e) {
        int status = httpStatus(e);
        if (status == 502 || status == 504) return true;
        if (status != 500) return false;
        String said = failureText(e).toLowerCase(Locale.ROOT);
        return TRANSIENT_SERVER_MARKERS.stream().anyMatch(said::contains);
    }

    private static final List<String> TRANSIENT_SERVER_MARKERS = List.of(
            "apiconnectionerror", "midstreamfallbackerror", "connection", "overloaded", "timeout", "timed out",
            "temporarily unavailable");

    // Everything a failure says about itself: each message down its cause chain, and the body of a
    // response that carried one.
    private static String failureText(Throwable e) {
        StringBuilder out = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t.getMessage() != null) out.append(t.getMessage()).append('\n');
            if (t instanceof org.springframework.web.reactive.function.client.WebClientResponseException wre) {
                out.append(wre.getResponseBodyAsString()).append('\n');
            } else if (t instanceof org.springframework.web.client.HttpStatusCodeException hsce) {
                out.append(hsce.getResponseBodyAsString()).append('\n');
            }
        }
        return out.toString();
    }

    // A Spring AI client that raises a status as TEXT. AnthropicApi's WebClient raised every error
    // status as a bare RuntimeException("Response exception, Status: [429 TOO_MANY_REQUESTS], ...")
    // until AnthropicAdapter put a status handler of its own in front of it - so an Anthropic rate
    // limit on a streamed call was never retried at all, by anybody (found 2026-10-08, from the
    // 0.29.1 feedback). Spring AI's RestClient error handler raises a 4xx as
    // NonTransientAiException("429 TOO_MANY_REQUESTS - {body}"). Both are read here as well as the
    // typed exceptions, so the next client to do this is not silently unretried again.
    private static final Pattern STATUS_IN_TEXT = Pattern.compile("^(?:Response exception, Status: \\[)?(\\d{3})\\b");

    // The HTTP status a failure carries anywhere in its cause chain, or 0.
    static int httpStatus(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof org.springframework.web.reactive.function.client.WebClientResponseException wre) {
                return wre.getStatusCode().value();
            }
            if (t instanceof org.springframework.web.client.HttpStatusCodeException hsce) {
                return hsce.getStatusCode().value();
            }
            // A 5xx through Spring AI's RestClient handler is a TransientAiException("500 ... - body").
            if (t.getMessage() != null && (t instanceof org.springframework.ai.retry.NonTransientAiException
                    || t instanceof org.springframework.ai.retry.TransientAiException
                    || t.getClass() == RuntimeException.class)) {
                Matcher m = STATUS_IN_TEXT.matcher(t.getMessage());
                if (m.find()) return Integer.parseInt(m.group(1));
            }
        }
        return 0;
    }

    // A failure to CONNECT or keep a connection, as opposed to a provider answering. See
    // MAX_CONNECTION_RETRIES.
    static boolean isConnectionFailure(Throwable e) {
        return httpStatus(e) == 0;
    }

    /**
     * How long the provider asked to be left alone, from a {@code Retry-After} header (seconds, or
     * an HTTP date) or the {@code retry-after-ms} some OpenAI-compatible services send instead.
     * Empty where the failure carries no headers or none of these.
     */
    static Optional<Duration> retryAfter(Throwable e, Instant now) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            org.springframework.http.HttpHeaders headers = null;
            if (t instanceof org.springframework.web.reactive.function.client.WebClientResponseException wre) {
                headers = wre.getHeaders();
            } else if (t instanceof org.springframework.web.client.HttpStatusCodeException hsce) {
                headers = hsce.getResponseHeaders();
            }
            if (headers == null) continue;
            String ms = headers.getFirst("retry-after-ms");
            if (ms != null) {
                try {
                    return Optional.of(Duration.ofMillis(Math.max(0, (long) Double.parseDouble(ms.trim()))));
                } catch (NumberFormatException ignored) {
                    // fall through to Retry-After
                }
            }
            String value = headers.getFirst(org.springframework.http.HttpHeaders.RETRY_AFTER);
            if (value == null || value.isBlank()) return Optional.empty();
            try {
                return Optional.of(Duration.ofSeconds(Math.max(0, Long.parseLong(value.trim()))));
            } catch (NumberFormatException notSeconds) {
                try {
                    Instant at = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                    return Optional.of(at.isAfter(now) ? Duration.between(now, at) : Duration.ZERO);
                } catch (DateTimeParseException notADate) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    /**
     * What a call's failures have used of its retries, and how long the next wait is. One per call;
     * the stop is the caller's to check.
     */
    static final class RetryBudget {
        private int attempts;
        private int connectionRetries;
        private int serverRetries;
        private int busyRetries;
        private Duration busyWaited = Duration.ZERO;

        /**
         * The wait before the next attempt, or empty when this failure is not worth one or the
         * budget is spent. {@code random} is in [0, 1) and scatters the wait.
         */
        Optional<Duration> next(Throwable failure, double random) {
            return next(failure, random, Instant.now());
        }

        Optional<Duration> next(Throwable failure, double random, Instant now) {
            if (!isRetryable(failure)) return Optional.empty();
            Duration wait;
            if (isConnectionFailure(failure)) {
                if (connectionRetries >= MAX_CONNECTION_RETRIES) return Optional.empty();
                wait = scatter(doubled(CONNECTION_FIRST_DELAY, connectionRetries, BUSY_MAX_DELAY), random);
                connectionRetries++;
            } else if (isTransientServerError(failure)) {
                if (serverRetries >= MAX_SERVER_RETRIES) return Optional.empty();
                wait = scatter(doubled(SERVER_FIRST_DELAY, serverRetries, BUSY_MAX_DELAY), random);
                serverRetries++;
            } else {
                // The provider's own figure where it gave one, plus up to a quarter more so the
                // agents it refused together do not all come back on the same second.
                Optional<Duration> asked = retryAfter(failure, now).map(d -> min(d, RETRY_AFTER_MAX));
                wait = asked.isPresent()
                        ? asked.get().plusMillis(Math.round(asked.get().toMillis() * 0.25 * random))
                        : scatter(doubled(BUSY_FIRST_DELAY, busyRetries, BUSY_MAX_DELAY), random);
                if (wait.compareTo(Duration.ofMillis(500)) < 0) wait = Duration.ofMillis(500);
                if (busyWaited.plus(wait).compareTo(BUSY_PATIENCE) > 0) return Optional.empty();
                busyWaited = busyWaited.plus(wait);
                busyRetries++;
            }
            attempts++;
            return Optional.of(wait);
        }

        /** How many retries this call has been granted so far - the number of the next attempt. */
        int attempts() {
            return attempts;
        }

        /** How long this call has spent waiting on a busy provider. */
        Duration busyWaited() {
            return busyWaited;
        }

        private static Duration doubled(Duration first, int times, Duration max) {
            long ms = first.toMillis() << Math.min(times, 20);
            return Duration.ofMillis(Math.min(ms, max.toMillis()));
        }

        // Moved by up to RETRY_JITTER of itself either way.
        private static Duration scatter(Duration base, double random) {
            double factor = 1 + RETRY_JITTER * (2 * random - 1);
            return Duration.ofMillis(Math.round(base.toMillis() * factor));
        }

        private static Duration min(Duration a, Duration b) {
            return a.compareTo(b) <= 0 ? a : b;
        }
    }
}
