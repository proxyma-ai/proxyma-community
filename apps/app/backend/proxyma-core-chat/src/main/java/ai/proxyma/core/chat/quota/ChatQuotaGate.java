package ai.proxyma.core.chat.quota;

import java.util.Optional;

// Cross-module extension point: proxyma-core-chat cannot depend on proxyma-core-auth (module
// dependency only goes auth -> chat), so this interface is defined here and implemented by
// RollingWindowChatQuotaGate in proxyma-core-auth (server profile only). ChatController takes
// Optional<ChatQuotaGate> - on proxyma-desktop (no proxyma-core-auth on the classpath at all)
// there is simply no bean, exactly like LlmSpendRecordedEvent having no listener there -
// desktop behavior is unaffected either way.
public interface ChatQuotaGate {
    Optional<QuotaRejection> checkAllowed(String userId);
}
