package ai.proxyma.core.provider.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.ResolvableType;
import org.springframework.core.codec.StringDecoder;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * AN ERROR EVENT INSIDE A STREAM IS A FAILURE, NOT AN EMPTY ANSWER (0.34.1 feedback).
 *
 * <p>A stream can be refused after its 200, and Anthropic documents that it is: it sends
 * {@code event: error} when it is overloaded mid-answer. A LiteLLM proxy on its Anthropic route
 * answers EVERY streamed refusal that way - a model it does not serve included - which is how this
 * was found. Spring AI 1.1.7 maps that event to an empty chunk and logs "Unhandled event
 * type: ERROR" (StreamHelper), so the call ended normally with no text and no tool call. A sub-agent
 * whose model the gateway did not have reported "finished without writing a report" on every run,
 * two seconds in, with the gateway's reason nowhere.
 *
 * <p>So the first {@code data:} line of type {@code error} ends the body with a
 * WebClientResponseException carrying the status that error type stands for and the provider's own
 * message - what a refusal before the 200 already raises (see AnthropicAdapter), so ModelCall retries
 * an overloaded or rate-limited stream and reports anything else in the provider's words. Every other
 * line passes through unchanged.
 */
final class AnthropicStreamErrors {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DATA = "data:";

    // Anthropic's error types and the statuses its documentation gives them. 529 is "overloaded".
    private static final Map<String, Integer> STATUS = Map.ofEntries(
            Map.entry("invalid_request_error", 400),
            Map.entry("authentication_error", 401),
            Map.entry("billing_error", 402),
            Map.entry("permission_error", 403),
            Map.entry("not_found_error", 404),
            Map.entry("request_too_large", 413),
            Map.entry("rate_limit_error", 429),
            Map.entry("api_error", 500),
            Map.entry("timeout_error", 504),
            Map.entry("overloaded_error", 529));

    private AnthropicStreamErrors() {}

    static ExchangeFilterFunction streamFilter() {
        return ExchangeFilterFunction.ofResponseProcessor(response -> {
            boolean eventStream = response.headers().contentType()
                    .map(MediaType.TEXT_EVENT_STREAM::isCompatibleWith)
                    .orElse(false);
            if (!eventStream || !response.statusCode().is2xxSuccessful()) return Mono.just(response);
            HttpHeaders headers = response.headers().asHttpHeaders();
            return Mono.just(response.mutate().body(body -> raiseErrors(body, headers)).build());
        });
    }

    private static Flux<DataBuffer> raiseErrors(Flux<DataBuffer> body, HttpHeaders headers) {
        DefaultDataBufferFactory buffers = DefaultDataBufferFactory.sharedInstance;
        return StringDecoder.allMimeTypes(List.of("\n"), true)
                .decode(body, ResolvableType.forClass(String.class), null, null)
                .concatMap(line -> {
                    WebClientResponseException error = errorOf(line, headers);
                    return error != null ? Flux.error(error)
                            : Flux.just(buffers.wrap((line + "\n").getBytes(StandardCharsets.UTF_8)));
                });
    }

    /** The failure one line of an event stream states; null for anything but an error event's data. */
    static WebClientResponseException errorOf(String line, HttpHeaders headers) {
        if (line == null || !line.startsWith(DATA) || !line.contains("\"error\"")) return null;
        String data = line.substring(DATA.length()).trim();
        JsonNode root;
        try {
            root = JSON.readTree(data);
        } catch (Exception notJson) {
            return null;
        }
        if (root == null || !"error".equals(root.path("type").asText())) return null;
        JsonNode error = root.path("error");
        String type = error.path("type").asText("");
        String message = error.path("message").asText("");
        int status = STATUS.getOrDefault(type, 500);
        String text = status + " " + (type.isEmpty() ? "error" : type)
                + (message.isBlank() ? "" : " - " + message);
        return new WebClientResponseException(text, status, type, headers,
                data.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
}
