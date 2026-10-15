package ai.proxyma.core.provider.gateway;

import ai.proxyma.core.common.spend.SpendSubject;
import ai.proxyma.core.provider.adapter.LlmProviderAdapter;
import ai.proxyma.core.provider.service.LlmProviderFactory;
import ai.proxyma.core.settings.dto.RerankingSettings;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import ai.proxyma.core.settings.provider.ResolvedProvider;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.settings.usage.AttributedSpendService;
import ai.proxyma.core.settings.usage.GlobalQuotaExceededException;
import ai.proxyma.core.settings.usage.GlobalQuotaGate;
import ai.proxyma.core.settings.usage.GlobalQuotaService;
import ai.proxyma.core.settings.usage.LlmCallLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The monthly spending limit is enforced IN the gateway (2026-09-29). It was checked at each call
 * site, and the sites that forgot - the embedding-cosine rerank tier and re-index embeddings among
 * them - spent past a limit the deployment believed it had.
 */
class GatewayBudgetTest {

    private static final LlmCaller CALLER = LlmCaller.of(LlmRole.RERANK, SpendSubject.user("u-1"));

    private LlmProviderFactory factory;
    private LlmProviderAdapter adapter;
    private EmbeddingModel raw;
    private DefaultLlmGateway gateway;

    @BeforeEach
    void setUp() {
        factory = mock(LlmProviderFactory.class);
        adapter = mock(LlmProviderAdapter.class);
        raw = mock(EmbeddingModel.class);
        SettingsService settings = mock(SettingsService.class);
        RerankingSettings rerank = new RerankingSettings();
        rerank.setProvider("p");
        rerank.setModel("m");
        when(settings.getSettings(eq(RerankingSettings.PREFIX), eq(RerankingSettings.class))).thenReturn(rerank);
        ResolvedProvider provider = mock(ResolvedProvider.class);
        when(provider.name()).thenReturn("Provider");
        when(factory.getAdapter(anyString())).thenReturn(adapter);
        when(factory.resolveProvider(anyString())).thenReturn(Optional.of(provider));
        when(adapter.buildEmbeddingModel(any(), anyString())).thenReturn(raw);

        gateway = new DefaultLlmGateway(factory, settings,
                mock(AttributedSpendService.class), mock(LlmCallLogService.class), mock(ModelPricingService.class));
        GlobalQuotaService quota = mock(GlobalQuotaService.class);
        when(quota.status()).thenReturn(new GlobalQuotaService.GlobalQuotaStatus(10, 4, 7, true));
        when(quota.limitUsd()).thenReturn(10.0);
        when(quota.isExhausted()).thenReturn(true);
        gateway.setBudget(new GlobalQuotaGate(quota));
    }

    @Test
    @DisplayName("a metered embedding is refused before the provider is called once the limit is reached")
    void meteredEmbeddingIsRefused() {
        EmbeddingModel metered = gateway.embeddingModel(CALLER, "p", "m");
        assertThrows(GlobalQuotaExceededException.class, () ->
                metered.call(new EmbeddingRequest(List.of("x"), EmbeddingOptions.builder().build())));
        verify(raw, never()).call(any());
    }

    @Test
    @DisplayName("a user's quota is checked for a service charged to the user, and never for one the platform pays")
    void userQuotaFollowsTheCostCenter() {
        GlobalQuotaService open = mock(GlobalQuotaService.class);
        when(open.status()).thenReturn(new GlobalQuotaService.GlobalQuotaStatus(0, 0, 0, false));
        gateway.setBudget(new GlobalQuotaGate(open));
        ai.proxyma.core.settings.usage.UserBudgetGate spent = userId -> {
            throw new ai.proxyma.core.settings.usage.UserBudgetExceededException("monthly", null, "You've used your monthly quota.");
        };
        gateway.setUserBudget(spent);
        ai.proxyma.core.settings.usage.CostCenterService centers = mock(ai.proxyma.core.settings.usage.CostCenterService.class);
        gateway.setCostCenters(centers);

        when(centers.chargedToUser("EMBEDDING")).thenReturn(true);
        LlmCaller embedding = LlmCaller.of(LlmRole.EMBEDDING, SpendSubject.user("u-1"));
        EmbeddingModel userPaid = gateway.embeddingModel(embedding, "p", "m");
        assertThrows(ai.proxyma.core.settings.usage.UserBudgetExceededException.class, () ->
                userPaid.call(new EmbeddingRequest(List.of("x"), EmbeddingOptions.builder().build())));

        when(centers.chargedToUser("EMBEDDING")).thenReturn(false);
        when(raw.call(any())).thenReturn(new org.springframework.ai.embedding.EmbeddingResponse(List.of()));
        gateway.embeddingModel(embedding, "p", "m")
                .call(new EmbeddingRequest(List.of("x"), EmbeddingOptions.builder().build()));
        verify(raw).call(any());
    }

    @Test
    @DisplayName("rerank degrades to no ranking rather than failing the search")
    void rerankDegrades() {
        Optional<LlmGateway.Ranking> ranking = gateway.rerank(CALLER, "q", List.of("a", "b"));
        assertTrue(ranking.isEmpty());
        verify(adapter, never()).rerank(any(), anyString(), any(), anyString());
        verify(raw, never()).call(any());
    }

    @Test
    @DisplayName("the configured embedding model - every ingest and every search - refuses a user past their quota too")
    @SuppressWarnings("unchecked")
    void primaryEmbeddingFollowsTheUserQuota() {
        LlmProviderFactory provider = mock(LlmProviderFactory.class);
        EmbeddingModel configured = mock(EmbeddingModel.class);
        when(provider.getEmbeddingModel()).thenReturn(configured);
        GlobalQuotaService open = mock(GlobalQuotaService.class);
        ai.proxyma.core.settings.usage.CostCenterService centers = mock(ai.proxyma.core.settings.usage.CostCenterService.class);
        when(centers.chargedToUser("EMBEDDING")).thenReturn(true);
        ai.proxyma.core.settings.usage.UserBudgetGate spent = userId -> {
            throw new ai.proxyma.core.settings.usage.UserBudgetExceededException("monthly", null, "You've used your monthly quota.");
        };
        org.springframework.beans.factory.ObjectProvider<ai.proxyma.core.settings.usage.UserBudgetGate> gates =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(gates.getIfAvailable()).thenReturn(spent);
        org.springframework.beans.factory.ObjectProvider<ai.proxyma.core.settings.usage.CostCenterService> centerProvider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(centerProvider.getIfAvailable()).thenReturn(centers);

        EmbeddingModel primary = new ai.proxyma.core.provider.config.EmbeddingModelConfig().embeddingModel(provider,
                mock(ai.proxyma.core.settings.usage.PlatformSpendService.class), mock(AttributedSpendService.class),
                mock(LlmCallLogService.class), mock(ModelPricingService.class), new GlobalQuotaGate(open),
                mock(SettingsService.class), gates, centerProvider);

        assertThrows(ai.proxyma.core.settings.usage.UserBudgetExceededException.class, () ->
                ai.proxyma.core.common.spend.SpendContext.with(SpendSubject.user("u-1"), () ->
                        primary.call(new EmbeddingRequest(List.of("x"), EmbeddingOptions.builder().build()))));
        verify(configured, never()).call(any());

        // A service the platform pays for is never refused for it.
        when(centers.chargedToUser("EMBEDDING")).thenReturn(false);
        when(configured.call(any())).thenReturn(new org.springframework.ai.embedding.EmbeddingResponse(List.of()));
        ai.proxyma.core.common.spend.SpendContext.with(SpendSubject.user("u-1"), () ->
                primary.call(new EmbeddingRequest(List.of("x"), EmbeddingOptions.builder().build())));
        verify(configured).call(any());
    }

    @Test
    @DisplayName("a workflow's own model is available when ITS instance is, whatever the chat role says")
    void overrideAvailabilityIsTheOverridesOwn() {
        SettingsService settings = mock(SettingsService.class);
        ai.proxyma.core.settings.dto.ChatSettings chat = new ai.proxyma.core.settings.dto.ChatSettings();
        when(settings.getSettings(eq(ai.proxyma.core.settings.dto.ChatSettings.PREFIX),
                eq(ai.proxyma.core.settings.dto.ChatSettings.class))).thenReturn(chat);
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getChatModel()).thenReturn(null); // the chat role is unset
        when(factory.buildChatModel(eq("cheap"), eq("small"), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(mock(org.springframework.ai.chat.model.ChatModel.class));
        when(factory.buildChatModel(eq("deleted"), anyString(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(LlmProviderFactory.ProviderNotConfiguredException.class);
        when(factory.resolveProvider(anyString())).thenReturn(Optional.empty());
        DefaultLlmGateway gw = new DefaultLlmGateway(factory, settings,
                mock(AttributedSpendService.class), mock(LlmCallLogService.class), mock(ModelPricingService.class));

        assertTrue(gw.isAvailable(new LlmGateway.ModelOverride("cheap", "small")));
        assertTrue(!gw.isAvailable(new LlmGateway.ModelOverride("deleted", "small")));
        assertTrue(!gw.isAvailable(LlmRole.CHAT));
    }
}
