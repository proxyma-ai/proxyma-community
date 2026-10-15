package ai.proxyma.it.app;

import ai.proxyma.it.harness.Chat;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the user sees when the model provider misbehaves.
 *
 * <h2>Why this class is worth more than the happy path</h2>
 *
 * {@code POST /api/v1/chat} returns 200 and a conversation id BEFORE the turn runs. Everything
 * that can go wrong therefore goes wrong after the response the client already got, which means
 * the only way a failure reaches the user at all is an event on the stream. A break in that path
 * does not produce an error - it produces a spinner that never stops, and the user waits, and then
 * reloads, and the turn is simply gone.
 *
 * <p>A real provider fails in these ways on its own schedule and never during a test. The fake one
 * fails on request, which is the only reason any of this is assertable.
 */
@DisplayName("Chat: when the provider fails")
class ChatFailureIT extends DesktopJourney {

    // A RATE LIMIT IS WAITED OUT, since 2026-10-08 (0.29.1 feedback): with many sub-agents on one
    // key a 429 is expected, and a turn that gave up after eighty seconds lost the job. The turn now
    // retries for up to ten minutes (ModelCall.BUSY_PATIENCE) - so these two tests replaced one
    // that waited for the 429 to come back as an error, which it now does only ten minutes later.
    // What has to stay true is the old test's point: never a SILENT stall. The user is told at once
    // that the turn is retrying, and Stop ends the wait.

    @Test
    @DisplayName("a rate limit is retried, and the answer arrives once the provider takes the call")
    void aRateLimitIsRetried() {
        // A reply, not a first message: a new conversation's title is a model call of its own,
        // made beside the turn, and it could take the scripted refusal instead. And no directive in
        // the first message - the fake reads directives from the whole history, so an earlier
        // "@@text" would answer the retry in place of the scripted reply (seen on the PC).
        String id = Chat.send(app, "Hello there").succeeded().conversationId();
        llm().clear();
        llm().script(Map.of("when", "RATE-LIMITED-ONCE", "status", 429),
                Map.of("when", "RATE-LIMITED-ONCE", "text", "answered after the wait"));

        Chat.Turn turn = Chat.reply(app, id, "RATE-LIMITED-ONCE - please answer");

        assertThat(turn.has("retry")).as("the user is told the turn is retrying. Saw: %s", turn.types()).isTrue();
        assertThat(turn.has("error")).as("a rate limit that clears is not a failure. Saw: %s", turn.types()).isFalse();
        assertThat(turn.text()).isEqualTo("answered after the wait");
    }

    @Test
    @DisplayName("a provider that keeps refusing is never a silent stall: the turn says it is retrying, and Stop ends it")
    void aRateLimitThatPersistsIsVisibleAndStoppable() {
        String id = api().post("/api/v1/chat", Map.of("message", "@@status 429", "mentions", List.of()))
                .ok().text("/conversationId");
        try (var events = api().openEvents("/api/v1/chat/events/" + id)) {
            events.await(e -> "retry".equals(e.path("type").asText()), Duration.ofSeconds(30),
                    "the first retry to be announced");
            api().post("/api/v1/chat/conversations/" + id + "/stop", Map.of()).ok();
            // Inside a wait of up to thirty seconds, so this is the stop ending the WAIT - not the
            // wait running out.
            events.await(e -> Set.of("done", "error").contains(e.path("type").asText()),
                    Duration.ofSeconds(15), "the stopped turn to end");
        }
    }

    @Test
    @DisplayName("a provider 500 ends the turn instead of hanging it")
    void aServerErrorEndsTheTurn() {
        Chat.Turn turn = Chat.send(app, "@@status 500");

        assertThat(turn.types()).as("the turn terminated").containsAnyOf("error", "done");
        assertThat(turn.has("error")).isTrue();
    }

    @Test
    @DisplayName("a stream that dies mid-answer is reported, not left half-written")
    void aDroppedStreamIsReported() {
        // The socket closes after the first token. A client that kept the partial text and called
        // it complete would show the user half an answer with no indication that anything went
        // wrong - which is worse than an error, because it looks like the model's real reply.
        Chat.Turn turn = Chat.send(app, "@@drop @@text this answer will never finish");

        assertThat(turn.has("error"))
                .as("a truncated stream is a failed turn. Saw: %s", turn.types())
                .isTrue();
    }

    @Test
    @DisplayName("an empty answer still terminates the turn")
    void anEmptyAnswerTerminates() {
        Chat.Turn turn = Chat.send(app, "@@empty");

        // A well-formed response with no content at all. Rare, and it is exactly the shape that
        // leaves a UI waiting: there is nothing to render and nothing obviously wrong.
        assertThat(turn.types()).containsAnyOf("done", "error");
    }

    @Test
    @DisplayName("the app recovers: the next turn works after a failed one")
    void theAppRecovers() {
        Chat.send(app, "@@status 500");
        Chat.Turn afterwards = Chat.send(app, "@@text back to normal").succeeded();

        // A failed turn must not poison the conversation, the sink or the provider client. The
        // sink in particular is replaced per turn precisely because a completed one permanently
        // rejects further emissions - so a stale reference here would break every LATER turn
        // rather than the one that failed.
        assertThat(afterwards.text()).isEqualTo("back to normal");
    }

    @Test
    @DisplayName("a turn sent with no model configured says so")
    void noModelConfigured() {
        app.clearSetting("chat", "model");
        try {
            Chat.Turn turn = Chat.send(app, "anything at all");
            assertThat(turn.types()).as("the turn ended rather than hanging")
                    .containsAnyOf("error", "done");
        } finally {
            app.setting("chat", "model", ai.proxyma.it.harness.FakeLlm.CHAT_MODEL);
        }
    }
    @Test
    @DisplayName("stopped while a tool is running, the conversation still sends: every tool call reaches the model with its result")
    void aTurnStoppedMidToolLeavesAValidHistory() {
        // A write tool pauses for the user's approval, which is a tool that is reliably still
        // running when Stop is pressed - the exact moment that used to leave the call without a
        // result in memory, so that the NEXT message was refused by the provider with a 400
        // ("tool_use ids were found without tool_result blocks immediately after").
        String id = api().post("/api/v1/chat", Map.of(
                "message", "Add the site. @@tool addSource {\"type\":\"webpage\",\"fields\":{\"seedUrl\":\"https://example.com/\",\"name\":\"Example\"}}",
                "mentions", List.of())).ok().text("/conversationId");
        try (var events = api().openEvents("/api/v1/chat/events/" + id)) {
            events.await(e -> Set.of("tool-approval-request", "tool-enable-request", "tool-start")
                    .contains(e.path("type").asText()), Duration.ofMinutes(1), "the tool to start waiting");
            api().post("/api/v1/chat/conversations/" + id + "/stop", Map.of()).ok();
            events.await(e -> Set.of("done", "error").contains(e.path("type").asText()),
                    Duration.ofMinutes(1), "the stopped turn to end");
        }

        llm().clear();
        Chat.Turn next = Chat.reply(app, id, "@@text carrying on").succeeded();
        assertThat(next.text()).isEqualTo("carrying on");

        // The fake accepts any history, so the pairing is read off what the product SENT - the
        // rule every real provider enforces: an assistant message's tool calls are answered, one
        // result per call id, before anything else comes.
        JsonNode messages = llm().lastChatRequest().path("body").path("messages");
        int calling = 0;
        for (int i = 0; i < messages.size(); i++) {
            JsonNode message = messages.get(i);
            if (!message.path("tool_calls").isArray() || message.path("tool_calls").isEmpty()) continue;
            calling++;
            Set<String> asked = new HashSet<>();
            message.path("tool_calls").forEach(c -> asked.add(c.path("id").asText()));
            Set<String> answered = new HashSet<>();
            int j = i + 1;
            while (j < messages.size() && "tool".equals(messages.get(j).path("role").asText())) {
                answered.add(messages.get(j).path("tool_call_id").asText());
                j++;
            }
            assertThat(answered).as("the results right after tool call message %d: %s", i, messages).isEqualTo(asked);
        }
        assertThat(calling).as("the stopped turn's tool call is in the history: %s", messages).isPositive();
    }
}
