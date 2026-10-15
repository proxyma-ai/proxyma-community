package ai.proxyma.core.provider.adapter;

import ai.proxyma.core.settings.dto.ChatSettings.Tone;
import ai.proxyma.core.settings.provider.ProviderProtocol;
import ai.proxyma.core.settings.provider.ResolvedProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A streamed Anthropic call that the provider refuses keeps the refusal's STATUS and HEADERS (0.29.1
 * feedback). Spring AI's AnthropicApi raises every error status of a stream as a bare
 * RuntimeException naming the status in its message, so a rate limit was never retried and its
 * Retry-After was lost; the adapter's own status handler, added first, now wins.
 *
 * <p>Against a real HTTP server on loopback, answering 429 with a Retry-After, through the adapter's
 * own client - the only way to know which of the two handlers WebClient actually ran.
 *
 * <p>And a refusal sent AFTER the 200, as an error event, is a failure too (0.34.1 feedback):
 * Spring AI turned it into an empty answer (AnthropicStreamErrors).
 */
class AnthropicStreamErrorTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    @DisplayName("a streamed 429 arrives as a WebClientResponseException carrying Retry-After")
    void streamedRateLimitKeepsItsHeaders() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Retry-After", "12");
            exchange.sendResponseHeaders(429, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        AnthropicAdapter adapter = new AnthropicAdapter(new SimpleClientHttpRequestFactory(), WebClient.builder());
        ResolvedProvider provider = new ResolvedProvider(UUID.randomUUID(), "Stub", ProviderProtocol.ANTHROPIC,
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", Map.of(), false);
        ChatModel model = adapter.buildChatModel(provider, "claude-test", Tone.CONCISE, 1024);

        Throwable failure = catchThrowable(() -> model.stream(new Prompt(new UserMessage("hi")))
                .blockLast(Duration.ofSeconds(10)));

        WebClientResponseException http = findCause(failure, WebClientResponseException.class);
        assertThat(http).as("the failure carries the HTTP response, not just text: %s", failure).isNotNull();
        assertThat(http.getStatusCode().value()).isEqualTo(429);
        assertThat(http.getHeaders().getFirst("Retry-After")).isEqualTo("12");
    }

    // What a LiteLLM gateway sends when the model it was asked for is not one it serves: the 200 has
    // gone out, so the refusal is an event of the stream (0.34.1 feedback - every sub-agent on such
    // a model ended "without writing a report" and the gateway's reason was lost).
    private static final String NOT_SERVED = "event: error\n"
            + "data: {\"type\":\"error\",\"error\":{\"type\":\"not_found_error\",\"message\":"
            + "\"litellm.APIError: model group 'claude-haiku-4-5' failed. No fallback was attempted.\"}}\n\n";

    @Test
    @DisplayName("an error event after the 200 fails the stream with its status and the provider's words")
    void errorEventInsideTheStreamIsAFailure() throws Exception {
        Throwable failure = streamAgainst(NOT_SERVED);

        WebClientResponseException http = findCause(failure, WebClientResponseException.class);
        assertThat(http).as("an error event is a failure, not an empty answer: %s", failure).isNotNull();
        assertThat(http.getStatusCode().value()).isEqualTo(404);
        assertThat(http.getMessage()).contains("not_found_error").contains("claude-haiku-4-5");
    }

    @Test
    @DisplayName("an overloaded error event is 529, which the agent loop waits out")
    void overloadedEventIsBusy() throws Exception {
        Throwable failure = streamAgainst("event: message_start\n"
                + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[],\"model\":\"claude-test\",\"usage\":{\"input_tokens\":3,\"output_tokens\":0}}}\n\n"
                + "event: error\n"
                + "data: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}\n\n");

        WebClientResponseException http = findCause(failure, WebClientResponseException.class);
        assertThat(http).as("%s", failure).isNotNull();
        assertThat(http.getStatusCode().value()).isEqualTo(529);
    }

    @Test
    @DisplayName("lines that are not an error event are not one, even when they mention an error")
    void otherLinesAreNotErrors() {
        HttpHeaders none = new HttpHeaders();
        assertThat(AnthropicStreamErrors.errorOf("event: error", none)).isNull();
        assertThat(AnthropicStreamErrors.errorOf(
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"error\":null}", none)).isNull();
        assertThat(AnthropicStreamErrors.errorOf("data: not json \"error\"", none)).isNull();
    }

    // Streams one call against a loopback server that answers 200 with this event stream.
    private Throwable streamAgainst(String events) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = events.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        AnthropicAdapter adapter = new AnthropicAdapter(new SimpleClientHttpRequestFactory(), WebClient.builder());
        ResolvedProvider provider = new ResolvedProvider(UUID.randomUUID(), "Stub", ProviderProtocol.ANTHROPIC,
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", Map.of(), false);
        ChatModel model = adapter.buildChatModel(provider, "claude-test", Tone.CONCISE, 1024);
        return catchThrowable(() -> model.stream(new Prompt(new UserMessage("hi"))).blockLast(Duration.ofSeconds(10)));
    }

    private static <T extends Throwable> T findCause(Throwable t, Class<T> type) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (type.isInstance(c)) return type.cast(c);
        }
        return null;
    }
}
