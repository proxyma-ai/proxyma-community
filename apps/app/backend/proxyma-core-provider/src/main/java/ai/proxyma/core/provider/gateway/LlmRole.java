package ai.proxyma.core.provider.gateway;

/**
 * What a model call is FOR. The one vocabulary the rest of the product uses to ask for an LLM.
 *
 * <p>This already existed, spelled as method names on {@link
 * ai.proxyma.core.provider.service.LlmProviderFactory} - {@code getRerankingChatModel()},
 * {@code getOcrChatModel()}, {@code getGraphChatModel()} and so on. Naming it makes two things
 * possible that a getter per role cannot:
 *
 * <ul>
 *   <li>A caller can say what it wants without holding a provider-built model, which is what let
 *       services grow branches on {@code protocol()} and {@code nativeRerank} - a search service
 *       deciding what a vLLM endpoint supports is the coupling this enum exists to remove.</li>
 *   <li>Every call arrives at one place carrying its own purpose, so cost can be attributed BY
 *       role without each call site remembering to report it. Native rerank, embedding-cosine
 *       rerank and workflow nodes once went unrecorded; they record now because they come through
 *       the one door, not because their call sites remember.</li>
 * </ul>
 *
 * <p>Roles are stable identifiers: they become a stored dimension on the spend ledger, so renaming
 * one rewrites what history means. Add rather than repurpose.
 */
public enum LlmRole {

    /** The main chat completion - the answer a person is waiting for. */
    CHAT,

    /** Delegated work inside a turn, via SubAgentService. */
    SUB_AGENT,

    /** Naming a conversation from its first exchange. */
    TITLE,

    /** Summarising older turns when the context window fills, via SmartChatMemory. */
    CONDENSATION,

    /** Rewriting or expanding a search query before retrieval. */
    QUERY_EXPANSION,

    /** Scoring retrieved candidates for relevance, by whichever strategy the provider supports. */
    RERANK,

    /** Vectorising chunks at ingest and queries at search time. Highest call volume in the product. */
    EMBEDDING,

    /** Vision-model text extraction from scanned pages during ingest. */
    OCR,

    /** Knowledge-graph entity and relation extraction, one call per chunk of an opted-in connector. */
    GRAPH,

    /** The astronomy fact under the chat hero. */
    SPACE_FACT,

    /** An LLM node inside a workflow run. Inert while workflows are gated off. */
    WORKFLOW,

    /**
     * Classifying a user message or an answer against the configured content policy.
     *
     * <p>Its own role rather than a borrowed one, unlike TITLE and SPACE_FACT which ride other
     * roles' models. Two reasons, and both are about cost being visible. This role fires up to
     * TWICE PER TURN on every turn, so folding it into another role's spend would make the
     * deployment's largest new line item invisible inside somebody else's. And a guardrail is the
     * one role an operator may want on a different model entirely - a small, cheap, fast
     * classifier while chat runs on something expensive.
     *
     * <p>NO FALLBACK to main chat, like OCR and GRAPH: unconfigured means off, because falling
     * back would silently double the cost of every message in the product.
     */
    GUARDRAIL,
}
