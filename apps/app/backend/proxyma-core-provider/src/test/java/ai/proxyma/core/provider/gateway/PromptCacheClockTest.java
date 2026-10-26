package ai.proxyma.core.provider.gateway;

import ai.proxyma.core.common.usage.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The prompt-cache clock (the maintainer's proposal, 2026-10-22): a write starts a timer with its
 * lifetime, a read restarts it, and each later call is read against it - a hit, a first write, an
 * expiry, a changed prompt, or a provider that did not keep what it wrote. And the one-hour check:
 * where a response does not say what lifetime it wrote, the next call on the same prompt settles it.
 */
class PromptCacheClockTest {

    private static final Instant T0 = Instant.parse("2026-10-22T10:00:00Z");
    private static final String LINE = "conversation-1";

    private final PromptCacheClock clock = new PromptCacheClock();

    private static TokenUsage wrote(long tokens) {
        return new TokenUsage(10, 5, 0, tokens, 10 + tokens);
    }

    private static TokenUsage read(long tokens) {
        return new TokenUsage(10, 5, tokens, 0, 10 + tokens);
    }

    // A conversation: one system prompt, and the messages so far.
    private static PromptFingerprint prompt(String system, String... turns) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(system));
        for (int i = 0; i < turns.length; i++) {
            messages.add(i % 2 == 0 ? new UserMessage(turns[i]) : new AssistantMessage(turns[i]));
        }
        return PromptFingerprint.of(messages, List.of());
    }

    private PromptCacheClock.Observation call(PromptFingerprint p, Instant at, TokenUsage usage, boolean longAsked) {
        return clock.observe(LINE, "Anthropic", "claude", p, at, usage, longAsked);
    }

    @Test
    @DisplayName("a write starts the timer with its lifetime, and a read restarts it")
    void writeThenRead() {
        PromptCacheClock.Observation first = call(prompt("sys", "hi"), T0, wrote(2_000), false);
        assertThat(first.outcome()).isEqualTo(PromptCacheClock.Outcome.FIRST);
        assertThat(first.warmUntil()).isEqualTo(T0.plus(Duration.ofMinutes(5)));

        Instant later = T0.plus(Duration.ofMinutes(3));
        PromptCacheClock.Observation hit = call(prompt("sys", "hi", "hello", "again"), later, read(2_000), false);
        assertThat(hit.outcome()).isEqualTo(PromptCacheClock.Outcome.HIT);
        assertThat(hit.warmUntil()).isEqualTo(later.plus(Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("a write after the lifetime ran out is an expiry")
    void expired() {
        call(prompt("sys", "hi"), T0, wrote(2_000), false);
        PromptCacheClock.Observation again = call(prompt("sys", "hi", "ok", "next"), T0.plus(Duration.ofMinutes(7)),
                wrote(2_100), false);
        assertThat(again.outcome()).isEqualTo(PromptCacheClock.Outcome.EXPIRED);
    }

    @Test
    @DisplayName("a write while the cache was warm is a changed prompt - and the change is named")
    void changedPrompt() {
        call(prompt("sys", "hi"), T0, wrote(2_000), false);
        assertThat(call(prompt("sys v2", "hi", "ok", "next"), T0.plus(Duration.ofMinutes(1)), wrote(2_000), false).outcome())
                .isEqualTo(PromptCacheClock.Outcome.CHANGED_SYSTEM);
        assertThat(call(prompt("sys v2", "summary of earlier turns", "ok"), T0.plus(Duration.ofMinutes(2)), wrote(800), false)
                .outcome()).isEqualTo(PromptCacheClock.Outcome.CHANGED_MESSAGES);

        PromptFingerprint withTool = PromptFingerprint.of(List.of(new SystemMessage("sys v2"),
                new UserMessage("summary of earlier turns"), new AssistantMessage("ok")),
                List.of(org.springframework.ai.tool.function.FunctionToolCallback
                        .builder("recall", (String in) -> "x").inputType(String.class).description("Recall").build()));
        assertThat(call(withTool, T0.plus(Duration.ofMinutes(3)), wrote(900), false).outcome())
                .isEqualTo(PromptCacheClock.Outcome.CHANGED_TOOLS);
    }

    @Test
    @DisplayName("the same prompt within its lifetime and nothing read: the provider did not keep it")
    void notKept() {
        call(prompt("sys", "hi"), T0, wrote(2_000), false);
        assertThat(call(prompt("sys", "hi", "ok", "next"), T0.plus(Duration.ofMinutes(2)), wrote(2_100), false).outcome())
                .isEqualTo(PromptCacheClock.Outcome.NOT_KEPT);
    }

    @Test
    @DisplayName("an unstated one-hour write read after ten minutes proves the hour was kept")
    void hourConfirmed() {
        call(prompt("sys", "hi"), T0, wrote(2_000), true);
        PromptCacheClock.Observation hit = call(prompt("sys", "hi", "ok", "next"), T0.plus(Duration.ofMinutes(10)),
                read(2_000), true);
        assertThat(hit.outcome()).isEqualTo(PromptCacheClock.Outcome.HIT);
        assertThat(clock.longWritesKept("Anthropic")).isTrue();
        assertThat(hit.warmUntil()).isEqualTo(T0.plus(Duration.ofMinutes(10)).plus(Duration.ofHours(1)));
    }

    @Test
    @DisplayName("an unstated one-hour write missed after ten minutes on the same prompt: the hour was not kept, and is no longer priced")
    void hourRefused() {
        call(prompt("sys", "hi"), T0, wrote(2_000), true);
        PromptCacheClock.Observation miss = call(prompt("sys", "hi", "ok", "next"), T0.plus(Duration.ofMinutes(10)),
                wrote(2_100), true);
        assertThat(miss.outcome()).isEqualTo(PromptCacheClock.Outcome.NOT_KEPT);
        assertThat(clock.longWritesKept("Anthropic")).isFalse();
        // What it writes from now on is taken to live five minutes.
        assertThat(miss.warmUntil()).isEqualTo(T0.plus(Duration.ofMinutes(15)));
        // Another provider is not judged by it.
        assertThat(clock.longWritesKept("Other")).isTrue();
    }

    @Test
    @DisplayName("a lifetime the response states needs no check, and calls with no cache activity are not judged")
    void statedLifetimeAndNoActivity() {
        TokenUsage stated = new TokenUsage(10, 5, 0, 2_000, 2_010, 2_000, true);
        PromptCacheClock.Observation first = call(prompt("sys", "hi"), T0, stated, true);
        assertThat(first.warmUntil()).isEqualTo(T0.plus(Duration.ofHours(1)));
        call(prompt("sys", "hi", "ok", "next"), T0.plus(Duration.ofMinutes(10)), wrote(2_100), true);
        assertThat(clock.longWritesKept("Anthropic")).isTrue();

        assertThat(call(prompt("sys", "x"), T0, new TokenUsage(10, 5, 0, 0, 10), false))
                .isEqualTo(PromptCacheClock.Observation.NONE);
    }

    @Test
    @DisplayName("a conversation's cache, for the composer's timer: warm until the last write or read plus its lifetime, then gone")
    void stateForTheTimer() {
        assertThat(clock.state(LINE, T0)).isEmpty();
        call(prompt("sys", "hi"), T0, wrote(2_000), false);
        call(prompt("sys", "hi", "ok", "next"), T0.plus(Duration.ofMinutes(2)), read(2_000), false);

        assertThat(clock.state(LINE, T0.plus(Duration.ofMinutes(3)))).get().satisfies(s -> {
            assertThat(s.warmUntil()).isEqualTo(T0.plus(Duration.ofMinutes(7)));
            assertThat(s.lifetime()).isEqualTo(Duration.ofMinutes(5));
        });
        assertThat(clock.state(LINE, T0.plus(Duration.ofMinutes(8)))).isEmpty();
        // Another conversation's line is not this one's, though one is a prefix of the other's name.
        assertThat(clock.state("conversation-", T0.plus(Duration.ofMinutes(3)))).isEmpty();
    }

    @Test
    @DisplayName("a call reported after a later one - a failed attempt behind its retry - is not judged against it")
    void reportedOutOfOrder() {
        // The retry, started second, ends first and reads what the first attempt wrote.
        call(prompt("sys", "hi"), T0.plus(Duration.ofSeconds(2)), read(2_000), false);
        PromptCacheClock.Observation late = call(prompt("sys", "hi"), T0, wrote(2_000), false);
        assertThat(late.outcome()).isEqualTo(PromptCacheClock.Outcome.FIRST);
        // The line stayed on the retry.
        assertThat(call(prompt("sys", "hi", "ok", "next"), T0.plus(Duration.ofMinutes(1)), read(2_000), false).outcome())
                .isEqualTo(PromptCacheClock.Outcome.HIT);
    }

    @Test
    @DisplayName("a prompt is the previous one continued only when its tools, system prompt and messages all start the same")
    void fingerprint() {
        PromptFingerprint a = prompt("sys", "hi", "hello");
        assertThat(a.isPrefixOf(prompt("sys", "hi", "hello", "more"))).isTrue();
        assertThat(a.isPrefixOf(prompt("sys", "hi"))).isFalse();
        assertThat(a.firstDifference(prompt("sys", "HI", "hello"))).isEqualTo(PromptFingerprint.Part.MESSAGES);
        assertThat(a.firstDifference(prompt("other", "hi", "hello"))).isEqualTo(PromptFingerprint.Part.SYSTEM);
    }
}
