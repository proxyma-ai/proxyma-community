package ai.proxyma.core.settings.provider;

/**
 * The wire protocol a provider instance speaks - NOT the vendor serving it.
 *
 * <p><b>NATIVE PROTOCOLS FIRST, AND EXACTLY AS THEIR VENDOR DEFINES THEM</b> (the maintainer's rule,
 * 2026-10-13). OpenAI, Anthropic, Google Gemini, Azure OpenAI and Ollama are each spoken the way
 * the vendor's own API documents, and nothing in their adapters exists for a server that deviates
 * from it. A GATEWAY that customizes a native protocol - adds a field, reports usage its own way,
 * refuses a route - gets a protocol of its own ({@link #OPENROUTER}, {@link #LITELLM}), where its
 * deviations live. Everything else that speaks OpenAI's shape - a server somebody runs, or a
 * third-party service with no protocol here - is {@link #OPENAI_COMPATIBLE}, which carries what
 * those servers need.
 *
 * <p>That REVERSES the earlier rule, which kept this enum short ("it grows only when a genuinely
 * different request shape appears") and reached every gateway through a native protocol plus
 * per-instance switches. The switches multiplied - a cache instruction for OpenRouter, a reasoning
 * field rename, usage corrections for LiteLLM inside the Anthropic adapter - and every one of them
 * ran, or could be switched on, against the vendor itself. The 0.34.1 feedback (a LiteLLM gateway
 * refusing a model as an in-stream error) is where it was decided.
 *
 * <p><b>No per-role capability is declared here, and no dropdown filters on one.</b> Every
 * configured instance is offered for every role. Where an adapter cannot serve the role it is
 * asked for, the build call throws UnsupportedOperationException, which
 * ProviderConnectivityService classifies as `not_supported` and the role then reports as having no
 * model - naming the protocol and the reason. Hiding an option explains nothing, and a declared
 * capability is a second authority that drifts from the adapters.
 *
 * <p>Stored by NAME (ProviderInstance.protocol). A constant added here is refused by a table created
 * before it on Enterprise Server - see docs/architecture.html#enum-columns.
 */
public enum ProviderProtocol {

    /**
     * OpenAI's own {@code /v1/chat/completions} and {@code /v1/embeddings}, as OpenAI documents
     * them - api.openai.com unless a base URL says otherwise. NOT OpenAI's newer {@code /v1/responses}
     * API, which would be a constant of its own.
     */
    OPENAI("OpenAI"),

    /** Anthropic's {@code /v1/messages}. No embedding endpoint exists on this API at all, which makes
     *  AnthropicAdapter one that refuses buildEmbeddingModel. */
    ANTHROPIC("Anthropic"),

    /**
     * Google's Gemini API - `generateContent` for chat, `embedContent` for embeddings. Both are
     * reached with a plain API key; the Vertex AI mode of the same SDK, which authenticates with
     * Google Cloud credentials and a project id, is not offered.
     */
    GEMINI("Google Gemini"),

    /**
     * Azure OpenAI. Kept separate from {@link #OPENAI} because it genuinely is: the model name is a
     * deployment name embedded in the URL path, and every request carries an `api-version`. A base
     * URL alone cannot describe it.
     */
    AZURE_OPENAI("Azure OpenAI"),

    /** Ollama's own `/api/chat` and `/api/embed` - the only place it states a model's context
     *  window (`/api/show`), which is why it keeps a protocol of its own though it speaks OpenAI too. */
    OLLAMA("Ollama"),

    /**
     * OpenRouter: OpenAI's shape with OpenRouter's additions - a top-level {@code cache_control} that
     * caches Claude, Gemini and Qwen models (OpenAI's own API refuses the field), a {@code reasoning}
     * field for what a reasoning model thinks, and model limits under its own field names.
     */
    OPENROUTER("OpenRouter"),

    /**
     * LiteLLM's proxy, spoken through the API chosen per instance ({@link GatewayApi}): Anthropic's
     * Messages or OpenAI's Chat Completions, each with what LiteLLM does differently from the
     * vendor - model limits from its own {@code /model/info}, cached tokens counted outside
     * {@code prompt_tokens} on the OpenAI route, and, for a deployment that ignores stream=true,
     * one blocking call per step (ResolvedProvider.blockingCalls).
     */
    LITELLM("LiteLLM"),

    /**
     * Everything else that speaks OpenAI's {@code /v1/chat/completions}: the servers people run -
     * vLLM, llama.cpp's server, TGI, LM Studio, LocalAI - and the services with no protocol of their
     * own here (DeepSeek, Mistral, Groq, Together, Fireworks, xAI, Nvidia NIM). Each is a name and a
     * base URL. What they do differently from OpenAI lives here: no key required, `/v1/rerank`,
     * `reasoning` for `reasoning_content`, several tool calls in one stream delta, and model limits
     * under each server's own field.
     */
    OPENAI_COMPATIBLE("OpenAI-compatible");

    private final String displayName;

    ProviderProtocol(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() { return displayName; }

    /**
     * Whether an instance of this protocol may be asked to do a native cross-encoder rerank.
     *
     * Per-INSTANCE rather than per-protocol in practice: `/v1/rerank` is a vLLM (and Cohere)
     * extension that most OpenAI-compatible servers do not serve, so this only says the question
     * is worth asking. {@link ProviderInstance#isNativeRerank()} is what actually turns it on, and
     * RerankingService still falls back to embedding-cosine scoring when the call fails.
     */
    public boolean maySupportNativeRerank() {
        return this == OPENAI_COMPATIBLE;
    }

    /** Whether an instance chooses which API it is spoken to through - see {@link GatewayApi}. */
    public boolean choosesApi() {
        return this == LITELLM;
    }

    /**
     * Whether an instance may be told to make one blocking call per step instead of streaming. Only
     * a gateway needs it: LiteLLM's passthrough to some deployments answers stream=true with a single
     * JSON body once tools are present, which no stream parser can read. A native API streams.
     */
    public boolean mayCallWithoutStreaming() {
        return this == LITELLM;
    }

    /** Parses the stored/wire value, returning null rather than throwing - a settings row naming a
     *  protocol this build does not have (a downgrade, a hand-edited value) should disable that one
     *  instance, not fail every read of the table. */
    public static ProviderProtocol fromValue(String value) {
        if (value == null || value.isBlank()) return null;
        for (ProviderProtocol p : values()) {
            if (p.name().equalsIgnoreCase(value.trim())) return p;
        }
        return null;
    }
}
