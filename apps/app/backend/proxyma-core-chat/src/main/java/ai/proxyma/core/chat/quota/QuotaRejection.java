package ai.proxyma.core.chat.quota;

import java.time.Instant;

// window is a short machine-readable label ("monthly" | "5h" | "weekly"); resetsAt is an
// approximation (see RollingWindowChatQuotaGate's own javadoc for why a continuously-rolling
// window doesn't have one exact reset instant); message is the human-readable sentence shown to
// the end user in the chat error bubble.
public record QuotaRejection(String window, Instant resetsAt, String message) {}
