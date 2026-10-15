package ai.proxyma.core.settings.event;

// Published when a provider's settings or credentials change.
// role: "chat" | "embedding" | "query-expansion" | "reranking" | "credentials"
// provider: the provider key (e.g. AiProvider.ANTHROPIC.value()) - only set when role="credentials"
public record ProviderSettingsChangedEvent(String role, String provider) {

    public static ProviderSettingsChangedEvent forRole(String role) {
        return new ProviderSettingsChangedEvent(role, null);
    }

    public static ProviderSettingsChangedEvent forCredentials(String provider) {
        return new ProviderSettingsChangedEvent("credentials", provider);
    }
}
