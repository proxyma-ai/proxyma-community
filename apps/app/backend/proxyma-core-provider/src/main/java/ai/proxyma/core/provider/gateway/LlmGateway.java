package ai.proxyma.core.provider.gateway;

import ai.proxyma.core.common.usage.TokenUsage;
import ai.proxyma.core.settings.dto.ChatSettings;
import ai.proxyma.core.provider.usage.TokenUsageExtractor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.List;
import java.util.Optional;

/**
 * The one door to a model. Callers name WHO is calling - a {@link LlmCaller}: the service, as a
 * {@link LlmRole}, and the subject the call is billed to - and pass their data; which provider
 * serves it, over which protocol, with which capability, is resolved behind here. Every call that
 * spends money takes a caller, so every call is recorded against a service and a person.
 *
 * <p>WHAT THIS IS FOR. Services had begun to reason about providers. RerankingService asked
 * whether the configured instance had {@code nativeRerank} turned on and whether its
 * {@code protocol().maySupportNativeRerank()}, then built an embedding model straight off the
 * adapter when the answer was no. That is a search service holding an opinion about vLLM. The
 * question it actually has is "rank these candidates against this query"; everything else is the
 * provider layer's business.
 *
 * <p>The second reason is cost. A single entry point is the only structure in which "every model
 * call is accounted for" can be true by construction rather than by everyone remembering. Streamed
 * calls come through a session ({@link #open}, {@link LlmSession#stream}) and are recorded like any
 * other.
 */
public interface LlmGateway {

    /**
     * Whether a model is configured for this role at all. Several roles are optional - OCR falls
     * back to a non-vision engine, graph extraction is off by default, sub-agents can be disabled -
     * and their callers need to ask before building a prompt they cannot send.
     */
    boolean isAvailable(LlmRole role);

    /**
     * Whether a model can be built for an explicit provider and model - a workflow's own choice -
     * as opposed to the chat role's. A workflow naming an instance that was deleted is refused here,
     * in the same words a role with no model is; one whose instance works runs even while the chat
     * role is unset.
     */
    boolean isAvailable(ModelOverride override);

    /**
     * Which provider and model this role resolves to right now, without making a call.
     *
     * <p>For a caller that makes MANY calls and reports their cost once - the prompt-scoring
     * reranker sends one request per candidate and publishes a single summed figure - asking each
     * result for its binding would be asking the same question forty times.
     */
    Optional<ModelBinding> binding(LlmRole role);

    /** A resolved role: the provider and model serving it, and whether that is a dedicated one. */
    record ModelBinding(String provider, String model, boolean dedicated) {}

    /**
     * How much this role's model is taken to read at once: what Settings > Agents says where it
     * says anything, else what the provider states for the model the role resolves to, else
     * {@link ChatSettings#FALLBACK_CONTEXT_WINDOW}. Always positive. Meaningful for the agent and
     * its sub-agents, the two roles that manage a context of their own.
     */
    int contextWindow(LlmRole role);

    /**
     * A model resolved once, to be called repeatedly - see {@link LlmSession}. For an agent loop,
     * where resolving per iteration would rebuild an uncached model every time round.
     */
    LlmSession open(LlmCaller caller, SessionOptions options);

    /**
     * How a session's requests are shaped.
     *
     * <p>The two factories exist because two shapes are genuinely in use and the difference
     * matters. {@link #tuned} applies the role's tone and an output ceiling through the ADAPTER,
     * which is what knows which sampling parameters each protocol and model accepts - some refuse
     * topK, OpenAI's reasoning models and Anthropic's current ones refuse sampling altogether.
     * {@link #plain} asks for none of it - which is what condensation has always done, and
     * giving it a ceiling it never had could truncate a summary mid-sentence.
     *
     * <p>{@code cache} is the prompt-cache lifetime, and only a TUNED session carries one to the
     * adapter; null means the adapter's default (five minutes, where the protocol caches at all).
     */
    record SessionOptions(ChatSettings.Tone tone, Integer maxTokens, ModelOverride override,
                          ChatSettings.PromptCache cache) {

        /** Model and nothing else: no temperature, no ceiling. */
        public static SessionOptions plain() {
            return new SessionOptions(null, null, null, null);
        }

        /** The role's sampling settings and output ceiling, applied by the provider's adapter. */
        public static SessionOptions tuned(ChatSettings.Tone tone, int maxTokens) {
            return new SessionOptions(tone, maxTokens, null, null);
        }

        /** The same, against an explicitly chosen provider and model. */
        public SessionOptions on(ModelOverride override) {
            return new SessionOptions(tone, maxTokens, override, cache);
        }

        /** The same, with this prompt-cache lifetime. */
        public SessionOptions cached(ChatSettings.PromptCache lifetime) {
            return new SessionOptions(tone, maxTokens, override, lifetime);
        }
    }

    /** One completion, using whichever model is configured for the caller's role. */
    LlmResult chat(LlmCaller caller, List<Message> messages);

    /**
     * One completion against an explicitly named provider and model.
     *
     * <p>This is NOT the provider knowledge the gateway exists to remove. A workflow node stores
     * the model its author picked, so its caller holds a provider id and a model name as DATA and
     * passes them through. What is forbidden is a caller BRANCHING on which provider it got -
     * asking what a protocol supports, or building a model to work around an endpoint that is
     * missing. Passing configuration through is not the same as reasoning about it.
     */
    LlmResult chat(LlmCaller caller, List<Message> messages, ModelOverride override);

    /** An explicitly chosen provider instance and model name. */
    record ModelOverride(String providerId, String model) {}

    /**
     * A completion and the binding that served it.
     *
     * <p>The provider and model travel WITH the answer because every caller needs them and each
     * one used to work them out again from settings - SpaceFactService re-derived the whole
     * dedicated-or-main fallback a second time inside its spend recording, so a change to the
     * fallback would have had to be made in two places that do not look related.
     */
    record LlmResult(ChatResponse response, String provider, String model, boolean dedicatedModel) {

        /**
         * The reply text, or empty - never null, and never an NPE from an empty result.
         *
         * <p>The ANSWER only: a reasoning model's thinking is left out wherever its protocol put it
         * (see ModelThinking). This read the FIRST generation, which on a Claude model that thinks
         * is the thinking block - empty by default on the newest ones - so every title, rerank
         * score, query expansion and graph extraction built on this came back blank.
         */
        public String text() {
            return ModelThinking.answerText(response);
        }

        /** What the call cost in tokens, or EMPTY where the provider reported nothing. */
        public TokenUsage usage() {
            TokenUsage usage = TokenUsageExtractor.extract(response);
            return usage == null ? TokenUsage.EMPTY : usage;
        }
    }

    /**
     * An embedding model OTHER than the configured one - the previous provider and model, which a
     * search still has to query with while a model change is re-indexing - METERED against this
     * caller like any other call. It used to be built straight off the adapter, so every search
     * during a re-index embedded its query for free as far as any ledger could tell.
     *
     * @return null when that pairing cannot be built
     */
    org.springframework.ai.embedding.EmbeddingModel embeddingModel(LlmCaller caller, String providerId, String model);

    /**
     * Rank documents against a query using whatever ranking capability the role's provider
     * actually has - a native {@code /v1/rerank} endpoint, or embedding cosine similarity.
     *
     * <p>EMPTY MEANS "THIS PROVIDER CANNOT RANK", not "nothing was relevant". The caller is then
     * free to fall back to a strategy of its own; RerankingService prompts a chat model to score
     * each candidate, which is a search-domain prompt and deliberately stays in search. The line
     * this interface draws is provider CAPABILITIES in here, domain PROMPTS out there.
     *
     * <p>A provider that CAN rank and failed to is an exception, not empty: a reranker checkpoint
     * cannot answer the caller's prompts either, so there is nothing to fall back to but the order
     * the candidates came in.
     *
     * <p>No topK: every candidate is ranked and the CALLER truncates. Neither strategy behind this
     * can be asked for a partial ranking - the native endpoint scores what it is given and cosine
     * similarity is computed per document - so a topK here would be a parameter that reads as a
     * cost saving and is not one.
     */
    Optional<Ranking> rerank(LlmCaller caller, String query, List<String> documents);

    /** One document's placing: its index in the list that was passed in, and its score. */
    record Ranked(int index, double score) {}

    /**
     * A ranking plus the name of the capability that produced it. The strategy travels with the
     * result because it is not an implementation detail to the caller - Evaluation Mode records
     * which tier answered, and "the reranker is silently doing cosine similarity" is exactly the
     * kind of thing that needs to be visible rather than inferred from a score distribution.
     */
    record Ranking(List<Ranked> ranked, String strategy) {}
}
