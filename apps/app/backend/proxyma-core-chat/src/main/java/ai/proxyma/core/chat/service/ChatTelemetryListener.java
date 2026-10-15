package ai.proxyma.core.chat.service;

import ai.proxyma.contracts.telemetry.TelemetryEventType;
import ai.proxyma.core.chat.event.ChatTurnCompletedEvent;
import ai.proxyma.core.telemetryclient.TelemetryShipper;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Counts completed chat turns for outbound telemetry.
 *
 * <p>A listener rather than a call inside ChatService because {@link ChatTurnCompletedEvent}
 * already exists, is published exactly once per genuinely completed turn, and is published
 * unconditionally - no Evaluation Mode gate, no skip for anonymous or desktop conversations, and
 * never from SmartChatMemory's condensation path. Nothing about a chat turn needed to be
 * measured that the event did not already mark, so measuring it anywhere else would have been a
 * second definition of "a turn" to keep in step with the first.
 *
 * <p>The event carries no identifying fields by construction and this listener adds none, so
 * there is nothing to strip: the record is the fact that a turn happened, plus the version and
 * tier TelemetryShipper stamps on everything. It is the event's only consumer, and whether
 * anything recorded here is kept or sent is TelemetryConsent's: never on Enterprise Server, and on
 * Desktop and the Home Server only with "Send diagnostics to Proxyma" on.
 */
@Service
class ChatTelemetryListener {

    private final TelemetryShipper telemetryShipper;

    ChatTelemetryListener(TelemetryShipper telemetryShipper) {
        this.telemetryShipper = telemetryShipper;
    }

    // Synchronous, like the other listeners on this event: record() is one counter increment in
    // memory. Nothing is sent from here - see TelemetryShipper and proxyma-core-diagnostics.
    @EventListener
    void onChatTurnCompleted(ChatTurnCompletedEvent event) {
        telemetryShipper.record(TelemetryEventType.CHAT, null);
    }
}
