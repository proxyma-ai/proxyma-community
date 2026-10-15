package ai.proxyma.core.settings.provider;

/**
 * Which of its APIs a gateway is spoken to through - asked per instance, because one gateway serves
 * both and the choice changes what reaches the model. LiteLLM is the gateway this exists for: its
 * {@code /v1/messages} keeps Claude's own tool format and prompt-cache breakpoints, its
 * {@code /v1/chat/completions} is what every other model it routes to is built for.
 *
 * <p>Embeddings and the model list are always the OpenAI ones - Anthropic's API has neither an
 * embedding endpoint nor anything LiteLLM would answer differently.
 */
public enum GatewayApi {

    ANTHROPIC_MESSAGES("Anthropic Messages"),
    OPENAI_CHAT("OpenAI Chat Completions");

    private final String displayName;

    GatewayApi(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() { return displayName; }

    /** Parses the stored/wire value, or null for anything this build does not know. */
    public static GatewayApi fromValue(String value) {
        if (value == null || value.isBlank()) return null;
        for (GatewayApi a : values()) {
            if (a.name().equalsIgnoreCase(value.trim())) return a;
        }
        return null;
    }
}
