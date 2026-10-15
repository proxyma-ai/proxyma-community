package ai.proxyma.core.settings.usage;

/**
 * The platform activities that can spend money on an LLM without a user's chat turn behind them.
 *
 * Deliberately NOT a free-form string. These names become the breakdown on the Usages page and the
 * grouping key of every cost query, and a typo in a string literal at one call site would quietly
 * create a second category that looks like a real one.
 *
 * Anything a user's turn caused - the completion itself, sub-agents, query expansion, reranking,
 * title generation, condensation - is charged to that user instead and never appears here. The
 * dividing line is whether a person was waiting on the answer.
 */
public enum PlatformService {

    /**
     * Vectorising document chunks at ingest and queries at search time. Called once per chunk and
     * once per search, so it is normally the largest platform cost by a wide margin.
     */
    EMBEDDING,

    /** Vision-model text extraction from scanned pages during ingest. */
    OCR,

    /**
     * Knowledge-graph construction: one LLM call per chunk of every document of every connector
     * that opted in, plus a gleaning round per chunk if configured.
     *
     * <p>Its own constant rather than folding into EMBEDDING, and that is the point of it: this is
     * the most expensive optional thing the product does at ingest, it is off by default, and the
     * only way anyone can judge whether it earns its keep is to see its number ALONE next to the
     * embedding it rides alongside. A combined figure would answer no question anybody has.
     */
    GRAPH_EXTRACTION,

    /**
     * RETIRED - nothing writes this any more. The memory digest that produced a per-user chat
     * greeting and topic suggestions was deleted once the hero stopped rendering them.
     *
     * The constant STAYS. `PlatformSpendRecord.service` is `@Enumerated(EnumType.STRING)`, so every
     * historical row on a deployment that ever ran a digest holds the literal text "MEMORY_DIGEST";
     * removing the constant turns each of those into an IllegalArgumentException the first time the
     * Usages page queries a range that includes them. The frontend's two label maps keep their
     * entry for the same reason - those rows are still real money that was spent.
     */
    MEMORY_DIGEST,

    /**
     * The astronomy fact under the chat hero, regenerated at most every 30 minutes and only while
     * someone is actually loading that page. One short call per deployment per window, so it should
     * sit near the bottom of the breakdown - a line item that grows is a sign the demand flag in
     * SpaceFactRefreshJob has stopped working, not that the feature got expensive.
     */
    SPACE_FACT,
}
