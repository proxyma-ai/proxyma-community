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
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A provider's rate limit is waited out, by the main agent and every sub-agent alike (0.29.1
 * feedback: with many sub-agents working, a rate limit is expected, not a failure).
 *
 * <p>What went wrong before: twelve attempts of at most eight seconds gave a busy provider about
 * eighty seconds, and a STREAMED Anthropic 429 was not retried at all - Spring AI raised it as a
 * bare RuntimeException that named the status only in its message.
 */
class RateLimitRetryTest {

    private static WebClientResponseException status(int code, HttpHeaders headers) {
        return WebClientResponseException.create(code, "Error", headers,
                "{\"type\":\"error\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private static WebClientResponseException status(int code) {
        return status(code, new HttpHeaders());
    }

    private static HttpHeaders header(String name, String value) {
        HttpHeaders h = new HttpHeaders();
        h.add(name, value);
        return h;
    }

    // -- What is a rate limit ---------------------------------------------------------------------

    @Test
    @DisplayName("a streamed Anthropic 429, raised as text by Spring AI, is a rate limit")
    void anthropicTextStatus() {
        RuntimeException e = new RuntimeException(
                "Response exception, Status: [429 TOO_MANY_REQUESTS], Body:[{\"type\":\"error\"}]");

        assertThat(ModelCall.isRateLimited(e)).isTrue();
        assertThat(ModelCall.isRetryable(e)).isTrue();
        assertThat(ModelCall.isConnectionFailure(e)).isFalse();
    }

    @Test
    @DisplayName("Spring AI's blocking 429 (NonTransientAiException) is a rate limit, wrapped or not")
    void blockingTextStatus() {
        NonTransientAiException e = new NonTransientAiException("429 TOO_MANY_REQUESTS - {\"error\":\"slow down\"}");

        assertThat(ModelCall.isRateLimited(e)).isTrue();
        assertThat(ModelCall.isRateLimited(new RuntimeException("Failed to generate content", e))).isTrue();
    }

    @Test
    @DisplayName("529 overloaded and 503 are waited out too, but are not called a rate limit")
    void busyButNotRateLimited() {
        assertThat(ModelCall.isBusy(status(529))).isTrue();
        assertThat(ModelCall.isBusy(status(503))).isTrue();
        assertThat(ModelCall.isRateLimited(status(529))).isFalse();
        assertThat(ModelCall.isBusy(new RuntimeException("Response exception, Status: [529 UNKNOWN], Body:[]")))
                .isTrue();
    }

    @Test
    @DisplayName("any other status fails at once, however it is raised")
    void otherStatusesAreNotRetried() {
        assertThat(ModelCall.isRetryable(status(400))).isFalse();
        assertThat(ModelCall.isRetryable(
                new RuntimeException("Response exception, Status: [400 BAD_REQUEST], Body:[]"))).isFalse();
        assertThat(ModelCall.isRetryable(new NonTransientAiException("401 UNAUTHORIZED - {}"))).isFalse();
        // A message that merely starts with digits, on an exception of some other kind, is not a status.
        assertThat(ModelCall.httpStatus(new IllegalStateException("429 apples"))).isZero();
    }

    // -- How long it waits ------------------------------------------------------------------------

    @Test
    @DisplayName("the provider's Retry-After is taken, in seconds or as a date, and capped at a minute")
    void retryAfterIsTaken() {
        Instant now = Instant.parse("2026-10-08T00:00:00Z");
        assertThat(ModelCall.retryAfter(status(429, header("Retry-After", "17")), now)).contains(Duration.ofSeconds(17));
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(40).atZone(ZoneOffset.UTC));
        assertThat(ModelCall.retryAfter(status(429, header("Retry-After", date)), now)).contains(Duration.ofSeconds(40));
        assertThat(ModelCall.retryAfter(status(429, header("retry-after-ms", "1500")), now))
                .contains(Duration.ofMillis(1500));
        assertThat(ModelCall.retryAfter(status(429), now)).isEmpty();

        // With no scatter (random 0), the wait is exactly what was asked, up to the cap.
        ModelCall.RetryBudget budget = new ModelCall.RetryBudget();
        assertThat(budget.next(status(429, header("Retry-After", "17")), 0, now)).contains(Duration.ofSeconds(17));
        assertThat(budget.next(status(429, header("Retry-After", "3600")), 0, now)).contains(ModelCall.RETRY_AFTER_MAX);
    }

    @Test
    @DisplayName("with no Retry-After the wait doubles from two seconds to thirty, scattered by up to half")
    void backoffDoublesToItsCeiling() {
        ModelCall.RetryBudget budget = new ModelCall.RetryBudget();
        List<Duration> waits = new ArrayList<>();
        for (int i = 0; i < 6; i++) waits.add(budget.next(status(429), 0.5, Instant.EPOCH).orElseThrow());

        assertThat(waits).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8),
                Duration.ofSeconds(16), Duration.ofSeconds(30), Duration.ofSeconds(30));
        assertThat(budget.attempts()).isEqualTo(6);

        ModelCall.RetryBudget low = new ModelCall.RetryBudget();
        ModelCall.RetryBudget high = new ModelCall.RetryBudget();
        assertThat(low.next(status(429), 0, Instant.EPOCH)).contains(Duration.ofSeconds(1));
        assertThat(high.next(status(429), 0.999, Instant.EPOCH).orElseThrow()).isBetween(
                Duration.ofMillis(2900), Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("a busy provider is waited on for ten minutes - not eighty seconds - and then the call fails")
    void tenMinutesOfPatience() {
        ModelCall.RetryBudget budget = new ModelCall.RetryBudget();
        int retries = 0;
        while (budget.next(status(429), 0.5, Instant.EPOCH).isPresent()) retries++;

        assertThat(budget.busyWaited()).isLessThanOrEqualTo(ModelCall.BUSY_PATIENCE)
                .isGreaterThan(ModelCall.BUSY_PATIENCE.minus(ModelCall.BUSY_MAX_DELAY));
        assertThat(retries).isGreaterThan(12);
    }

    @Test
    @DisplayName("a connection that cannot be made still gets two retries, not ten minutes")
    void connectionFailuresStayShort() {
        WebClientRequestException refused = new WebClientRequestException(new java.net.ConnectException("refused"),
                org.springframework.http.HttpMethod.POST, URI.create("https://api.example.com"), new HttpHeaders());
        ModelCall.RetryBudget budget = new ModelCall.RetryBudget();

        assertThat(budget.next(refused, 0.5, Instant.EPOCH)).isPresent();
        assertThat(budget.next(refused, 0.5, Instant.EPOCH)).isPresent();
        assertThat(budget.next(refused, 0.5, Instant.EPOCH)).isEmpty();
    }

    // -- A real call ------------------------------------------------------------------------------

    /** A provider that refuses {@code refusals} times with the given failure, then answers. */
    private static LlmSession provider(int refusals, RuntimeException refusal, AtomicInteger calls) {
        return new LlmSession() {
            @Override public String provider() { return "Fake"; }
            @Override public String model() { return "fake-chat"; }
            @Override public boolean dedicated() { return false; }
            @Override public LlmResult chat(List<Message> messages, List<ToolCallback> tools) {
                throw new UnsupportedOperationException();
            }
            @Override public Flux<ChatResponse> stream(List<Message> messages, List<ToolCallback> tools) {
                return Flux.defer(() -> calls.incrementAndGet() <= refusals
                        ? Flux.error(refusal)
                        : Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("done"))))));
            }
        };
    }

    @Test
    @DisplayName("a streamed call refused for its rate limit is retried, and the answer arrives")
    void retriedUntilItAnswers() {
        AtomicInteger calls = new AtomicInteger();
        List<Integer> retries = new ArrayList<>();
        RuntimeException refusal = status(429, header("retry-after-ms", "1"));

        ModelCall.Outcome outcome = ModelCall.run(provider(2, refusal, calls), true,
                List.of(new UserMessage("go")), List.of(), () -> false, new StreamingScrubber(List.of()),
                new ModelCall.Listener() {
                    @Override public void text(String text) {}
                    @Override public void retry(int attempt, Throwable failure) { retries.add(attempt); }
                });

        assertThat(outcome.stopped()).isFalse();
        assertThat(outcome.assistant().getText()).isEqualTo("done");
        assertThat(calls).hasValue(3);
        assertThat(retries).containsExactly(1, 2);
    }

    @Test
    @DisplayName("Stop ends a call that is waiting out a minute-long Retry-After within a moment")
    void stopEndsTheWait() {
        AtomicBoolean stopped = new AtomicBoolean();
        RuntimeException refusal = status(429, header("Retry-After", "60"));
        new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                // the test ends either way
            }
            stopped.set(true);
        }).start();

        long started = System.nanoTime();
        ModelCall.Outcome outcome = ModelCall.run(provider(Integer.MAX_VALUE, refusal, new AtomicInteger()), true,
                List.of(new UserMessage("go")), List.of(), stopped::get, new StreamingScrubber(List.of()),
                text -> {});

        assertThat(outcome.stopped()).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("a failure that is not worth waiting for is thrown at once, as the provider raised it")
    void permanentFailureThrows() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> ModelCall.run(provider(1, status(400), calls), true,
                List.of(new UserMessage("go")), List.of(), () -> false, new StreamingScrubber(List.of()), text -> {}))
                .isInstanceOf(WebClientResponseException.class);
        assertThat(calls).hasValue(1);
    }

    /**
     * chat-agent-07: the whole call was given ten minutes - exactly the busy patience - so the waits
     * of a sustained 429 ran into it before the budget was spent, and what was thrown was Reactor's
     * "Timeout on blocking read": the 429 lost, so no sub-agent could report RATE LIMITED.
     */
    @Test
    @DisplayName("a call that runs out of time while retrying a rate limit fails with the rate limit")
    void outOfTimeWhileRetryingIsTheRateLimit() {
        assertThat(ModelCall.CALL_LIMIT).isGreaterThan(ModelCall.BUSY_PATIENCE);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> ModelCall.run(
                provider(Integer.MAX_VALUE, status(429), new AtomicInteger()), true,
                List.of(new UserMessage("go")), List.of(), () -> false, new StreamingScrubber(List.of()),
                text -> {}, Duration.ofMillis(300)));

        assertThat(ModelCall.isRateLimited(thrown)).as("thrown: %s", thrown).isTrue();
        assertThat(SubAgentService.failureReport(thrown)).startsWith("RATE LIMITED");
    }

    // -- What a sub-agent that gave up says -------------------------------------------------------

    @Test
    @DisplayName("a sub-agent that gave up on a rate limit says so, in words the main agent can act on")
    void subAgentReportNamesTheRateLimit() {
        String report = SubAgentService.failureReport(new RuntimeException(
                "Response exception, Status: [429 TOO_MANY_REQUESTS], Body:[{\"type\":\"rate_limit_error\"}]"));

        assertThat(report).startsWith("RATE LIMITED").contains("fewer sub-agents").contains("429");
        assertThat(SubAgentService.failureReport(status(400))).doesNotContain("RATE LIMITED");
    }
}
