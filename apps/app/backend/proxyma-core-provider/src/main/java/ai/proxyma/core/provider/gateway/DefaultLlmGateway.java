package ai.proxyma.core.provider.gateway;

import ai.proxyma.core.settings.usage.CostCenterService;
import ai.proxyma.core.settings.usage.GlobalQuotaGate;
import ai.proxyma.core.settings.usage.UserBudgetExceededException;
import ai.proxyma.core.settings.usage.UserBudgetGate;
import ai.proxyma.core.common.concurrent.BlockingWork;
import ai.proxyma.core.common.diagnostics.DiagCounter;
import ai.proxyma.core.common.diagnostics.DiagOp;
import ai.proxyma.core.common.diagnostics.Diagnostics;
import ai.proxyma.core.common.diagnostics.ErrorCategory;
import ai.proxyma.core.common.spend.SpendSubject;
import ai.proxyma.core.common.usage.TokenUsage;
import ai.proxyma.core.provider.adapter.LlmProviderAdapter;
import ai.proxyma.core.provider.usage.TokenUsageExtractor;
import ai.proxyma.core.provider.usage.WireUsage;
import ai.proxyma.core.provider.service.LlmProviderFactory;
import ai.proxyma.core.settings.dto.ChatSettings;
import ai.proxyma.core.settings.dto.GraphSettings;
import ai.proxyma.core.settings.dto.GuardrailSettings;
import ai.proxyma.core.settings.dto.OcrSettings;
import ai.proxyma.core.settings.dto.QueryExpansionSettings;
import ai.proxyma.core.settings.dto.RerankingSettings;
import ai.proxyma.core.settings.dto.SubAgentSettings;
import ai.proxyma.core.settings.provider.ResolvedProvider;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.settings.usage.AttributedSpendService;
import ai.proxyma.core.settings.usage.LlmCallLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Resolves a {@link LlmRole} to a provider and drives it.
 *
 * <p>Everything that used to make a caller think about providers lives here now: which instance
 * serves a role, whether that instance has a native rerank endpoint, and what to do when it does
 * not.
 */
@Service
public class DefaultLlmGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(DefaultLlmGateway.class);


    private final LlmProviderFactory providerFactory;
    private final SettingsService settingsService;
    private final AttributedSpendService attributedSpend;
    private final LlmCallLogService callLog;
    private final ModelPricingService pricing;

    // The configured embedding model is not reached through here: every vector store calls the
    // metered @Primary bean (EmbeddingModelConfig) directly. Only the embedding paths that build a
    // model of their own come through the gateway - see embeddingModel().
    public DefaultLlmGateway(LlmProviderFactory providerFactory,
                             SettingsService settingsService,
                             AttributedSpendService attributedSpend,
                             LlmCallLogService callLog,
                             ModelPricingService pricing) {
        this.providerFactory = providerFactory;
        this.settingsService = settingsService;
        this.attributedSpend = attributedSpend;
        this.callLog = callLog;
        this.pricing = pricing;
    }

    // THE MONTHLY SPENDING LIMIT IS CHECKED HERE, since 2026-09-29 - before every chat call, every
    // session call and stream, every metered embedding and every rerank. It was checked at each call
    // site instead, on the grounds that no one choke point existed; the gateway is that choke point
    // now, and the call-site version had missed the guardrail classifier, sub-agents started over
    // MCP, "Condense now", search over MCP and REST, both non-LLM rerank tiers and re-index
    // embeddings. Call sites that already check keep doing so for their own wording; checking twice
    // costs one grouped SUM over the month's llm_calls.
    //
    // Setter-injected and optional: the gateway is built by hand in tests with no quota at all.
    private GlobalQuotaGate budget;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setBudget(GlobalQuotaGate budget) {
        this.budget = budget;
    }

    // THE PER-USER QUOTA IS CHECKED HERE TOO, since 2026-09-29, for a service charged to the user
    // (CostCenterSettings) - on every path it runs through, not only a chat message sent from the
    // page. A service charged to the platform never touches it. Both optional: desktop has no
    // per-user quota, and tests build the gateway by hand.
    private UserBudgetGate userBudget;
    private CostCenterService costCenters;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setUserBudget(UserBudgetGate userBudget) {
        this.userBudget = userBudget;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCostCenters(CostCenterService costCenters) {
        this.costCenters = costCenters;
    }

    private void checkBudget(LlmRole role, SpendSubject subject) {
        if (budget != null) budget.checkOrRefuse(activity(role));
        UserQuota.check(userBudget, costCenters, role, subject);
    }

    // WHERE A STREAM'S SPEND IS WRITTEN: off the thread that ended the stream. That thread is a
    // Reactor Netty event loop, shared with the WebFlux server, and recordSpend reads the price and
    // makes two transactional writes - one behind a global monitor. Every finished turn and
    // sub-agent step blocked an event loop for them (backend-maven.md: a repository call inside a
    // reactive chain stalls every concurrent request). Package-visible so a test can run it inline.
    private java.util.concurrent.Executor spendRecorder = BlockingWork.executor();

    void setSpendRecorder(java.util.concurrent.Executor spendRecorder) {
        this.spendRecorder = spendRecorder;
    }

    // Completes "... so {activity} was not run" (GlobalQuotaExceededException), naming the work.
    private static String activity(LlmRole role) {
        return "this " + role.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + " model call";
    }

    @Override
    public boolean isAvailable(LlmRole role) {
        return bindingFor(role) != null;
    }

    // Built and dropped: an override's model is uncached by design, and this is asked once per run.
    @Override
    public boolean isAvailable(ModelOverride override) {
        return override == null ? isAvailable(LlmRole.CHAT) : explicitBinding(override) != null;
    }

    @Override
    public Optional<ModelBinding> binding(LlmRole role) {
        Binding b = bindingFor(role);
        return b == null ? Optional.empty()
                : Optional.of(new ModelBinding(b.provider(), b.modelName(), b.dedicated()));
    }

    @Override
    public LlmSession open(LlmCaller caller, SessionOptions options) {
        LlmRole role = caller.service();
        Binding binding = options.override() != null
                ? explicitBinding(options.override())
                : bindingFor(role);
        if (binding == null) {
            throw new IllegalStateException("No model is configured for role " + role
                    + " - callers that can run without one must ask isAvailable() first.");
        }
        // One-hour caching reaches the adapter only on a TUNED session - see buildOptions.
        boolean longCache = options.tone() != null && options.maxTokens() != null
                && options.cache() == ChatSettings.PromptCache.ONE_HOUR;
        boolean streams = providerFactory.resolveProvider(binding.providerId())
                .map(ResolvedProvider::streams).orElse(true);
        return new AdapterSession(role, binding, buildOptions(binding, options), caller.subject(), longCache,
                streams);
    }

    /**
     * The per-request options a session reuses.
     *
     * <p>Built through the ADAPTER when tuned, because whether topK may be forwarded is a
     * protocol fact: OpenAI-compatible and Azure have no setter for it and fail Spring AI's
     * runtime-options merge with NotWritablePropertyException. That is exactly the knowledge a
     * caller should never have had to hold.
     */
    private ToolCallingChatOptions buildOptions(Binding binding, SessionOptions options) {
        if (options.tone() == null || options.maxTokens() == null) {
            return ToolCallingChatOptions.builder()
                    .model(binding.modelName())
                    .internalToolExecutionEnabled(false)
                    .build();
        }
        LlmProviderAdapter adapter = adapterFor(binding);
        // A ceiling nobody set is the model's own, where its provider states one.
        int ceiling = providerFactory.outputCeiling(binding.providerId(), binding.modelName(), options.maxTokens());
        // The instance, for a protocol whose request depends on it (LiteLLM's chosen API).
        ResolvedProvider provider = providerFactory.resolveProvider(binding.providerId()).orElse(null);
        return adapter.buildChatOptions(provider, binding.modelName(), options.tone(), ceiling, List.of(),
                options.cache());
    }

    @Override
    public int contextWindow(LlmRole role) {
        ChatSettings chat = settingsService.getSettings(ChatSettings.PREFIX, ChatSettings.class);
        int set = role == LlmRole.SUB_AGENT
                ? settingsService.getSettings(SubAgentSettings.PREFIX, SubAgentSettings.class).contextMaxTokens(chat)
                : chat.getMemoryMaxTokens();
        if (set > 0) return set;
        // The model the role actually resolves to - a sub-agent's own, where it has one.
        Binding binding = bindingFor(role);
        Integer stated = binding == null ? null
                : providerFactory.modelLimits(binding.providerId(), binding.modelName()).contextWindow();
        return stated != null ? stated : ChatSettings.FALLBACK_CONTEXT_WINDOW;
    }

    @Override
    public LlmResult chat(LlmCaller caller, List<Message> messages) {
        return chat(caller, messages, null);
    }

    @Override
    public LlmResult chat(LlmCaller caller, List<Message> messages, ModelOverride override) {
        LlmRole role = caller.service();
        Binding binding = override != null ? explicitBinding(override) : bindingFor(role);
        if (binding == null) {
            throw new IllegalStateException("No model is configured for role " + role
                    + " - callers that can run without one must ask isAvailable() first.");
        }
        checkBudget(role, caller.subject());
        long start = System.nanoTime();
        ChatResponse response;
        WireUsage wire = new WireUsage();
        try (WireUsage.Open open = wire.bind()) {
            response = wire.correct(binding.model().call(new Prompt(messages)));
        } catch (RuntimeException e) {
            recordCall(role, binding, "call", start, e);
            throw e;
        }
        recordCall(role, binding, "call", start, null);
        LlmResult result = new LlmResult(response, binding.provider(), binding.modelName(), binding.dedicated());
        recordSpend(role, binding, caller.subject(), result.usage());
        return result;
    }

    @Override
    public EmbeddingModel embeddingModel(LlmCaller caller, String providerId, String model) {
        LlmProviderAdapter adapter = providerFactory.getAdapter(providerId);
        ResolvedProvider provider = providerFactory.resolveProvider(providerId).orElse(null);
        if (adapter == null || provider == null) return null;
        EmbeddingModel raw = adapter.buildEmbeddingModel(provider, model);
        return raw == null ? null : new MeteredEmbeddingModel(raw, caller, provider.name(), model);
    }

    @Override
    public Optional<Ranking> rerank(LlmCaller caller, String query, List<String> documents) {
        LlmRole role = caller.service();
        if (documents.isEmpty()) return Optional.of(new Ranking(List.of(), "none"));
        // Degrades rather than throws: a search whose rerank is refused still answers, unranked -
        // the same as a rerank model that is not configured.
        if (budget != null && budget.isExhausted()) return Optional.empty();
        try {
            checkBudget(role, caller.subject());
        } catch (UserBudgetExceededException e) {
            return Optional.empty();
        }

        RerankingSettings settings = settingsService.getSettings(
                RerankingSettings.PREFIX, RerankingSettings.class);
        LlmProviderAdapter adapter = providerFactory.getAdapter(settings.getProvider());
        ResolvedProvider provider = providerFactory.resolveProvider(settings.getProvider()).orElse(null);
        if (adapter == null || provider == null) return Optional.empty();

        // A genuine cross-encoder, when the instance says it has one. Native rerank is a property
        // of the INSTANCE and not of the protocol: /v1/rerank is a vLLM/Cohere extension that most
        // OpenAI-compatible services answer with a 404, so the operator opts in per instance and
        // the protocol only says whether opting in is meaningful at all.
        if (provider.nativeRerank() && provider.protocol().maySupportNativeRerank()) {
            try {
                LlmProviderAdapter.RerankResponse response =
                        adapter.rerank(provider, query, documents, settings.getModel());
                // Recorded here rather than not at all. This branch spent money and reached no
                // ledger until 2026-09-16, while recordSpend's own comment said it could not.
                recordSpend(role, provider.name(), settings.getModel(),
                        caller.subject(), response.usage());
                List<LlmProviderAdapter.RerankResult> results = response.results();
                List<Ranked> ranked = new ArrayList<>(results.size());
                for (LlmProviderAdapter.RerankResult r : results) ranked.add(new Ranked(r.index(), r.score()));
                return Optional.of(new Ranking(ranked, "native"));
            } catch (Exception e) {
                // Falls through to the embedding path rather than failing the search: a 404 here is
                // the common case for an instance whose operator ticked the box optimistically.
                log.warn("[LlmGateway] native rerank failed for provider '{}', trying embeddings: {}",
                        settings.getProvider(), e.getMessage());
            }
        }

        EmbeddingModel raw = rerankEmbeddingModel(settings, adapter, provider);
        if (raw == null) return Optional.empty();
        EmbeddingModel embeddingModel = new MeteredEmbeddingModel(raw, caller, provider.name(), settings.getModel());
        try {
            return Optional.of(new Ranking(scoreByCosine(query, documents, embeddingModel), "embedding"));
        } catch (Exception e) {
            // THROWN, not answered with empty. Empty means "this provider cannot rank - score each
            // candidate with a prompt instead", and the model here is a RERANKER checkpoint, which
            // answers a prompt with nothing: a search then sent it one chat request per candidate,
            // forty of them, every one returning no choices, and ranked by the fallback each
            // produced (found in a 0.23.0 feedback log, 2026-10-02). A reranker that will not
            // embed leaves the candidates in the order retrieval gave them, and says what to
            // change.
            throw new IllegalStateException("The reranking model '" + settings.getModel() + "' on '"
                    + provider.name() + "' did not return embeddings (" + e.getMessage() + "). If that "
                    + "provider has a /v1/rerank endpoint, turn on \"Serves /v1/rerank\" for it in "
                    + "Settings > AI Providers; otherwise choose a chat or embedding model for reranking.", e);
        }
    }

    /**
     * WHERE EVERY MODEL CALL IN THE PRODUCT IS COSTED. One place, reached by construction rather
     * than by each caller remembering - which is the whole reason the gateway exists.
     *
     * <p><b>THAT SENTENCE WAS NOT TRUE UNTIL 2026-09-16, and it is the reason to distrust this
     * kind of sentence.</b> It claimed all three formerly-unmetered paths - native rerank,
     * embedding-cosine rerank and workflow nodes - "cannot avoid this method". Only workflow nodes
     * ever did: rerank returned scores without usage and never called in, and the cosine tier built
     * a RAW embedding model whose own javadoc, a hundred lines below, admitted it was a known gap.
     * Two comments in one class, disagreeing, and the optimistic one was the one people read. Both
     * paths now call in for real - proved by a test that fails if either stops.
     *
     * <p>It writes TWO ledgers from this one point: the per-call log, which takes every call
     * including one whose provider reported no tokens, and the monthly rollup, which does not.
     * Written from the same numbers in the same breath, they cannot come to disagree about what
     * happened - which is precisely what the four ledgers that grew up separately did.
     *
     * <p>The subject arrives with the call, in its {@link LlmCaller} - see that record for why it
     * stopped being read from the thread.
     */
    void recordSpend(LlmRole role, Binding binding, SpendSubject subject, TokenUsage usage) {
        recordSpend(role, binding.provider(), binding.modelName(), subject, usage);
    }

    /**
     * The same, for a call with no {@link Binding} - rerank resolves its provider and model itself.
     *
     * <p>Takes the provider's DISPLAY NAME, never an instance id: {@code ModelPricingService} keys
     * rates on the name, so an id here prices every row at $0 while looking recorded.
     */
    void recordSpend(LlmRole role, String providerName, String modelName,
                     SpendSubject subject, TokenUsage usage) {
        double cost = pricing.costUsd(providerName, modelName, usage);
        // The per-call log takes EVERY call, including one whose provider reported no tokens - a
        // rerank endpoint that answers with scores alone still happened and still cost something.
        // The monthly rollup keeps ignoring zero-token calls, which is what makes its averages
        // mean anything.
        callLog.record(subject, role.name(), providerName, modelName, usage, cost);
        if (usage == null || usage == TokenUsage.EMPTY) return;
        attributedSpend.record(subject, role.name(), providerName, modelName, usage, cost);
    }

    // - internals -

    /** A resolved role: the model to drive, and the names to report it under. */
    private record Binding(ChatModel model, String provider, String providerId, String modelName, boolean dedicated,
                           String protocol) {}

    /**
     * ROLE TO MODEL, in one place. Every one of these dedicated-or-fall-back-to-main choices used
     * to be made at the call site, four of them with the same three lines and one of them - the
     * space fact - repeated a second time inside its own spend recording so the two could drift.
     *
     * <p>Which settings class holds a role is a fact about that role, so it lives beside the
     * fallback rather than in the service that happens to use it.
     */
    private Binding bindingFor(LlmRole role) {
        return switch (role) {
            case CHAT, CONDENSATION, WORKFLOW -> mainChat();
            // Title generation borrows the sub-agent model: short, undemanding work that should not
            // tie up the deployment's best model. Both run on the agent's model when sub-agents
            // have none of their own, which is also why a title is not a settings role of its own.
            case TITLE, SUB_AGENT -> {
                SubAgentSettings s = settingsService.getSettings(SubAgentSettings.PREFIX, SubAgentSettings.class);
                ChatSettings agent = settingsService.getSettings(ChatSettings.PREFIX, ChatSettings.class);
                yield dedicatedOrMain(providerFactory.getSubAgentChatModel(), s.provider(agent), s.getModel());
            }
            // The space fact rides the query-expansion model for the same reason, and deliberately
            // has no role of its own: a settings toggle nobody asked for is one nobody understands.
            case QUERY_EXPANSION, SPACE_FACT -> {
                QueryExpansionSettings s = settingsService.getSettings(
                        QueryExpansionSettings.PREFIX, QueryExpansionSettings.class);
                yield dedicatedOrMain(providerFactory.getQueryExpansionChatModel(), s.getProvider(), s.getModel());
            }
            case RERANK -> {
                RerankingSettings s = settingsService.getSettings(RerankingSettings.PREFIX, RerankingSettings.class);
                yield dedicatedOrMain(providerFactory.getRerankingChatModel(), s.getProvider(), s.getModel());
            }
            // NO FALLBACK for these two: both are off unless configured, and answering them with the
            // main chat model would silently start spending on OCR or graph extraction nobody enabled.
            case OCR -> {
                OcrSettings s = settingsService.getSettings(OcrSettings.PREFIX, OcrSettings.class);
                yield dedicatedOnly(providerFactory.getOcrChatModel(), s.getProvider(), s.getModel());
            }
            case GRAPH -> {
                GraphSettings s = settingsService.getSettings(GraphSettings.PREFIX, GraphSettings.class);
                yield dedicatedOnly(providerFactory.getGraphChatModel(), s.getProvider(), s.getModel());
            }
            // Same no-fallback rule, and here it matters most: this role can fire twice on EVERY
            // turn, so a fallback to main chat would silently double the deployment's chat bill the
            // moment somebody ticked "enable" without choosing a model.
            case GUARDRAIL -> {
                GuardrailSettings s = settingsService.getSettings(GuardrailSettings.PREFIX, GuardrailSettings.class);
                yield dedicatedOnly(providerFactory.getGuardrailChatModel(), s.getProvider(), s.getModel());
            }
            case EMBEDDING -> null;   // not a chat role - see embed()
        };
    }

    /**
     * The PROTOCOL of a provider instance - OPENAI_COMPATIBLE, ANTHROPIC - for diagnostics labels.
     * Never the instance's name, which is whatever the user typed, and never its URL.
     */
    private String protocolOf(String providerId) {
        try {
            return providerFactory.resolveProvider(providerId)
                    .map(p -> p.protocol() == null ? "unknown" : p.protocol().name())
                    .orElse("unknown");
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    /** One model call's duration, and its failure category when it failed. */
    private static void recordCall(LlmRole role, Binding binding, String mode, long startNanos, Throwable failure) {
        String protocol = binding.protocol();
        Diagnostics.timeSince(DiagOp.LLM_CALL, startNanos, "role", role == null ? "none" : role.name(),
                "protocol", protocol, "mode", mode, "outcome", failure == null ? "ok" : "failed");
        if (failure != null) {
            Diagnostics.count(DiagCounter.LLM_FAILURE, "role", role == null ? "none" : role.name(),
                    "protocol", protocol, "error", ErrorCategory.of(failure));
        }
    }

    private Binding mainChat() {
        ChatModel model = providerFactory.getChatModel();
        if (model == null) return null;
        ChatSettings s = settingsService.getSettings(ChatSettings.PREFIX, ChatSettings.class);
        return new Binding(model, providerFactory.providerDisplayName(s.getProvider()), s.getProvider(), s.getModel(), false, protocolOf(s.getProvider()));
    }

    private Binding dedicatedOrMain(ChatModel dedicated, String providerId, String modelName) {
        if (dedicated == null) return mainChat();
        return new Binding(dedicated, providerFactory.providerDisplayName(providerId), providerId, modelName, true, protocolOf(providerId));
    }

    private Binding dedicatedOnly(ChatModel dedicated, String providerId, String modelName) {
        if (dedicated == null) return null;
        return new Binding(dedicated, providerFactory.providerDisplayName(providerId), providerId, modelName, true, protocolOf(providerId));
    }
    /**
     * An explicitly named provider and model, with either half falling back to the main chat
     * settings when the caller left it unset - which is what a workflow node with no model of its
     * own means. Built with the chat role's own output ceiling and CONCISE, matching what
     * WorkflowRunPorts did before this moved: a step reports to the next step or to a log, not to
     * a reader waiting on prose style.
     */
    // Null when the named instance is gone - the same "not configured" every role binding reports,
    // so open() and isAvailable(override) say it the same way. buildChatModel throws for that case
    // rather than returning null, so the null check this had could never fire.
    private Binding explicitBinding(ModelOverride override) {
        ChatSettings chat = settingsService.getSettings(ChatSettings.PREFIX, ChatSettings.class);
        String providerId = override.providerId() == null ? chat.getProvider() : override.providerId();
        String modelName = override.model() == null ? chat.getModel() : override.model();
        if (providerId == null || modelName == null) return null;
        ChatModel model;
        try {
            model = providerFactory.buildChatModel(providerId, modelName,
                    ChatSettings.Tone.CONCISE, chat.getMaxOutputTokens());
        } catch (LlmProviderFactory.ProviderNotConfiguredException e) {
            return null;
        }
        boolean dedicated = !modelName.equals(chat.getModel()) || !providerId.equals(chat.getProvider());
        return new Binding(model, providerFactory.providerDisplayName(providerId), providerId, modelName, dedicated, protocolOf(providerId));
    }

    // One for the process: building it parses the whole cl100k vocabulary, and the strategy holds no
    // state between calls. It was built per cosine rerank - once per search.
    private static final TokenCountBatchingStrategy BATCHING = new TokenCountBatchingStrategy();

    /**
     * The embedding-cosine workaround, for a reranker-only checkpoint with no /rerank endpoint
     * (e.g. dengcao/Qwen3-Reranker-8B served through Ollama). Recognised by "rerank" appearing in
     * the model name, which is how the operator's own choice of checkpoint tells us what it is -
     * there is no capability call that would answer this.
     *
     * <p>A raw model built straight off the adapter, which rerank() wraps in
     * {@link MeteredEmbeddingModel} - so every request it makes, the query's and the documents',
     * reaches the per-call log. Until 2026-09-22 only the query's did.
     */
    private EmbeddingModel rerankEmbeddingModel(RerankingSettings settings,
                                                LlmProviderAdapter adapter,
                                                ResolvedProvider provider) {
        String model = settings.getModel();
        if (model == null || !model.toLowerCase(Locale.ROOT).contains("rerank")) return null;
        try {
            return adapter.buildEmbeddingModel(provider, model);
        } catch (Exception e) {
            log.warn("[LlmGateway] could not build embedding model for {}/{}: {}",
                    settings.getProvider(), model, e.getMessage());
            return null;
        }
    }

    // The model is METERED: both embed() calls reach MeteredEmbeddingModel.call(), the batched one
    // through EmbeddingModel's default, so each request is one row in the per-call log.
    private List<Ranked> scoreByCosine(String query, List<String> documents, EmbeddingModel embeddingModel) {
        float[] queryVec = embeddingModel.embed(query);
        List<Document> docs = toDocuments(documents);
        List<float[]> docVecs = embeddingModel.embed(docs, EmbeddingOptions.builder().build(), BATCHING);

        List<Ranked> ranked = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            // Scaled to 0-10 so every strategy reports on one range. Scores are not compared across
            // searches, but a number whose meaning depends on which tier answered is a trap for
            // whoever reads it next.
            ranked.add(new Ranked(i, cosineSimilarity(queryVec, docVecs.get(i)) * 10));
        }
        ranked.sort(Comparator.comparingDouble(Ranked::score).reversed());
        return ranked;
    }

    /**
     * Spring AI rejects a Document with no text, and a chunk CAN arrive blank - an image-only
     * page, or a converter that produced nothing. Substituting a space keeps index alignment,
     * which is what the caller maps its results back through; dropping the entry instead would
     * silently shift every score after it onto the wrong document.
     */
    private static List<Document> toDocuments(List<String> texts) {
        List<Document> docs = new ArrayList<>(texts.size());
        for (String t : texts) docs.add(new Document(t == null || t.isBlank() ? " " : t));
        return docs;
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }

    /**
     * Resolves the adapter for a binding, so buildOptions can ask it what its protocol accepts.
     *
     * <p>Falls back to the chat role's own adapter when the binding came from an override whose
     * provider id is not resolvable - the same shape of defence the rest of this class uses, since
     * failing a whole turn over an options builder would be worse than sending defaults.
     */
    private LlmProviderAdapter adapterFor(Binding binding) {
        ChatSettings chat = settingsService.getSettings(ChatSettings.PREFIX, ChatSettings.class);
        LlmProviderAdapter adapter = providerFactory.getAdapter(binding.providerId());
        return adapter != null ? adapter : providerFactory.getAdapter(chat.getProvider());
    }

    /** A bound model plus the options it reuses. See LlmSession for why the tools are per call. */
    private final class AdapterSession implements LlmSession {

        private final LlmRole role;
        private final Binding binding;
        private final ToolCallingChatOptions options;
        /**
         * From the caller that opened the session - never read from a thread. A ReAct loop hands
         * its model call to another thread more than once (a bounded future for the timeout, a
         * scheduler for the stream), and a ThreadLocal read over there would find nothing.
         */
        private final SpendSubject subject;
        private final boolean longCache;
        private final boolean streams;

        AdapterSession(LlmRole role, Binding binding, ToolCallingChatOptions options, SpendSubject subject,
                       boolean longCache, boolean streams) {
            this.role = role;
            this.binding = binding;
            this.options = options;
            this.subject = subject;
            this.longCache = longCache;
            this.streams = streams;
        }

        @Override public String provider()   { return binding.provider(); }
        @Override public String model()      { return binding.modelName(); }
        @Override public boolean dedicated() { return binding.dedicated(); }
        @Override public String protocol()   { return binding.protocol(); }
        @Override public boolean longPromptCache() { return longCache; }
        @Override public boolean streams() { return streams; }

        @Override
        public LlmResult chat(List<Message> messages, List<ToolCallback> tools) {
            checkBudget(role, subject);
            long start = System.nanoTime();
            ChatResponse response;
            // The counts the response stated, where Spring AI dropped some - see WireUsage.
            WireUsage wire = new WireUsage();
            try (WireUsage.Open open = wire.bind()) {
                response = wire.correct(binding.model().call(prompt(messages, tools)));
            } catch (RuntimeException e) {
                recordCall(role, binding, "call", start, e);
                throw e;
            }
            recordCall(role, binding, "call", start, null);
            LlmResult result = new LlmResult(response, binding.provider(), binding.modelName(), binding.dedicated());
            recordSpend(role, binding, subject, priced(result.usage()));
            return result;
        }

        @Override
        public Flux<ChatResponse> stream(List<Message> messages, List<ToolCallback> tools) {
            // A stream's usage is folded FIELD-WISE MAXIMUM over every chunk that reports any, and
            // recorded once the stream ends, however it ends. Not the last chunk: Anthropic sends
            // the input and both cache counts in message_start and only the output in
            // message_delta, so "newest wins" - what this did until 2026-09-24 - filed every
            // streamed Anthropic call in the per-call ledger with no input and no cache tokens at
            // all. StreamChunkAggregation fixed the same rule on the chat side long before; see
            // TokenUsage.maxWith. doFinally rather than doOnComplete: a turn the user stopped still
            // spent what it spent up to that point, and cancellation is the commonest way this
            // flux terminates.
            //
            // Every chunk is first raised to what the provider's stream itself stated (WireUsage):
            // Spring AI keeps an Anthropic stream's input and cache counts from message_start
            // alone, and a gateway that states them only in message_delta was filed with its output
            // and nothing else (0.29.2 feedback). The tap rides in this stream's context, so the
            // chat's own aggregation reads the corrected chunks too.
            //
            // EVERY SUBSCRIPTION IS ITS OWN CALL. ModelCall retries by resubscribing this flux, and
            // the state below was made once per stream() and shared by every attempt: a failure was
            // never cleared, so a retry that succeeded was recorded as failed; attempt 1's maxima
            // were filed again on attempt 2's ledger row; and attempt 1's stated usage raised
            // attempt 2's chunks. Deferred, each attempt starts from nothing and files its own row.
            checkBudget(role, subject);
            Prompt prompt = prompt(messages, tools);
            return Flux.defer(() -> {
                WireUsage wire = new WireUsage();
                java.util.concurrent.atomic.AtomicReference<TokenUsage> last =
                        new java.util.concurrent.atomic.AtomicReference<>(TokenUsage.EMPTY);
                // Timed from subscription to termination. A stop by the user is a cancel, recorded
                // as "ok" - it is not the provider failing.
                long start = System.nanoTime();
                java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                        new java.util.concurrent.atomic.AtomicReference<>();
                return binding.model().stream(prompt)
                        .map(wire::correct)
                        .doOnNext(chunk -> {
                            TokenUsage usage = TokenUsageExtractor.extract(chunk);
                            if (usage != null && usage != TokenUsage.EMPTY) last.updateAndGet(u -> u.maxWith(usage));
                        })
                        .doOnError(failure::set)
                        .doFinally(signal -> {
                            recordCall(role, binding, "stream", start, failure.get());
                            TokenUsage spent = priced(last.get());
                            recordSpendOffLoop(role, binding, subject, spent);
                        })
                        .contextWrite(wire::into);
            });
        }

        // See spendRecorder. Never allowed to fail the stream that already ended.
        private void recordSpendOffLoop(LlmRole role, Binding binding, SpendSubject subject, TokenUsage spent) {
            try {
                spendRecorder.execute(() -> {
                    try {
                        recordSpend(role, binding, subject, spent);
                    } catch (RuntimeException e) {
                        log.warn("[LlmGateway] could not record a stream's spend: {}", e.toString());
                    }
                });
            } catch (RuntimeException rejected) {
                recordSpend(role, binding, subject, spent);
            }
        }

        // The options object is mutated rather than rebuilt, which is what the ReAct loop did
        // before this moved - Spring AI reads the callbacks off it at request time.
        private Prompt prompt(List<Message> messages, List<ToolCallback> tools) {
            options.setToolCallbacks(tools == null ? List.of() : tools);
            return new Prompt(messages, options);
        }
    }

    /**
     * An embedding model that is not the configured one, metered against a caller: every request
     * is one row in the per-call log, priced like any other. Covers the two embedding paths that
     * build a raw model off an adapter - the cosine rerank tier and a search querying the previous
     * model while a re-index runs - which spent tokens no ledger saw until 2026-09-22.
     *
     * <p>Only call() is metered, and that is enough: embed(String) and embed(Document) are routed
     * through it here, and EmbeddingModel's batched default is defined in terms of call().
     */
    private final class MeteredEmbeddingModel implements EmbeddingModel {

        private final EmbeddingModel delegate;
        private final LlmCaller caller;
        private final String providerName;
        private final String modelName;

        MeteredEmbeddingModel(EmbeddingModel delegate, LlmCaller caller, String providerName, String modelName) {
            this.delegate = delegate;
            this.caller = caller;
            this.providerName = providerName;
            this.modelName = modelName;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            checkBudget(caller.service(), caller.subject());
            EmbeddingResponse response = delegate.call(request);
            TokenUsage usage = response == null || response.getMetadata() == null
                    ? TokenUsage.EMPTY
                    : TokenUsageExtractor.extract(response.getMetadata().getUsage());
            recordSpend(caller.service(), providerName, modelName, caller.subject(), usage);
            return response;
        }

        @Override
        public float[] embed(String text) {
            return call(new EmbeddingRequest(List.of(text), EmbeddingOptions.builder().build()))
                    .getResults().get(0).getOutput();
        }

        @Override
        public float[] embed(Document document) {
            return embed(document.getText());
        }
    }
}
