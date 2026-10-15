package ai.proxyma.core.chat.service;

import ai.proxyma.core.guardrail.StreamingScrubber;
import ai.proxyma.core.provider.gateway.LlmGateway.LlmResult;
import ai.proxyma.core.provider.gateway.LlmSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * telemetry-dff32853 (0.35.0 feedback, a LiteLLM gateway on the Anthropic API): three sub-agents
 * died on "500 api_error - litellm.MidStreamFallbackError: litellm.APIConnectionError" - an upstream
 * connection dropped in the middle of an answer, reported as an error event inside the stream after
 * its 200. Only 429, 503 and 529 were retried, so a dropped connection failed the call on its first
 * attempt.
 *
 * <p>The failures below are the exception AnthropicStreamErrors raises for such an event: a
 * WebClientResponseException carrying the status the error type stands for and the event's data.
 */
class TransientServerErrorTest {

    private static WebClientResponseException streamError(int status, String type, String message) {
        String data = "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"" + message + "\"}}";
        return new WebClientResponseException(status + " " + type + " - " + message, status, type, new HttpHeaders(),
                data.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private static final WebClientResponseException DROPPED = streamError(500, "api_error",
            "litellm.MidStreamFallbackError: litellm.APIConnectionError: AnthropicException - ");

    private static ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    // A stream that answers with `firstWords`, then fails with `failure`, `failures` times - and
    // then answers whole.
    private static LlmSession provider(int failures, RuntimeException failure, AtomicInteger calls) {
        return new LlmSession() {
            @Override public String provider() { return "LiteLLM"; }
            @Override public String model() { return "claude"; }
            @Override public boolean dedicated() { return false; }
            @Override public LlmResult chat(List<Message> messages, List<ToolCallback> tools) {
                throw new UnsupportedOperationException();
            }
            @Override public Flux<ChatResponse> stream(List<Message> messages, List<ToolCallback> tools) {
                return Flux.defer(() -> calls.incrementAndGet() <= failures
                        ? Flux.just(chunk("The first words of an ")).concatWith(Flux.error(failure))
                        : Flux.just(chunk("The whole "), chunk("answer.")));
            }
        };
    }

    @Test
    @DisplayName("a stream that errors part way once is asked again, and the answer stored is the retry's alone")
    void droppedMidStreamIsRetried() {
        AtomicInteger calls = new AtomicInteger();
        List<Integer> retries = new ArrayList<>();

        ModelCall.Outcome outcome = ModelCall.run(provider(1, DROPPED, calls), true,
                List.of(new UserMessage("go")), List.of(), () -> false, new StreamingScrubber(List.of()),
                new ModelCall.Listener() {
                    @Override public void text(String text) {}
                    @Override public void retry(int attempt, Throwable failure) { retries.add(attempt); }
                });

        assertThat(calls).hasValue(2);
        assertThat(retries).containsExactly(1);
        // Not "The first words of an The whole answer." - the failed attempt's chunks go with it.
        assertThat(outcome.assistant().getText()).isEqualTo("The whole answer.");
    }

    @Test
    @DisplayName("what is transient: a 500 that names a connection, fallback, overload or timeout, a 502, a 504")
    void whatIsTransient() {
        assertThat(ModelCall.isTransientServerError(DROPPED)).isTrue();
        assertThat(ModelCall.isTransientServerError(streamError(500, "api_error", "upstream connection reset"))).isTrue();
        assertThat(ModelCall.isTransientServerError(streamError(500, "api_error", "Overloaded"))).isTrue();
        assertThat(ModelCall.isTransientServerError(streamError(504, "timeout_error", ""))).isTrue();
        assertThat(ModelCall.isTransientServerError(WebClientResponseException.create(502, "Bad Gateway",
                new HttpHeaders(), new byte[0], StandardCharsets.UTF_8))).isTrue();
        // A server fault that says nothing transient is asked about once.
        assertThat(ModelCall.isTransientServerError(streamError(500, "api_error", "Internal server error"))).isFalse();
        assertThat(ModelCall.isTransientServerError(streamError(400, "invalid_request_error", "connection field invalid")))
                .isFalse();
    }

    @Test
    @DisplayName("a 500 that does not say it was transient fails at once, as the provider raised it")
    void permanentServerErrorIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        WebClientResponseException permanent = streamError(500, "api_error", "Internal server error");

        assertThatThrownBy(() -> ModelCall.run(provider(5, permanent, calls), true, List.of(new UserMessage("go")),
                List.of(), () -> false, new StreamingScrubber(List.of()), text -> {}))
                .isSameAs(permanent);
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("a transient server error gets three quick retries of its own - not the ten minutes a busy provider gets")
    void threeQuickRetries() {
        ModelCall.RetryBudget budget = new ModelCall.RetryBudget();
        List<java.time.Duration> waits = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            budget.next(DROPPED, 0.5, Instant.EPOCH).ifPresent(waits::add);
        }
        assertThat(waits).hasSize(ModelCall.MAX_SERVER_RETRIES);
        assertThat(waits.get(0)).isEqualTo(ModelCall.SERVER_FIRST_DELAY);
        assertThat(waits.get(1)).isEqualTo(ModelCall.SERVER_FIRST_DELAY.multipliedBy(2));
        // A busy provider's patience is untouched by them.
        assertThat(budget.next(WebClientResponseException.create(429, "Too Many Requests", new HttpHeaders(),
                new byte[0], StandardCharsets.UTF_8), 0.5, Instant.EPOCH)).isPresent();
    }
}
