package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.event.ChatEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AN EVENT DROPPED FOR WANT OF A SINK STILL TAKES ITS NUMBER (ChatEventSink, since 2026-11-03).
 *
 * <p>A Constellations lead's tellPerson writes its words into the person's lead chat with no turn
 * (ChatService.writeNote) and publishes an agent-mail event - into a conversation nobody follows yet, so
 * it is dropped. The drop used to leave the ordering where it was, so every later event of the
 * conversation waited behind a number that never came: the person's next turn reached the dock with
 * nothing at all, and its "Waiting for ..." line counted on for minutes over an answer the transcript
 * already held. And the turn the server starts for a teammate's reply goes to the sink the page already
 * follows (ChatService.wake keeps it).
 */
class DroppedEventNumberTest {

    private static final String CONVERSATION = "conv-lead-chat";

    private static void publish(ChatEventSink sink, long seq, ChatEvent.Type type) {
        sink.onChatEvent(new ChatEvent(CONVERSATION, seq, type.value(), null, null, "x"));
    }

    private static List<Long> replayed(ChatEventSink sink, int count) {
        return sink.subscribe(CONVERSATION).take(count).map(ChatEvent::sequence).collectList()
                .block(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("an event dropped with no sink does not hold back the next turn's events")
    void aDroppedEventDoesNotStallTheNextTurn() {
        ChatEventSink sink = new ChatEventSink();
        // tellPerson's mail, into a conversation no page follows: dropped, with its number.
        publish(sink, sink.nextSequence(CONVERSATION), ChatEvent.Type.AGENT_MAIL);

        // The person's turn.
        sink.startTurn(CONVERSATION, false);
        long text = sink.nextSequence(CONVERSATION);
        long done = sink.nextSequence(CONVERSATION);
        publish(sink, text, ChatEvent.Type.TEXT);
        publish(sink, done, ChatEvent.Type.DONE);

        assertThat(replayed(sink, 2)).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("dropped events arriving out of order are all passed over, and the turn after them is delivered")
    void droppedOutOfOrder() {
        ChatEventSink sink = new ChatEventSink();
        long first = sink.nextSequence(CONVERSATION);
        long second = sink.nextSequence(CONVERSATION);
        publish(sink, second, ChatEvent.Type.AGENT_MAIL);
        publish(sink, first, ChatEvent.Type.AGENT_MAIL);

        sink.startTurn(CONVERSATION, false);
        publish(sink, sink.nextSequence(CONVERSATION), ChatEvent.Type.TEXT);

        assertThat(replayed(sink, 1)).containsExactly(2L);
    }

    @Test
    @DisplayName("the turn the server starts for a teammate's reply reaches the page still following the first")
    void theFollowUpTurnReachesTheFollower() {
        ChatEventSink sink = new ChatEventSink();
        publish(sink, sink.nextSequence(CONVERSATION), ChatEvent.Type.AGENT_MAIL);
        sink.startTurn(CONVERSATION, false);
        List<ChatEvent> seen = new CopyOnWriteArrayList<>();
        Disposable page = sink.subscribe(CONVERSATION).subscribe(seen::add);
        try {
            publish(sink, sink.nextSequence(CONVERSATION), ChatEvent.Type.TEXT);
            publish(sink, sink.nextSequence(CONVERSATION), ChatEvent.Type.DONE);

            // The reply came in: ChatService.wake keeps the sink and announces the turn.
            sink.startTurn(CONVERSATION, true);
            publish(sink, sink.nextSequence(CONVERSATION), ChatEvent.Type.TURN_START);
            publish(sink, sink.nextSequence(CONVERSATION), ChatEvent.Type.TEXT);
            publish(sink, sink.nextSequence(CONVERSATION), ChatEvent.Type.DONE);

            // Emitted on the publishing thread here, so the page has every one by now.
            assertThat(seen).extracting(ChatEvent::streamType).containsExactly(
                    ChatEvent.Type.TEXT.value(), ChatEvent.Type.DONE.value(),
                    ChatEvent.Type.TURN_START.value(), ChatEvent.Type.TEXT.value(), ChatEvent.Type.DONE.value());
        } finally {
            page.dispose();
        }
    }
}
