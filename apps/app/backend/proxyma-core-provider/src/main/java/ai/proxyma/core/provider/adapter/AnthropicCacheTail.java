package ai.proxyma.core.provider.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.http.client.reactive.ClientHttpRequestDecorator;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * THE CONVERSATION'S CACHE BREAKPOINT GOES ON ITS LAST MESSAGE (0.29.2 feedback).
 *
 * <p>Spring AI 1.1.7's CONVERSATION_HISTORY strategy marks history blocks first come, first served,
 * up to Anthropic's limit of four breakpoints with the system prompt's - so in a turn of several
 * tool steps the markers landed on the first answer, the second question and the first step, and
 * nothing after that was ever cached: every later step of the turn re-sent the growing tail at the
 * full input rate (seen in the request, 2026-10-08). Anthropic's own pattern is one breakpoint at
 * the END of the prompt, moving forward with every call; the next call finds the previous one's
 * prefix by looking back from its own (up to twenty blocks), reads it, and writes only what is new.
 *
 * <p>So the request body is rewritten on its way out: every breakpoint inside {@code messages} is
 * taken out and one - with the lifetime Spring AI gave them - is put on the last block of the last
 * message that can carry one (never a thinking block). The system prompt's and the tools'
 * breakpoints stay where they are. A request with no breakpoint in its messages is left alone, and
 * so is anything that is not JSON with a {@code messages} array.
 */
final class AnthropicCacheTail {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CACHE_CONTROL = "cache_control";
    private static final Set<String> UNMARKABLE = Set.of("thinking", "redacted_thinking");

    private AnthropicCacheTail() {}

    /** The request body with its history breakpoint moved to the end, or the same bytes. */
    static byte[] retail(byte[] body) {
        if (body == null || body.length == 0) return body;
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (Exception notJson) {
            return body;
        }
        if (!(root instanceof ObjectNode request) || !moveToTail(request)) return body;
        try {
            return JSON.writeValueAsBytes(request);
        } catch (Exception unwritable) {
            return body;
        }
    }

    /** Moves the breakpoints in {@code messages} to the tail; false when there was nothing to move. */
    static boolean moveToTail(ObjectNode request) {
        if (!(request.get("messages") instanceof ArrayNode messages) || messages.isEmpty()) return false;
        JsonNode marker = null;
        for (JsonNode message : messages) {
            if (!(message.get("content") instanceof ArrayNode blocks)) continue;
            for (JsonNode block : blocks) {
                if (block instanceof ObjectNode b && b.has(CACHE_CONTROL)) {
                    if (marker == null) marker = b.get(CACHE_CONTROL).deepCopy();
                    b.remove(CACHE_CONTROL);
                }
            }
        }
        if (marker == null) return false;
        for (int m = messages.size() - 1; m >= 0; m--) {
            if (!(messages.get(m) instanceof ObjectNode message)) continue;
            JsonNode content = message.get("content");
            if (content != null && content.isTextual()) {
                ArrayNode blocks = message.putArray("content");
                blocks.addObject().put("type", "text").put("text", content.asText()).set(CACHE_CONTROL, marker);
                return true;
            }
            if (!(content instanceof ArrayNode blocks)) continue;
            for (int i = blocks.size() - 1; i >= 0; i--) {
                if (blocks.get(i) instanceof ObjectNode block && !UNMARKABLE.contains(block.path("type").asText())) {
                    block.set(CACHE_CONTROL, marker);
                    return true;
                }
            }
        }
        return true;
    }

    /** For the RestClient behind a blocking call. */
    static ClientHttpRequestInterceptor callInterceptor() {
        return (request, body, execution) -> execution.execute(request, retail(body));
    }

    /** For the WebClient behind a stream: the body is joined, rewritten and written once. */
    static ExchangeFilterFunction streamFilter() {
        return (request, next) -> next.exchange(ClientRequest.from(request)
                .body((outputMessage, context) -> request.body().insert(new Retailing(outputMessage), context))
                .build());
    }

    private static final class Retailing extends ClientHttpRequestDecorator {

        Retailing(ClientHttpRequest delegate) {
            super(delegate);
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            return DataBufferUtils.join(body).flatMap(joined -> {
                byte[] bytes = new byte[joined.readableByteCount()];
                joined.read(bytes);
                DataBufferUtils.release(joined);
                byte[] rewritten = retail(bytes);
                HttpHeaders headers = getHeaders();
                if (headers.getContentLength() >= 0) headers.setContentLength(rewritten.length);
                return super.writeWith(Mono.just(bufferFactory().wrap(rewritten)));
            });
        }
    }
}
