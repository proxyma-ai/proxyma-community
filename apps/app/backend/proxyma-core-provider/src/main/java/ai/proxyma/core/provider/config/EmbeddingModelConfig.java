package ai.proxyma.core.provider.config;

import ai.proxyma.core.common.usage.TokenUsage;
import ai.proxyma.core.provider.service.LlmProviderFactory;
import ai.proxyma.core.common.spend.SpendContext;
import ai.proxyma.core.provider.gateway.LlmRole;
import ai.proxyma.core.provider.gateway.UserQuota;
import ai.proxyma.core.settings.usage.CostCenterService;
import ai.proxyma.core.settings.usage.UserBudgetGate;
import org.springframework.beans.factory.ObjectProvider;
import ai.proxyma.core.provider.usage.TokenUsageExtractor;
import ai.proxyma.core.settings.dto.EmbeddingSettings;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.settings.usage.GlobalQuotaGate;
import ai.proxyma.core.settings.usage.PlatformService;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import ai.proxyma.core.settings.usage.AttributedSpendService;
import ai.proxyma.core.settings.usage.LlmCallLogService;
import ai.proxyma.core.settings.usage.PlatformSpendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;

@Configuration
public class EmbeddingModelConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingModelConfig.class);

    /**
     * Lazy-delegate: resolves the real model from the factory at call time, not at bean-creation
     * time (the factory initializes on ApplicationReadyEvent).
     *
     * It also RECORDS WHAT EMBEDDING COSTS. Embedding runs once per chunk at ingest and once per
     * query at search time, which normally makes it the largest single LLM cost in a deployment -
     * and none of it appeared in any total anywhere, because nothing on this path published spend.
     *
     * Instrumented here, in the one delegating bean, rather than at the call sites. There are two
     * of those today (PgVectorStoreService's batched ingest path and its query path) and they both
     * use the convenience embed() methods, which return bare float[] and throw the
     * EmbeddingResponse - and its usage metadata - away. Recording centrally means a third call
     * site added later is counted without anyone remembering to instrument it.
     *
     * All three methods below route through this class's own call(), NOT the underlying model's.
     * That is the load-bearing detail: EmbeddingModel's default embed(List, options, strategy) -
     * the batched path ingest actually uses - is defined in terms of this.call(), so overriding
     * call() catches the batched path for free. Delegating embed(String)/embed(Document) straight
     * to the real model, as this used to, would have left both of them uncounted.
     */
    @Bean
    @Primary
    public EmbeddingModel embeddingModel(LlmProviderFactory factory,
                                         PlatformSpendService platformSpendService,
                                         AttributedSpendService attributedSpend,
                                         LlmCallLogService callLog,
                                         ModelPricingService pricing,
                                         GlobalQuotaGate globalQuotaGate,
                                         SettingsService settingsService,
                                         ObjectProvider<UserBudgetGate> userBudget,
                                         ObjectProvider<CostCenterService> costCenters) {
        return new EmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                // Gated here for the same reason spend is recorded here: this is the one point
                // every embedding passes through, ingest and search alike. On the ingest path the
                // exception reaches SyncService.markFailed(), which records the reason against the
                // document and schedules an auto-retry - so work refused for budget resumes by
                // itself once the quota resets, and never looks indexed when it is not.
                globalQuotaGate.checkOrRefuse("indexing or searching your documents");
                // And the per-user quota, where embedding is charged to the user - the same rule the
                // gateway applies to every other path (UserQuota). Checked only there until
                // 2026-10-14, so this, the busiest path, kept spending past a user's quota.
                UserQuota.check(userBudget.getIfAvailable(), costCenters.getIfAvailable(),
                        LlmRole.EMBEDDING, SpendContext.current());
                // Null whenever the configured pairing could not be built - a provider that was
                // deleted, a key that stopped working, or a protocol with no embedding endpoint,
                // which is now a pairing the settings page lets an operator pick (see
                // ProviderProtocol). Stated rather than dereferenced: a NullPointerException here
                // reaches SyncService.markFailed() as the reason a document did not index, and
                // "NullPointerException" is not a reason anyone can act on.
                EmbeddingModel configured = factory.getEmbeddingModel();
                if (configured == null) {
                    EmbeddingSettings s = settingsService.getSettings(
                            EmbeddingSettings.PREFIX, EmbeddingSettings.class);
                    throw new IllegalStateException(
                            "No embedding model is available: the provider configured for "
                                    + "embeddings (" + factory.providerDisplayName(s.getProvider())
                                    + " / " + s.getModel() + ") could not be built. Check Settings "
                                    + "> Embedding - not every provider can serve this role.");
                }
                EmbeddingResponse response = configured.call(request);
                recordSpend(response);
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

            // Never allowed to disturb the embedding itself: a failure here means an under-counted
            // total, which is visible and recoverable, whereas a thrown exception would fail an
            // ingest run over bookkeeping.
            private void recordSpend(EmbeddingResponse response) {
                try {
                    if (response == null || response.getMetadata() == null) return;
                    TokenUsage usage = TokenUsageExtractor.extract(response.getMetadata().getUsage());
                    EmbeddingSettings s = settingsService.getSettings(
                            EmbeddingSettings.PREFIX, EmbeddingSettings.class);
                    String callProvider = factory.providerDisplayName(s.getProvider());
                    // A call with no reported usage is still a call: it goes in the per-call log, as
                    // the gateway's own calls do (DefaultLlmGateway.recordSpend), and stops there -
                    // the rollups take only what has tokens to add. It returned before logging until
                    // 2026-09-29, so an endpoint that reports no usage made no call at all, as far as
                    // the log that now also feeds the spending limit could tell.
                    if (usage == null || usage == TokenUsage.EMPTY) {
                        callLog.record(SpendContext.current(), LlmRole.EMBEDDING.name(),
                                callProvider, s.getModel(), TokenUsage.EMPTY, 0);
                        return;
                    }
                    // THE DISPLAY NAME, not s.getProvider(), which is an instance ID. Pricing is
                    // keyed on the name, so the id priced every embedding row at $0 - in the one
                    // ledger GlobalQuotaService reads, for the highest-volume call in the product.
                    // The attributed row three lines down always used the name, so the two ledgers
                    // disagreed about the deployment's largest line item until 2026-09-16.
                    String provider = factory.providerDisplayName(s.getProvider());
                    platformSpendService.record(PlatformService.EMBEDDING, usage,
                            provider, s.getModel());
                    // AND against whoever it is for. Embedding is the highest-volume call in the
                    // product and, at ingest, the one somebody can run up by registering a
                    // hundred sources - so "who" is exactly the question worth being able to ask
                    // of it. SyncService puts the connector's OWNER in the context; a search-time
                    // embedding carries whoever is searching.
                    double cost = pricing.costUsd(provider, s.getModel(), usage);
                    attributedSpend.record(SpendContext.current(), LlmRole.EMBEDDING.name(),
                            provider, s.getModel(), usage, cost);
                    // And one row in the per-call ledger, from the same numbers, so the detail view
                    // and the rollup can never tell different stories about the same batch.
                    callLog.record(SpendContext.current(), LlmRole.EMBEDDING.name(),
                            provider, s.getModel(), usage, cost);
                } catch (Exception e) {
                    log.debug("[PlatformSpend] Could not record embedding spend: {}", e.getMessage());
                }
            }
        };
    }
}
