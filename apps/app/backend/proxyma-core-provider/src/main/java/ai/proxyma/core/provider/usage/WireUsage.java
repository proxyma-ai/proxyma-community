package ai.proxyma.core.provider.usage;

import ai.proxyma.core.common.usage.CallFacts;
import ai.proxyma.core.common.usage.TokenUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.ResolvableType;
import org.springframework.core.codec.StringDecoder;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * THE USAGE A PROVIDER'S OWN RESPONSE STATED, read off the wire, for the counts Spring AI 1.1.7
 * loses on the way to a {@link ChatResponse} (0.29.2 feedback, a probe of a LiteLLM gateway
 * fronting Claude, 2026-10-08):
 *
 * <ul>
 *   <li><b>A streamed Anthropic call recorded no input and no cache at all.</b> Spring AI takes
 *       those from {@code message_start} and only the output from {@code message_delta}, whose
 *       usage record has no other field. Anthropic itself states them in {@code message_start};
 *       the gateway sends zeros there and the real counts in {@code message_delta}. So every
 *       streamed call through it - every chat turn and every sub-agent step - was filed with its
 *       output alone: cost understated, the context meter at zero, condensation never due.</li>
 *   <li><b>An OpenAI-route gateway's prompt_tokens EXCLUDES the cached tokens</b>, where OpenAI
 *       counts them inside it, and it states its cache WRITES in fields Spring AI has no slot for.
 *       Reading it the OpenAI way clamped the uncached input to zero and lost every write. Read
 *       that way for LiteLLM's protocol alone since 2026-10-13 ({@link Shape#OPENAI_CACHE_OUTSIDE}).</li>
 *   <li><b>Which writes were one-hour</b> is stated by both shapes and kept by neither, so it was
 *       inferred from what the request asked for - which a gateway may not have honored.</li>
 * </ul>
 *
 * <p>How it travels: the gateway opens a tap per call - in the Reactor context of a stream
 * ({@link #into}), on the thread of a blocking call ({@link #bind}). The adapter's clients carry
 * a {@link #streamFilter} and a {@link #callInterceptor} that read each response's usage into the
 * tap of the call it belongs to; a client with no tap open passes everything through untouched.
 * The gateway then folds the tap into every chunk after it ({@link #correct}), as a
 * {@link TokenUsage} that {@link TokenUsageExtractor} returns as it is - so the chat's own
 * aggregation, the per-call ledger and the context meter all read the corrected counts through
 * the path they already had. Bytes pass through unchanged; nothing here rewrites a response.
 *
 * <p>Correcting by FIELD-WISE MAXIMUM is safe in both directions: what Spring AI did read is never
 * lowered, and a provider that reports correctly (Anthropic itself, OpenAI) reads here exactly as
 * it did there.
 */
public final class WireUsage {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DATA = "data:";
    private static final ThreadLocal<WireUsage> CALL = new ThreadLocal<>();

    /** The response shapes a usage is read from. */
    public enum Shape {
        ANTHROPIC(WireUsage::anthropicUsageNode, WireUsage::fromAnthropic, WireUsage::anthropicFacts),
        /** OpenAI's: cached tokens counted inside prompt_tokens. */
        OPENAI(root -> root.path("usage"), WireUsage::fromOpenAi, WireUsage::openAiFacts),
        /** LiteLLM's OpenAI route, which counts them outside it - see {@link #fromOpenAiCacheOutside}. */
        OPENAI_CACHE_OUTSIDE(root -> root.path("usage"), WireUsage::fromOpenAiCacheOutside, WireUsage::openAiFacts);

        private final Function<JsonNode, JsonNode> locate;
        private final Function<JsonNode, TokenUsage> read;
        private final Function<JsonNode, Facts> facts;

        Shape(Function<JsonNode, JsonNode> locate, Function<JsonNode, TokenUsage> read,
              Function<JsonNode, Facts> facts) {
            this.locate = locate;
            this.read = read;
            this.facts = facts;
        }

        /** What one JSON document says about the call beyond its counts. */
        public Facts factsOf(JsonNode root) {
            return root == null ? Facts.NONE : facts.apply(root);
        }

        /** The usage one JSON document - a whole body, or one event of a stream - states, or EMPTY. */
        public TokenUsage usageOf(JsonNode root) {
            if (root == null) return TokenUsage.EMPTY;
            JsonNode usage = locate.apply(root);
            return usage != null && usage.isObject() ? read.apply(usage) : TokenUsage.EMPTY;
        }
    }

    private final AtomicReference<TokenUsage> stated = new AtomicReference<>(TokenUsage.EMPTY);
    private final AtomicReference<Facts> facts = new AtomicReference<>(Facts.NONE);

    /**
     * What a response said about its call beyond the counts - the money it says the call cost
     * (OpenRouter's usage.cost, LiteLLM's usage.cost or x-litellm-response-cost), the tier that
     * served it, Anthropic's region and its server-side web searches. Folded across a stream: a
     * value stated later replaces an earlier one, searches keep their largest count.
     */
    public record Facts(Double costUsd, String serviceTier, String inferenceGeo, long webSearchRequests) {
        public static final Facts NONE = new Facts(null, null, null, 0);

        Facts merge(Facts later) {
            if (later == null || later.equals(NONE)) return this;
            return new Facts(
                    later.costUsd != null ? later.costUsd : costUsd,
                    later.serviceTier != null ? later.serviceTier : serviceTier,
                    later.inferenceGeo != null ? later.inferenceGeo : inferenceGeo,
                    Math.max(webSearchRequests, later.webSearchRequests));
        }
    }

    /** The facts read so far, as the pricing takes them. */
    public CallFacts facts(String protocol, Instant at) {
        Facts f = facts.get();
        return new CallFacts(f.costUsd(), f.serviceTier(), f.inferenceGeo(), f.webSearchRequests(), protocol, at);
    }

    void absorbFacts(Facts more) {
        if (more != null && !more.equals(Facts.NONE)) facts.updateAndGet(f -> f.merge(more));
    }

    /** What the responses read so far stated, folded by maximum; EMPTY when nothing was read. */
    public TokenUsage stated() {
        return stated.get();
    }

    void absorb(TokenUsage usage) {
        if (usage != null && !usage.equals(TokenUsage.EMPTY)) stated.updateAndGet(u -> u.maxWith(usage));
    }

    /** Puts this tap in a stream's Reactor context, where {@link #streamFilter} finds it. */
    public Context into(Context context) {
        return context.put(WireUsage.class, this);
    }

    /** A tap open on a thread, closed when the call it was opened for returns. */
    public interface Open extends AutoCloseable {
        @Override void close();
    }

    /** Opens this tap on the current thread for one blocking call. */
    public Open bind() {
        WireUsage previous = CALL.get();
        CALL.set(this);
        return () -> {
            if (previous == null) CALL.remove(); else CALL.set(previous);
        };
    }

    /**
     * The chunk with its usage raised to what the wire stated so far, or the chunk itself when
     * nothing was stated or nothing is higher. The usage is carried as a TokenUsage, which
     * TokenUsageExtractor hands back as it is.
     */
    public ChatResponse correct(ChatResponse chunk) {
        TokenUsage wire = stated.get();
        if (chunk == null || wire.equals(TokenUsage.EMPTY)) return chunk;
        TokenUsage own = TokenUsageExtractor.extract(chunk);
        TokenUsage merged = own.maxWith(wire);
        if (merged.equals(own)) return chunk;
        return withUsage(chunk, merged);
    }

    /**
     * The chunk with its usage replaced by {@code merged}, carried as a TokenUsage that
     * TokenUsageExtractor hands back as it is. Everything else in the chunk is kept.
     */
    public static ChatResponse withUsage(ChatResponse chunk, TokenUsage merged) {
        if (chunk == null) return null;
        ChatResponseMetadata md = chunk.getMetadata() != null ? chunk.getMetadata() : new ChatResponseMetadata();
        ChatResponseMetadata.Builder builder = ChatResponseMetadata.builder()
                .id(md.getId())
                .model(md.getModel())
                .rateLimit(md.getRateLimit())
                .promptMetadata(md.getPromptMetadata())
                .usage(new DefaultUsage(
                        (int) Math.min(Integer.MAX_VALUE, merged.inputTokens()),
                        (int) Math.min(Integer.MAX_VALUE, merged.outputTokens()),
                        (int) Math.min(Integer.MAX_VALUE, merged.promptTokensTotal() + merged.outputTokens()),
                        merged));
        for (Map.Entry<String, Object> entry : md.entrySet()) builder.keyValue(entry.getKey(), entry.getValue());
        return new ChatResponse(chunk.getResults(), builder.build());
    }

    // -- Reading a usage ---------------------------------------------------------------------------

    // message_start carries it under message.usage; message_delta and a whole body under usage.
    private static JsonNode anthropicUsageNode(JsonNode root) {
        JsonNode nested = root.path("message").path("usage");
        return nested.isObject() ? nested : root.path("usage");
    }

    /**
     * An Anthropic-shaped usage: input EXCLUDES both cache categories, so the prompt is the sum of
     * the three. A key left out is zero (the gateway omits zero counts). {@code cache_creation}
     * splits the writes by lifetime when present.
     */
    static TokenUsage fromAnthropic(JsonNode usage) {
        long input = count(usage, "input_tokens");
        long output = count(usage, "output_tokens");
        long write = count(usage, "cache_creation_input_tokens");
        long read = count(usage, "cache_read_input_tokens");
        JsonNode split = usage.path("cache_creation");
        boolean stated = split.isObject();
        long longWrites = stated ? count(split, "ephemeral_1h_input_tokens") : 0;
        return new TokenUsage(input, output, read, write, input + read + write, longWrites, stated);
    }

    /**
     * An OpenAI-shaped usage, as OpenAI counts it: cached tokens INSIDE prompt_tokens. Writes are
     * {@code cache_write_tokens} (OpenRouter) or {@code cache_creation_tokens}, split by lifetime in
     * {@code cache_creation_token_details}; OpenAI itself states none, which reads as zero.
     */
    static TokenUsage fromOpenAi(JsonNode usage) {
        return openAi(usage, false);
    }

    /**
     * The same from LiteLLM's OpenAI route, which in front of Anthropic counts the cached tokens
     * OUTSIDE prompt_tokens - and its own total gives that away: total_tokens is then
     * prompt + completion + read + write, where OpenAI's is prompt + completion. Only that exact
     * excess is read as "outside"; a total that differs for reasons of its own (reasoning counted
     * apart) is read as OpenAI counts it.
     */
    static TokenUsage fromOpenAiCacheOutside(JsonNode usage) {
        return openAi(usage, true);
    }

    private static TokenUsage openAi(JsonNode usage, boolean cacheMayBeOutside) {
        long prompt = count(usage, "prompt_tokens");
        long completion = count(usage, "completion_tokens");
        long total = count(usage, "total_tokens");
        JsonNode details = usage.path("prompt_tokens_details");
        long read = count(details, "cached_tokens");
        long write = Math.max(count(details, "cache_creation_tokens"), count(details, "cache_write_tokens"));
        JsonNode split = details.path("cache_creation_token_details");
        boolean stated = split.isObject();
        long longWrites = stated ? count(split, "ephemeral_1h_input_tokens") : 0;
        boolean cacheOutsidePrompt = cacheMayBeOutside
                && read + write > 0 && total == prompt + completion + read + write;
        long promptTotal = cacheOutsidePrompt ? prompt + read + write : prompt;
        return new TokenUsage(prompt, completion, read, write, promptTotal, longWrites, stated);
    }

    // Anthropic-shaped: in the same usage record as the counts. A LiteLLM gateway that was told to
    // (include_cost_in_streaming_usage) adds its cost there too, in message_delta.
    static Facts anthropicFacts(JsonNode root) {
        JsonNode usage = anthropicUsageNode(root);
        if (!usage.isObject()) return Facts.NONE;
        return new Facts(money(usage.path("cost")), text(usage, "service_tier"), text(usage, "inference_geo"),
                count(usage.path("server_tool_use"), "web_search_requests"));
    }

    /**
     * OpenAI-shaped: the tier at the top of the response, a cost inside usage. OpenRouter states
     * usage.cost on every call (in a stream's last chunk); with your own provider key ("BYOK") that
     * is only OpenRouter's fee, and the provider's own charge is cost_details.upstream_inference_cost
     * - so the call cost both. LiteLLM states usage.cost in a stream's last chunk when told to.
     */
    static Facts openAiFacts(JsonNode root) {
        JsonNode usage = root.path("usage");
        Double cost = usage.isObject() ? money(usage.path("cost")) : null;
        if (cost != null && usage.path("is_byok").asBoolean(false)) {
            Double upstream = money(usage.path("cost_details").path("upstream_inference_cost"));
            if (upstream != null) cost += upstream;
        }
        return new Facts(cost, text(root, "service_tier"), null, 0);
    }

    private static Double money(JsonNode value) {
        if (value.isNumber()) {
            double v = value.asDouble();
            return Double.isFinite(v) && v >= 0 ? v : null;
        }
        if (value.isTextual()) return money(value.asText());
        return null;
    }

    static Double money(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            double v = Double.parseDouble(text.trim());
            return Double.isFinite(v) && v >= 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static long count(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? Math.max(0, value.asLong()) : 0;
    }

    // -- The clients' halves -----------------------------------------------------------------------

    /**
     * For a WebClient: reads the usage of every event of a STREAMED response into the tap in the
     * subscriber's context. Lines pass through as the bytes they arrived as, one at a time, so an
     * event's usage is in the tap before anything downstream has parsed that event.
     */
    public static ExchangeFilterFunction streamFilter(Shape shape) {
        return ExchangeFilterFunction.ofResponseProcessor(response -> Mono.deferContextual(ctx -> {
            WireUsage tap = ctx.getOrDefault(WireUsage.class, null);
            boolean eventStream = response.headers().contentType()
                    .map(MediaType.TEXT_EVENT_STREAM::isCompatibleWith)
                    .orElse(false);
            if (tap == null || !eventStream) return Mono.just(response);
            return Mono.just(response.mutate().body(body -> tapLines(body, shape, tap)).build());
        }));
    }

    private static Flux<DataBuffer> tapLines(Flux<DataBuffer> body, Shape shape, WireUsage tap) {
        DefaultDataBufferFactory buffers = DefaultDataBufferFactory.sharedInstance;
        return StringDecoder.allMimeTypes(List.of("\n"), true)
                .decode(body, ResolvableType.forClass(String.class), null, null)
                .map(line -> {
                    tap.absorb(usageOfEvent(line, shape));
                    tap.absorbFacts(factsOfEvent(line, shape));
                    return buffers.wrap((line + "\n").getBytes(StandardCharsets.UTF_8));
                });
    }

    /** The usage one line of an event stream states; EMPTY for anything but a data line carrying one. */
    static TokenUsage usageOfEvent(String line, Shape shape) {
        if (line == null || !line.startsWith(DATA) || !line.contains("\"usage\"")) return TokenUsage.EMPTY;
        try {
            return shape.usageOf(JSON.readTree(line.substring(DATA.length())));
        } catch (Exception notJson) {
            return TokenUsage.EMPTY;
        }
    }

    /** What one line of an event stream says about the call; NONE for anything else. */
    static Facts factsOfEvent(String line, Shape shape) {
        if (line == null || !line.startsWith(DATA)
                || !(line.contains("\"usage\"") || line.contains("\"service_tier\""))) {
            return Facts.NONE;
        }
        try {
            return shape.factsOf(JSON.readTree(line.substring(DATA.length())));
        } catch (Exception notJson) {
            return Facts.NONE;
        }
    }

    /** LiteLLM's header with the cost of a whole (not streamed) call. */
    static final String LITELLM_COST_HEADER = "x-litellm-response-cost";

    /**
     * For a RestClient: reads the usage of a whole JSON response into the tap open on this thread.
     * Only a successful JSON body is read, and only while a tap is open; the body is buffered once
     * and handed on as it was.
     */
    public static ClientHttpRequestInterceptor callInterceptor(Shape shape) {
        return (request, requestBody, execution) -> {
            ClientHttpResponse response = execution.execute(request, requestBody);
            WireUsage tap = CALL.get();
            MediaType type = response.getHeaders().getContentType();
            if (tap == null || !response.getStatusCode().is2xxSuccessful()
                    || type == null || !MediaType.APPLICATION_JSON.isCompatibleWith(type)) {
                return response;
            }
            byte[] bytes;
            try (InputStream in = response.getBody()) {
                bytes = in.readAllBytes();
            }
            try {
                JsonNode root = JSON.readTree(bytes);
                tap.absorb(shape.usageOf(root));
                tap.absorbFacts(shape.factsOf(root));
                Double headerCost = money(response.getHeaders().getFirst(LITELLM_COST_HEADER));
                if (headerCost != null) tap.absorbFacts(new Facts(headerCost, null, null, 0));
            } catch (Exception notJson) {
                // Spring AI will say what is wrong with it.
            }
            return new Buffered(response, bytes);
        };
    }

    private record Buffered(ClientHttpResponse delegate, byte[] bytes) implements ClientHttpResponse {
        @Override public HttpStatusCode getStatusCode() throws IOException { return delegate.getStatusCode(); }
        @Override public String getStatusText() throws IOException { return delegate.getStatusText(); }
        @Override public HttpHeaders getHeaders() { return delegate.getHeaders(); }
        @Override public InputStream getBody() { return new ByteArrayInputStream(bytes); }
        @Override public void close() { delegate.close(); }
    }
}
