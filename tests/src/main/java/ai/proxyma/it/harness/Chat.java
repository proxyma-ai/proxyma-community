package ai.proxyma.it.harness;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;


/**
 * One chat turn, start to finish, with everything the stream carried.
 *
 * <h2>POST FIRST, THEN SUBSCRIBE - which is the opposite of the obvious order</h2>
 *
 * {@code POST /api/v1/chat} is fire-and-forget: it returns a conversation id immediately, having
 * dispatched the real work onto another thread, and every token, tool call, guardrail verdict,
 * usage update and error arrives later on {@code GET /api/v1/chat/events/{id}}. Every instinct
 * says to subscribe before publishing, or lose the start of the answer.
 *
 * <p><b>That is exactly wrong here, and it fails silently.</b> {@code ChatEventSink.ensureSink} -
 * called by the POST handler at the start of every turn - does {@code sinks.put}, installing a
 * FRESH sink and discarding whatever was there. A subscriber that connected first is left holding
 * the discarded one and receives nothing at all: no events, no error, just the fifteen-second
 * keepalive comments, until the test times out and blames the product. Measured, not reasoned
 * about - the first draft of this class had it backwards and did precisely that.
 *
 * <p>Posting first is safe because the sink is {@code Sinks.many().replay().all()}, unbounded and
 * scoped to one turn. A subscriber arriving after the turn has begun - or after it has already
 * ENDED - is replayed every event from the beginning. That is what makes the product survive a
 * backgrounded tab or a proxy dropping an idle connection, and it is what makes this order
 * correct rather than merely lucky.
 */
public final class Chat {

    private Chat() {}

    /**
     * The record of a completed turn.
     *
     * @param conversationId the id, for asking follow-up questions in the same conversation
     * @param events         every event the stream carried, in order
     * @param text           the assistant's answer as PERSISTED - what the user keeps and what
     *                       they see after a reload
     * @param streamedText   the answer as assembled from the streamed token events
     */
    public record Turn(String conversationId, List<JsonNode> events, String text, String streamedText) {

        public List<String> types() {
            return events.stream().map(e -> e.path("type").asText("")).toList();
        }

        /** Every tool the agent actually invoked, in call order. */
        public List<String> toolsCalled() {
            return events.stream()
                    .filter(e -> "tool-start".equals(e.path("type").asText()))
                    .map(e -> e.path("toolName").asText())
                    .toList();
        }

        public boolean has(String type) { return types().contains(type); }

        /** The first event of a type, for reading its payload. */
        public JsonNode first(String type) {
            return events.stream()
                    .filter(e -> type.equals(e.path("type").asText()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "no '" + type + "' event in this turn. Saw: " + types()));
        }

        /** Asserts the turn did not fail, with the error text when it did. */
        public Turn succeeded() {
            if (has("error")) {
                throw new AssertionError("the turn failed: " + first("error").path("data").asText()
                        + "\nEvents: " + types());
            }
            return this;
        }
    }

    /** Sends a message in a new conversation and waits for the turn to finish. */
    public static Turn send(ProxymaApp app, String message) {
        return send(app.api(), null, message, List.of());
    }

    /** Sends a follow-up in an existing conversation - for memory and multi-turn journeys. */
    public static Turn reply(ProxymaApp app, String conversationId, String message) {
        return send(app.api(), conversationId, message, List.of());
    }

    /**
     * Sends a message with {@code @mention}s - the ids of the sources the user picked, which is
     * how a user restricts a question to one source.
     */
    public static Turn sendMentioning(ProxymaApp app, String message, List<String> connectorIds) {
        return send(app.api(), null, message, connectorIds);
    }

    /**
     * The full form, taking the HTTP client explicitly so a server-mode test can send a turn AS a
     * particular signed-in user - that client carries their session cookie.
     *
     * @param conversationId null to start a new conversation and let the server mint the id
     */
    public static Turn send(Http client, String conversationId, String message, List<String> mentions) {
        Map<String, Object> request = new java.util.HashMap<>();
        if (conversationId != null) request.put("conversationId", conversationId);
        request.put("message", message);
        request.put("mentions", mentions);

        // POST first. See the class comment - subscribing first attaches to a sink the POST is
        // about to throw away, and the turn then completes into the void.
        String id = client.post("/api/v1/chat", request).ok().text("/conversationId");

        try (Http.EventStream stream = client.openEvents("/api/v1/chat/events/" + id)) {
            // Three minutes. The fake provider answers instantly, so this only ever expires when
            // the turn is genuinely stuck - and when it does, await() prints every event that DID
            // arrive, which is almost always enough to say where it stopped.
            //
            // 'error' ends a turn as surely as 'done' does, and waiting only for 'done' would
            // spend the full timeout on every failed turn and then report a timeout instead of
            // the error message the product already sent.
            stream.await(event -> {
                String type = event.path("type").asText();
                return "done".equals(type) || "error".equals(type);
            }, Duration.ofMinutes(3), "the turn to finish (done or error)");

            // A short settle after the terminal event: 'done' is published before the last token
            // frame has necessarily been flushed to this socket, and assembling the answer a
            // moment too early truncates it. Cheap, and it removes a genuine race rather than
            // papering over one - the alternative is asserting on a prefix.
            ManagedProcess.sleep(200);
            String streamed = stream.assembledText("text", "data");
            return new Turn(id, stream.events(), persistedAnswer(client, id), streamed);
        }
    }

    /**
     * The last assistant message as the server stored it.
     *
     * <p>Two readings of one answer, and a test should say which it means. This is what the user
     * keeps and what a reload shows; {@link Turn#streamedText()} is what the event stream
     * actually carried. An assertion about WHAT THE MODEL SAID wants this one - it does not
     * depend on how many frames the answer arrived in. An assertion about STREAMING wants the
     * other, and the chat journey has two of those.
     *
     * <p>They used to differ, which is why this method exists. A turn's text was occasionally
     * absent from the stream while sitting correctly in the database - an empty assistant bubble
     * that a reload fixed - and this suite read it as a race around the moment a client
     * subscribes. It was not: the final text flush ran in {@code doFinally}, after
     * {@code block()} had already returned, so the guardrail scrubber was drained before it was
     * given the answer and no text event was ever published. Fixed 2026-09-23;
     * {@code ChatJourneyIT.aChunkedAnswerIsNotLost} pins it.
     */
    private static String persistedAnswer(Http client, String conversationId) {
        JsonNode detail = client.get("/api/v1/chat/conversations/" + conversationId).json();
        String answer = "";
        for (JsonNode message : detail.path("messages")) {
            if ("ASSISTANT".equals(message.path("role").asText())) {
                answer = message.path("content").asText("");
            }
        }
        return answer;
    }
}
