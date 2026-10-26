package ai.proxyma.core.provider.gateway;

import ai.proxyma.core.common.spend.SpendSubject;
import ai.proxyma.core.common.usage.TokenUsage;
import ai.proxyma.core.provider.adapter.LlmProviderAdapter;
import ai.proxyma.core.provider.service.LlmProviderFactory;
import ai.proxyma.core.settings.dto.RerankingSettings;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import ai.proxyma.core.settings.provider.ProviderProtocol;
import ai.proxyma.core.settings.provider.ResolvedProvider;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.settings.usage.AttributedSpendService;
import ai.proxyma.core.settings.usage.LlmCallLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ai.proxyma.core.common.spend.SpendContext;
import ai.proxyma.core.settings.dto.ChatSettings;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rerank is billed, so rerank is recorded.
 *
 * <p>THIS IS THE TEST THE COMMENT STOOD IN FOR. {@code recordSpend}'s javadoc claimed that the three
 * formerly-unmetered paths "cannot avoid this method"; native rerank could and did, returning
 * scores with no usage and calling nothing, while a second comment in the same class admitted the
 * embedding-cosine tier was "a known gap". A sentence asserting a property, with nothing enforcing
 * it, is the shape .claude/rules/security.md names as this project's most expensive - and it had
 * produced two ledgers that silently missed an entire class of call.
 *
 * <p>Proved by breaking it: deleting either recordSpend call in DefaultLlmGateway.rerank fails the
 * matching test here.
 */
class GatewaySpendMeteringTest {

    private static final String PROVIDER_NAME = "Local vLLM";
    private static final String MODEL = "bge-reranker-v2-m3";
    private static final SpendSubject USER = SpendSubject.user("u-1");
    private static final LlmCaller CALLER = LlmCaller.of(LlmRole.RERANK, USER);

    private LlmProviderFactory factory;
    private SettingsService settings;
    private AttributedSpendService attributed;
    private LlmCallLogService callLog;
    private ModelPricingService pricing;
    private LlmProviderAdapter adapter;
    private DefaultLlmGateway gateway;

    @BeforeEach
    void setUp() {
        factory = mock(LlmProviderFactory.class);
        settings = mock(SettingsService.class);
        attributed = mock(AttributedSpendService.class);
        callLog = mock(LlmCallLogService.class);
        pricing = mock(ModelPricingService.class);
        adapter = mock(LlmProviderAdapter.class);

        RerankingSettings rerankSettings = new RerankingSettings();
        rerankSettings.setProvider("instance-id-not-a-name");
        rerankSettings.setModel(MODEL);
        when(settings.getSettings(eq(RerankingSettings.PREFIX), eq(RerankingSettings.class)))
                .thenReturn(rerankSettings);
        when(factory.getAdapter(anyString())).thenReturn(adapter);
        when(pricing.costUsd(anyString(), anyString(), any())).thenReturn(0.25);
        when(pricing.price(anyString(), anyString(), any()))
                .thenReturn(new ModelPricingService.Priced(0.25, ai.proxyma.core.common.usage.CostSource.RATE));

        gateway = new DefaultLlmGateway(factory, settings,
                attributed, callLog, pricing);
    }

    private void withNativeRerankProvider() {
        ResolvedProvider provider = mock(ResolvedProvider.class);
        when(provider.nativeRerank()).thenReturn(true);
        when(provider.protocol()).thenReturn(ProviderProtocol.OPENAI_COMPATIBLE);
        when(provider.name()).thenReturn(PROVIDER_NAME);
        when(factory.resolveProvider(anyString())).thenReturn(Optional.of(provider));
    }

    @Test
    @DisplayName("a native rerank call reaches the ledger, with the usage the endpoint reported")
    void nativeRerankIsRecorded() {
        withNativeRerankProvider();
        TokenUsage reported = new TokenUsage(120, 0, 0, 0, 120);
        when(adapter.rerank(any(), anyString(), any(), anyString()))
                .thenReturn(new LlmProviderAdapter.RerankResponse(
                        List.of(new LlmProviderAdapter.RerankResult(0, 9.1)), reported));

        Optional<LlmGateway.Ranking> ranking = gateway.rerank(CALLER, "q", List.of("a"));

        assertTrue(ranking.isPresent());
        // The DISPLAY NAME, never the instance id in settings - pricing is keyed on the name, and
        // an id here would record a row priced at $0 while looking perfectly recorded.
        verify(callLog).record(eq(USER), eq(LlmRole.RERANK.name()),
                eq(PROVIDER_NAME), eq(MODEL), eq(reported), eq(0.25), any());
        verify(attributed).record(eq(USER), eq(LlmRole.RERANK.name()),
                eq(PROVIDER_NAME), eq(MODEL), eq(reported), eq(0.25));
    }

    @Test
    @DisplayName("a rerank endpoint that reports no usage is still logged as a call that happened")
    void rerankWithoutUsageIsStillLogged() {
        withNativeRerankProvider();
        when(adapter.rerank(any(), anyString(), any(), anyString()))
                .thenReturn(new LlmProviderAdapter.RerankResponse(
                        List.of(new LlmProviderAdapter.RerankResult(0, 4.0)), TokenUsage.EMPTY));

        gateway.rerank(CALLER, "q", List.of("a"));

        // Logged: the request was made and billed, whatever the service chose to report.
        verify(callLog).record(eq(USER), eq(LlmRole.RERANK.name()),
                eq(PROVIDER_NAME), eq(MODEL), eq(TokenUsage.EMPTY), eq(0.25), any());
        // NOT rolled up: a zero-token row would inflate the monthly call count while teaching the
        // reader nothing, which is the rule AttributedSpendService already had.
        verify(attributed, never()).record(any(), anyString(), anyString(), anyString(), any(), org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test
    @DisplayName("the embedding-cosine tier records EVERY request - the documents' as well as the query's")
    void cosineRerankRecordsEveryRequest() {
        ResolvedProvider provider = mock(ResolvedProvider.class);
        when(provider.nativeRerank()).thenReturn(false);
        when(provider.protocol()).thenReturn(ProviderProtocol.OPENAI_COMPATIBLE);
        when(provider.name()).thenReturn(PROVIDER_NAME);
        when(factory.resolveProvider(anyString())).thenReturn(Optional.of(provider));
        EmbeddingModel raw = mock(EmbeddingModel.class);
        when(raw.call(any(EmbeddingRequest.class))).thenAnswer(inv -> {
            EmbeddingRequest request = inv.getArgument(0);
            List<Embedding> out = new java.util.ArrayList<>();
            for (int i = 0; i < request.getInstructions().size(); i++) out.add(new Embedding(new float[]{1f, 0f}, i));
            return new EmbeddingResponse(out);
        });
        when(adapter.buildEmbeddingModel(any(), anyString())).thenReturn(raw);

        Optional<LlmGateway.Ranking> ranking = gateway.rerank(CALLER, "q", List.of("a", "b"));

        assertTrue(ranking.isPresent());
        // One row for the query, one for the batch of documents. Until 2026-09-22 the documents'
        // request went to a raw model and only the query's reached the ledger.
        verify(callLog, times(2)).record(eq(USER), eq(LlmRole.RERANK.name()),
                eq(PROVIDER_NAME), eq(MODEL), any(), eq(0.25), any());
    }

    @Test
    @DisplayName("a reranker checkpoint that returns no embeddings is a failure, not 'this provider cannot rank'")
    void aRerankerThatWillNotEmbedThrows() {
        ResolvedProvider provider = mock(ResolvedProvider.class);
        when(provider.nativeRerank()).thenReturn(false);
        when(provider.protocol()).thenReturn(ProviderProtocol.OPENAI_COMPATIBLE);
        when(provider.name()).thenReturn(PROVIDER_NAME);
        when(factory.resolveProvider(anyString())).thenReturn(Optional.of(provider));
        EmbeddingModel raw = mock(EmbeddingModel.class);
        // What Spring AI does with a 200 that carries no `data`.
        when(raw.call(any(EmbeddingRequest.class))).thenThrow(new NullPointerException("EmbeddingList.data() is null"));
        when(adapter.buildEmbeddingModel(any(), anyString())).thenReturn(raw);

        // Empty would send the search to the prompt tier - forty chat requests to a model that
        // cannot answer one. The exception keeps retrieval order, and names the setting to change.
        IllegalStateException failure = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> gateway.rerank(CALLER, "q", List.of("a", "b")));
        assertTrue(failure.getMessage().contains("Serves /v1/rerank"), failure.getMessage());
    }

    @Test
    @DisplayName("a chat call is billed to the caller it was given, never to whatever the thread holds")
    void chatIsBilledToTheExplicitCaller() {
        ChatSettings chat = new ChatSettings();
        chat.setProvider("chat-instance");
        chat.setModel("chat-model");
        when(settings.getSettings(eq(ChatSettings.PREFIX), eq(ChatSettings.class))).thenReturn(chat);
        when(factory.providerDisplayName("chat-instance")).thenReturn("Anthropic");
        when(factory.resolveProvider(anyString())).thenReturn(Optional.empty());
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("hi")))));
        when(factory.getChatModel()).thenReturn(model);

        // A stale subject on the thread - what a pooled thread used to carry from the last request.
        SpendContext.run(SpendSubject.user("someone-else"), () ->
                gateway.chat(LlmCaller.of(LlmRole.CHAT, USER), List.of(new UserMessage("q"))));

        verify(callLog).record(eq(USER), eq(LlmRole.CHAT.name()), eq("Anthropic"), eq("chat-model"), any(), anyDouble(), any());
    }
}
