package ai.proxyma.core.provider.service;

import ai.proxyma.core.common.concurrent.BlockingWork;
import ai.proxyma.core.provider.adapter.LlmProviderAdapter;
import ai.proxyma.core.provider.adapter.ModelLimits;
import ai.proxyma.core.settings.provider.ProviderInstanceService;
import ai.proxyma.core.settings.provider.ProviderProtocol;
import ai.proxyma.core.settings.provider.ResolvedProvider;
import ai.proxyma.core.settings.event.ProviderSettingsChangedEvent;
import ai.proxyma.core.settings.dto.ChatSettings;
import ai.proxyma.core.settings.dto.EmbeddingSettings;
import ai.proxyma.core.settings.dto.OcrSettings;
import ai.proxyma.core.settings.dto.GraphSettings;
import ai.proxyma.core.settings.dto.GuardrailSettings;
import ai.proxyma.core.settings.dto.QueryExpansionSettings;
import ai.proxyma.core.settings.dto.RerankingSettings;
import ai.proxyma.core.settings.dto.SubAgentSettings;
import ai.proxyma.core.settings.dto.ChatSettings.Tone;
import ai.proxyma.core.settings.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

// Central AI provider factory. Maintains one volatile model reference per role: chat, embedding,
// query expansion, reranking, sub-agent, OCR (vision), graph extraction and the guardrail
// classifier. All of them are (re)built on one thread, at ApplicationReadyEvent and whenever
// settings change. Which sampling parameters a protocol accepts is each adapter's business.
@Service
public class LlmProviderFactory {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderFactory.class);

    private final SettingsService settingsService;
    private final ProviderInstanceService providerInstances;

    // Keyed by PROTOCOL, not by provider name. One adapter serves every instance speaking it, so
    // adding OpenRouter or a second OpenAI account adds a row to a table, not an entry here.
    private final Map<ProviderProtocol, LlmProviderAdapter> adapters;

    private volatile ChatModel chatModel;
    private volatile EmbeddingModel embeddingModel;
    private volatile ChatModel queryExpansionChatModel;
    private volatile ChatModel rerankingChatModel;
    private volatile ChatModel subAgentChatModel;
    private volatile ChatModel ocrChatModel;
    private volatile ChatModel graphChatModel;
    private volatile ChatModel guardrailChatModel;

    public LlmProviderFactory(SettingsService settingsService,
                              ProviderInstanceService providerInstances,
                              List<LlmProviderAdapter> adapters) {
        this.settingsService = settingsService;
        this.providerInstances = providerInstances;
        this.adapters = adapters.stream()
                .collect(Collectors.toMap(LlmProviderAdapter::protocol, Function.identity()));
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    public ChatModel getChatModel() { return chatModel; }
    public EmbeddingModel getEmbeddingModel() { return embeddingModel; }
    public ChatModel getQueryExpansionChatModel() { return queryExpansionChatModel; }
    public ChatModel getRerankingChatModel() { return rerankingChatModel; }

    // Null whenever sub-agents have no model of their own or it failed to build - DelegateTool
    // treats null as "delegation unavailable" and tells the main agent to do the work itself,
    // rather than failing the turn.
    public ChatModel getSubAgentChatModel() { return subAgentChatModel; }

    // Null whenever the OCR engine is not the vision one, no model is configured, or the model
    // failed to build - OcrService reports OCR as unavailable in that case rather than failing a
    // conversion halfway through.
    public ChatModel getOcrChatModel() { return ocrChatModel; }

    // Null whenever graph extraction is disabled or its model failed to build. Callers must treat
    // null as "do not extract" and let the document finish ingesting - the graph being incomplete
    // is recoverable by re-indexing later, whereas failing ingest over an optional enrichment
    // would make turning this feature on able to break the pipeline that feeds everything else.
    public ChatModel getGraphChatModel() { return graphChatModel; }

    // Null whenever the guardrail classifier is disabled or its model failed to build. Callers
    // must treat null as "the classifier layer is off" and fall through to the deterministic
    // rules, which need no model at all - never as a reason to refuse the turn. A guardrail whose
    // model is unreachable taking the whole product down with it is a worse outcome than the one
    // it is protecting against.
    public ChatModel getGuardrailChatModel() { return guardrailChatModel; }

    // ── Initialization ─────────────────────────────────────────────────────────

    // ONE thread builds every role's model, in the order the requests arrive. Building one may ask
    // its provider for the model's limits (outputCeiling), a network call; it ran on the boot thread
    // at startup and, on a settings change, on the shared default @Async pool inside a synchronized
    // method - so a burst of saves parked pool threads meant for short database writes on a monitor
    // held across a provider's connect and read timeouts. A single queue keeps the rebuilds in
    // order, takes no lock, and leaves both the boot thread and that pool alone.
    private final java.util.concurrent.ExecutorService initThread =
            java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "provider-init");
                thread.setDaemon(true);
                return thread;
            });

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        submit("startup", this::initAll);
    }

    // AFTER THE COMMIT: ProviderInstanceService publishes this inside its own transaction, and a
    // rebuild that ran first read the row as it was before the save - the old key, or an instance
    // just deleted - until the next change. fallbackExecution covers the publishers with no
    // transaction (SettingsService's role saves).
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onProviderSettingsChanged(ProviderSettingsChangedEvent event) {
        submit(event.role(), () -> reinit(event));
    }

    void reinit(ProviderSettingsChangedEvent event) {
        // A role now points somewhere else, or an instance's address changed: ask again.
        rememberedLimits.clear();
        switch (event.role()) {
            // The sub-agents too: with no provider of their own they run on the agent's.
            case ChatSettings.PREFIX -> { initChat(); initSubAgent(); }
            case EmbeddingSettings.PREFIX -> initEmbedding();
            case QueryExpansionSettings.PREFIX -> initQueryExpansion();
            case RerankingSettings.PREFIX -> initReranking();
            case SubAgentSettings.PREFIX -> initSubAgent();
            case GraphSettings.PREFIX -> initGraph();
            case GuardrailSettings.PREFIX -> initGuardrail();
            case OcrSettings.PREFIX -> initOcr();
            case "credentials" -> initAllUsingProvider(event.provider());
            default -> log.warn("Unknown reinit role '{}'", event.role());
        }
    }

    private void submit(String reason, Runnable work) {
        initThread.execute(() -> {
            try {
                work.run();
            } catch (RuntimeException e) {
                log.error("[Proxy] Rebuilding models ({}) failed: {}", reason, e.toString());
            }
        });
    }

    private void initChat() {
        ChatSettings s = settingsService.getSettings(ChatSettings.PREFIX, ChatSettings.class);
        try {
            // Also where the model's limits are first asked for (buildChatModel resolves an unset
            // ceiling through modelLimits), so the readers on a request path find them remembered.
            chatModel = buildChatModel(s.getProvider(), s.getModel(), s.getTone(), s.getMaxOutputTokens());
            log.debug("Chat model reinitialized: {}/{}", providerDisplayName(s.getProvider()), s.getModel());
        } catch (Exception e) {
            logInitFailure("chat model", providerDisplayName(s.getProvider()), s.getModel(), e);
            chatModel = null;
        }
    }

    private void initEmbedding() {
        EmbeddingSettings s = settingsService.getSettings(EmbeddingSettings.PREFIX, EmbeddingSettings.class);
        try {
            embeddingModel = buildEmbeddingModel(s.getProvider(), s.getModel());
            log.debug("Embedding model reinitialized: {}/{}", providerDisplayName(s.getProvider()), s.getModel());
        } catch (Exception e) {
            logInitFailure("embedding model", providerDisplayName(s.getProvider()), s.getModel(), e);
            embeddingModel = null;
        }
    }

    private void initQueryExpansion() {
        QueryExpansionSettings s = settingsService.getSettings(QueryExpansionSettings.PREFIX, QueryExpansionSettings.class);
        try {
            queryExpansionChatModel = buildChatModel(s.getProvider(), s.getModel(), Tone.CONCISE, 4096);
            log.debug("Query expansion model reinitialized: {}/{}", providerDisplayName(s.getProvider()), s.getModel());
        } catch (Exception e) {
            logInitFailure("query expansion model", providerDisplayName(s.getProvider()), s.getModel(), e);
            queryExpansionChatModel = null;
        }
    }

    private void initReranking() {
        RerankingSettings s = settingsService.getSettings(RerankingSettings.PREFIX, RerankingSettings.class);
        try {
            rerankingChatModel = buildChatModel(s.getProvider(), s.getModel(), Tone.CONCISE, 1024);
            log.debug("Reranking model reinitialized: {}/{}", providerDisplayName(s.getProvider()), s.getModel());
        } catch (Exception e) {
            logInitFailure("reranking model", providerDisplayName(s.getProvider()), s.getModel(), e);
            rerankingChatModel = null;
        }
    }

    private void initSubAgent() {
        SubAgentSettings s = settingsService.getSettings(SubAgentSettings.PREFIX, SubAgentSettings.class);
        // No model of its own: a sub-agent runs on the agent's (DefaultLlmGateway.bindingFor), so
        // there is nothing to build here.
        if (s.getModel() == null || s.getModel().isBlank()) {
            subAgentChatModel = null;
            return;
        }
        try {
            // CONCISE: a sub-agent reports findings back to another model, not to a person -
            // there is no value in it spending output tokens on prose style.
            ChatSettings agent = settingsService.getSettings(ChatSettings.PREFIX, ChatSettings.class);
            subAgentChatModel = buildChatModel(s.provider(agent), s.getModel(), Tone.CONCISE, s.maxOutputTokens(agent));
            log.debug("Sub-agent model reinitialized: {}/{}", providerDisplayName(s.provider(agent)), s.getModel());
        } catch (Exception e) {
            logInitFailure("sub-agent model", providerDisplayName(s.getProvider()), s.getModel(), e);
            subAgentChatModel = null;
        }
    }

    private void initGraph() {
        GraphSettings s = settingsService.getSettings(GraphSettings.PREFIX, GraphSettings.class);
        if (!s.isEnabled() || s.getModel() == null || s.getModel().isBlank()) {
            graphChatModel = null;
            return;
        }
        try {
            // CONCISE: extraction returns a structured object to a parser, not prose to a person.
            // Any latitude spent on style is tokens spent per chunk across an entire corpus.
            graphChatModel = buildChatModel(s.getProvider(), s.getModel(), Tone.CONCISE,
                    s.getMaxOutputTokens());
            log.debug("Graph extraction model reinitialized: {}/{}", providerDisplayName(s.getProvider()), s.getModel());
        } catch (Exception e) {
            logInitFailure("graph extraction model", providerDisplayName(s.getProvider()), s.getModel(), e);
            graphChatModel = null;
        }
    }

    // The classifier's ceiling when the stored value is blank. GuardrailSettings keeps it as an
    // Integer, and a blank saved field reads back as null - which, unboxed, threw here and left the
    // classifier silently off while the page showed it on.
    private static final int GUARDRAIL_DEFAULT_CEILING = 256;

    private void initGuardrail() {
        GuardrailSettings s = settingsService.getSettings(GuardrailSettings.PREFIX, GuardrailSettings.class);
        // Gated on the LLM toggle specifically, not on the guardrails being on at all: the
        // deterministic rules are the default and they need no model, so a deployment with
        // guardrails enabled and no classifier configured must build nothing here.
        if (!s.isLlmEnabled() || s.getModel() == null || s.getModel().isBlank()) {
            guardrailChatModel = null;
            return;
        }
        try {
            // CONCISE: the classifier returns a small JSON object to a parser, never prose to a
            // person, and this call happens on every turn - twice, when both stages are on.
            Integer ceiling = s.getMaxOutputTokens();
            guardrailChatModel = buildChatModel(s.getProvider(), s.getModel(), Tone.CONCISE,
                    ceiling == null ? GUARDRAIL_DEFAULT_CEILING : ceiling);
            log.debug("Guardrail classifier model reinitialized: {}/{}",
                    providerDisplayName(s.getProvider()), s.getModel());
        } catch (Exception e) {
            logInitFailure("guardrail classifier model", providerDisplayName(s.getProvider()), s.getModel(), e);
            guardrailChatModel = null;
        }
    }

    private void initOcr() {
        OcrSettings s = settingsService.getSettings(OcrSettings.PREFIX, OcrSettings.class);
        if (s.getEngine() != OcrSettings.Engine.VISION || s.getModel() == null || s.getModel().isBlank()) {
            ocrChatModel = null;
            return;
        }
        try {
            // CONCISE and a large output budget: transcribing a dense page can legitimately need
            // more tokens than a chat answer, while any "creative" latitude is actively wrong here
            // - the model is reading a page, not writing about it.
            ocrChatModel = buildChatModel(s.getProvider(), s.getModel(), Tone.CONCISE, 8192);
            log.debug("OCR vision model reinitialized: {}/{}", providerDisplayName(s.getProvider()), s.getModel());
        } catch (Exception e) {
            logInitFailure("OCR vision model", providerDisplayName(s.getProvider()), s.getModel(), e);
            ocrChatModel = null;
        }
    }

    private void initAllUsingProvider(String provider) {
        rememberedLimits.clear();
        ChatSettings chat = settingsService.getSettings(ChatSettings.PREFIX, ChatSettings.class);
        if (provider.equals(chat.getProvider())) initChat();

        EmbeddingSettings emb = settingsService.getSettings(EmbeddingSettings.PREFIX, EmbeddingSettings.class);
        if (provider.equals(emb.getProvider())) initEmbedding();

        QueryExpansionSettings qe = settingsService.getSettings(QueryExpansionSettings.PREFIX, QueryExpansionSettings.class);
        if (provider.equals(qe.getProvider())) initQueryExpansion();

        RerankingSettings rerank = settingsService.getSettings(RerankingSettings.PREFIX, RerankingSettings.class);
        if (provider.equals(rerank.getProvider())) initReranking();

        SubAgentSettings sub = settingsService.getSettings(SubAgentSettings.PREFIX, SubAgentSettings.class);
        if (provider.equals(sub.provider(chat))) initSubAgent();

        OcrSettings ocr = settingsService.getSettings(OcrSettings.PREFIX, OcrSettings.class);
        if (provider.equals(ocr.getProvider())) initOcr();

        GraphSettings graph = settingsService.getSettings(GraphSettings.PREFIX, GraphSettings.class);
        if (provider.equals(graph.getProvider())) initGraph();

        GuardrailSettings guardrail = settingsService.getSettings(
                GuardrailSettings.PREFIX, GuardrailSettings.class);
        if (provider.equals(guardrail.getProvider())) initGuardrail();
    }

    // Package-visible for the test that pins every role being built here - sub-agent, OCR, graph
    // and the guardrail were each missing from this list once, and a role missing from it stays off
    // after every restart until somebody re-saves one of its fields.
    void initAll() {
        initChat();
        initEmbedding();
        initQueryExpansion();
        initReranking();
        initSubAgent();
        initOcr();
        initGraph();
        initGuardrail();
    }

    // ── Model construction ─────────────────────────────────────────────────────

    /**
     * Builds a chat model for a provider instance and model name.
     *
     * <p>Every role stores the ID of a configured provider instance. Resolution is therefore
     * id -&gt; instance -&gt; protocol -&gt; adapter, and the adapter is handed the instance to talk to
     * rather than looking one up itself.
     *
     * <p>PUBLIC, AND UNCACHED. The seven roles above each hold one built model in a volatile field,
     * rebuilt when their settings change. This entry point is for a model chosen per RUN rather
     * than per role - a workflow's own {@code provider:}/{@code model:} pair (see WorkflowService)
     * - where there is no fixed set to hold. Callers get a fresh model each time and should build
     * once per run, not once per step. A cache here would be keyed on a pair that any author can
     * invent, which is an unbounded map of objects holding HTTP clients.
     *
     * <p>Throws rather than returning null when the instance is gone or its protocol has no
     * adapter, so a caller cannot mistake "that provider no longer exists" for "run it on the
     * default".
     */
    public ChatModel buildChatModel(String providerId, String model, Tone tone, int maxTokens) {
        ResolvedProvider provider = requireProvider(providerId);
        return requireAdapter(provider).buildChatModel(provider, model, tone,
                outputCeiling(providerId, model, maxTokens));
    }

    // -- What a model can take in and give back ---------------------------------------------------

    private record Remembered(ModelLimits limits, long until) {}

    private final Map<String, Remembered> rememberedLimits = new java.util.concurrent.ConcurrentHashMap<>();
    // A model's limits do not change; a provider that could not be asked may be back in a minute.
    private static final long LIMITS_KNOWN_MS = java.time.Duration.ofHours(6).toMillis();
    private static final long LIMITS_UNKNOWN_MS = java.time.Duration.ofMinutes(10).toMillis();
    // How long a caller waits for the answer. The request keeps the LLM read bound, which is for a
    // model generating; "how large is this model" is not worth a request path or a rebuild waiting
    // longer than this. A late answer is simply not used.
    private static final long LIMITS_WAIT_SECONDS = 15;
    // The Settings page may ask about any (instance, model) pair; past this many the expired ones go,
    // and if that is not enough, all of them - a model nobody asks about again is not worth keeping.
    private static final int MAX_REMEMBERED = 512;

    /**
     * What the provider says this model can take in and give back, asked once and remembered.
     * Never throws and never null: an instance that is gone, a protocol that cannot be asked and a
     * request that failed are all {@link ModelLimits#UNKNOWN}, remembered for a shorter time.
     *
     * <p>The first call for a model is an HTTP request, waited on for at most
     * {@link #LIMITS_WAIT_SECONDS}. It is made when the role's model is built (initChat,
     * initSubAgent), so the readers on a request path find it already answered.
     */
    public ModelLimits modelLimits(String providerId, String model) {
        if (providerId == null || providerId.isBlank() || model == null || model.isBlank()) return ModelLimits.UNKNOWN;
        String key = providerId + '\n' + model;
        long now = System.currentTimeMillis();
        Remembered held = rememberedLimits.get(key);
        if (held != null && held.until() > now) return held.limits();
        ModelLimits limits = ModelLimits.UNKNOWN;
        ResolvedProvider provider = providerInstances.resolve(providerId).orElse(null);
        LlmProviderAdapter adapter = provider == null ? null : adapters.get(provider.protocol());
        // Nothing remembered for an instance that does not exist: the key is the caller's to
        // invent, and remembering every one was an unbounded map.
        if (adapter == null) return ModelLimits.UNKNOWN;
        try {
            ModelLimits answered = java.util.concurrent.CompletableFuture
                    .supplyAsync(() -> adapter.modelLimits(provider, model), BlockingWork.executor())
                    .get(LIMITS_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            if (answered != null) limits = answered;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("[Proxy] No limits for model {} on {}: {}", model, providerDisplayName(providerId), e.toString());
        }
        if (rememberedLimits.size() >= MAX_REMEMBERED) {
            rememberedLimits.values().removeIf(r -> r.until() <= now);
            if (rememberedLimits.size() >= MAX_REMEMBERED) rememberedLimits.clear();
        }
        rememberedLimits.put(key, new Remembered(limits, now + (limits.isKnown() ? LIMITS_KNOWN_MS : LIMITS_UNKNOWN_MS)));
        if (limits.isKnown()) {
            log.info("[Proxy] {} on {} states context={} output={}", model, providerDisplayName(providerId),
                    limits.contextWindow(), limits.maxOutputTokens());
        }
        return limits;
    }

    /**
     * The output ceiling a call goes out with: the one asked for, else the model's own as the
     * provider states it, else zero - which an adapter reads as "send none" (LlmProviderAdapter
     * .ceiling), leaving it to the provider.
     */
    public int outputCeiling(String providerId, String model, int requested) {
        if (requested > 0) return requested;
        Integer stated = modelLimits(providerId, model).maxOutputTokens();
        return stated == null ? 0 : stated;
    }

    private EmbeddingModel buildEmbeddingModel(String providerId, String model) {
        ResolvedProvider provider = requireProvider(providerId);
        EmbeddingModel result = requireAdapter(provider).buildEmbeddingModel(provider, model);
        if (result == null) {
            throw new UnsupportedOperationException(
                    "Provider '" + provider.name() + "' speaks " + provider.protocol()
                            + ", which this build cannot embed with");
        }
        return result;
    }

    /**
     * The instance a role points at.
     *
     * A role naming an instance that no longer exists - deleted, or never configured on a fresh
     * deployment - throws here, which the init methods catch and turn into "no model for this
     * role". That is deliberately the same outcome as a misconfigured one: visible as unavailable,
     * rather than silently falling back to some other provider the user did not choose.
     */
    private ResolvedProvider requireProvider(String providerId) {
        return providerInstances.resolve(providerId)
                .orElseThrow(() -> new ProviderNotConfiguredException(
                        providerId == null || providerId.isBlank()
                                ? "No provider selected for this role"
                                : "No configured provider with id " + providerId));
    }

    // A role pointing at a provider instance that is not configured is a SETTINGS state, not a
    // fault: it is the normal condition of a fresh deployment, and of any deployment where an
    // instance was deleted or renamed while a role still named it. It was logged at ERROR, so a
    // desktop install whose saved settings still said "ollama" opened with four ERROR lines on
    // every single start and nothing wrong with it. A genuine build failure - bad credentials, an
    // adapter that threw - stays at ERROR, because that one IS a fault.
    private void logInitFailure(String role, String provider, String model, Exception e) {
        if (e instanceof ProviderNotConfiguredException) {
            // NOTHING SELECTED is the ordinary state of a fresh install and reads differently from
            // a role pointing at an instance that is gone. It used to print as "/ is not a
            // configured provider", because the roles shipped a provider default of "" - and
            // before that as "ollama/llama3", which was worse: it named a provider that had never
            // existed as an instance and sent the reader looking for it.
            if (provider == null || provider.isBlank()) {
                log.warn("No {} available: no provider selected for this role - pick one in Settings",
                        role);
            } else {
                log.warn("No {} available: {}/{} is not a configured provider - pick one in Settings",
                        role, provider, model);
            }
        } else {
            log.error("Failed to init {} ({}/{}): {}", role, provider, model, e.getMessage());
        }
    }

    /** Thrown when a role names a provider instance that does not exist - see requireProvider. */
    public static class ProviderNotConfiguredException extends IllegalStateException {
        ProviderNotConfiguredException(String message) { super(message); }
    }

    private LlmProviderAdapter requireAdapter(ResolvedProvider provider) {
        LlmProviderAdapter adapter = adapters.get(provider.protocol());
        if (adapter == null) {
            throw new UnsupportedOperationException(
                    "No adapter registered for protocol: " + provider.protocol());
        }
        return adapter;
    }

    // ── Adapter and instance access ────────────────────────────────────────────

    /** The adapter for the instance a role points at, or null when that instance is gone. Callers
     *  outside this class hold a role's stored provider id, never a protocol. */
    public LlmProviderAdapter getAdapter(String providerId) {
        return providerInstances.resolve(providerId)
                .map(p -> adapters.get(p.protocol()))
                .orElse(null);
    }

    /** For callers that need the instance itself - its display name for the cost ledger, or its
     *  native-rerank flag. */
    public Optional<ResolvedProvider> resolveProvider(String providerId) {
        return providerInstances.resolve(providerId);
    }

    /**
     * What the cost ledger and the price list should call this provider: the user's own name for
     * it, never the id.
     *
     * Roles store an opaque instance id, and writing that into the ledger would put raw UUIDs on
     * the Usages page and stop every configured price from matching (ModelPricing is keyed on
     * provider + model). Falls back to the id when the instance is gone, because a spend row that
     * says something unhelpful is still better than one that says nothing about where the money
     * went.
     */
    public String providerDisplayName(String providerId) {
        return providerInstances.resolve(providerId)
                .map(ResolvedProvider::name)
                .orElse(providerId);
    }
}
