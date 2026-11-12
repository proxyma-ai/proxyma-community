package ai.proxyma.core.provider.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.http.client.reactive.ClientHttpRequestDecorator;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A STREAMED TURN SHOWS WHAT THE MODEL THINKS (2026-11-11, the maintainer: "when the model is thinking, can we have its
 * thinking stream and print out thinking tokens count instead of just the timer?"). Claude's models from generation 5
 * think by default (adaptive) and stream their thinking blocks EMPTY unless the request asks for {@code display:
 * "summarized"} - Anthropic's default there is "omitted", which bills the same and shows nothing. So a streamed request to
 * such a model is sent with {@code thinking: {type: "adaptive", display: "summarized"}}, or with the display added to an
 * adaptive thinking it already names. What the model thinks, and what it costs, does not change: only whether the summary
 * comes back.
 *
 * <p>Left alone: an older model (before generation 5 a request without {@code thinking} does not think at all, and
 * adding it would turn thinking ON), a request that sets thinking another way (enabled, disabled, between_tools), one
 * that already names a display, and anything that is not a JSON object with a model. Native Anthropic only - a gateway
 * speaking Anthropic's API (LiteLLM) is not sent a field it may not pass on.
 */
final class AnthropicThinkingDisplay {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** claude-opus-5-5, claude-haiku-5-5, claude-fable-5-1, claude-sonnet-5: the family and its generation. */
    private static final Pattern MODEL = Pattern.compile("claude-(?:opus|sonnet|haiku|fable|mythos)-(\\d+)(?:\\D.*)?");

    private AnthropicThinkingDisplay() {}

    /** Whether a model thinks by default, so asking for its thinking's summary changes nothing but what is shown. */
    static boolean thinksByDefault(String model) {
        if (model == null) return false;
        Matcher m = MODEL.matcher(model.strip());
        if (!m.matches()) return false;
        try {
            return Integer.parseInt(m.group(1)) >= 5;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** The request body with its thinking's display asked for, or the same bytes. */
    static byte[] shown(byte[] body) {
        if (body == null || body.length == 0) return body;
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (Exception notJson) {
            return body;
        }
        if (!(root instanceof ObjectNode request) || !ask(request)) return body;
        try {
            return JSON.writeValueAsBytes(request);
        } catch (Exception unwritable) {
            return body;
        }
    }

    /** Adds the display; false when the request is left as it is. */
    static boolean ask(ObjectNode request) {
        if (!thinksByDefault(request.path("model").asText(null))) return false;
        JsonNode thinking = request.get("thinking");
        if (thinking == null || thinking.isNull()) {
            request.putObject("thinking").put("type", "adaptive").put("display", "summarized");
            return true;
        }
        if (thinking instanceof ObjectNode t && "adaptive".equals(t.path("type").asText()) && !t.has("display")) {
            t.put("display", "summarized");
            return true;
        }
        return false;
    }

    /** For the WebClient behind a stream: the body is joined, rewritten and written once. */
    static ExchangeFilterFunction streamFilter() {
        return (request, next) -> next.exchange(ClientRequest.from(request)
                .body((outputMessage, context) -> request.body().insert(new Showing(outputMessage), context))
                .build());
    }

    private static final class Showing extends ClientHttpRequestDecorator {

        Showing(ClientHttpRequest delegate) {
            super(delegate);
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            return DataBufferUtils.join(body).flatMap(joined -> {
                byte[] bytes = new byte[joined.readableByteCount()];
                joined.read(bytes);
                DataBufferUtils.release(joined);
                byte[] rewritten = shown(bytes);
                HttpHeaders headers = getHeaders();
                if (headers.getContentLength() >= 0) headers.setContentLength(rewritten.length);
                return super.writeWith(Mono.just(bufferFactory().wrap(rewritten)));
            });
        }
    }
}
