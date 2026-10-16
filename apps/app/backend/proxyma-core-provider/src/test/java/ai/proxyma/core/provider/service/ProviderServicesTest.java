package ai.proxyma.core.provider.service;

import ai.proxyma.core.provider.adapter.LlmProviderAdapter;
import ai.proxyma.core.settings.dto.ChatSettings;
import ai.proxyma.core.settings.dto.EmbeddingSettings;
import ai.proxyma.core.settings.dto.GraphSettings;
import ai.proxyma.core.settings.dto.GuardrailSettings;
import ai.proxyma.core.settings.dto.OcrSettings;
import ai.proxyma.core.settings.dto.QueryExpansionSettings;
import ai.proxyma.core.settings.dto.RerankingSettings;
import ai.proxyma.core.settings.dto.SubAgentSettings;
import ai.proxyma.core.settings.event.ProviderSettingsChangedEvent;
import ai.proxyma.core.settings.provider.ProviderInstanceService;
import ai.proxyma.core.settings.provider.ProviderProtocol;
import ai.proxyma.core.settings.provider.ResolvedProvider;
import ai.proxyma.core.settings.service.SettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The provider services' own decisions: why a provider is down, and which roles are built.
 *
 * <ul>
 *   <li>The Settings button unwrapped one cause too many before classifying, so every network
 *       failure there read "unknown_error" while the boot check read it correctly; and the JDK
 *       client's timeouts were never "timeout".</li>
 *   <li>Sub-agent, OCR, graph and the guardrail were each missing from the startup build once, and a
 *       role missing from it stays off after every restart. The guardrail's ceiling, read back as
 *       null from a blank field, turned its classifier off with a NullPointerException.</li>
 * </ul>
 */
class ProviderServicesTest {

    private final ProviderConnectivityService connectivity =
            new ProviderConnectivityService(List.of(), mock(ProviderInstanceService.class));

    @Test
    @DisplayName("a network failure is classified the same however deep it arrives")
    void classifyWalksTheChain() {
        ResourceAccessException refused = new ResourceAccessException("I/O error", new ConnectException("refused"));
        assertThat(connectivity.classify(refused).code()).isEqualTo("connection_refused");
        assertThat(connectivity.classify(new ExecutionException(refused)).code()).isEqualTo("connection_refused");
        assertThat(connectivity.classify(new CompletionException(refused)).code()).isEqualTo("connection_refused");
        // What the button used to pass: the cause alone.
        assertThat(connectivity.classify(new ConnectException("refused")).code()).isEqualTo("connection_refused");
        assertThat(connectivity.classify(new ResourceAccessException("x", new UnknownHostException("llm.corp")))
                .code()).isEqualTo("unknown_host");
    }

    @Test
    @DisplayName("a name that does not resolve is unknown_host however the client reports it, and says which way it went")
    void unresolvedNameSaysTheRoute() {
        // As the JDK client reports a name its resolver does not know (0.36.0 feedback): a
        // ConnectException with no message, over an UnresolvedAddressException. It read
        // "Connection refused - is the service running at that address?".
        ResourceAccessException dns = new ResourceAccessException("I/O error on GET request",
                (ConnectException) new ConnectException().initCause(new java.nio.channels.UnresolvedAddressException()));
        assertThat(connectivity.classify(dns).code()).isEqualTo("unknown_host");
        assertThat(connectivity.classify(dns).message()).isEqualTo("Could not resolve host - check the base URL.");

        ProviderConnectivityService behindProxy =
                new ProviderConnectivityService(List.of(), mock(ProviderInstanceService.class));
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<ai.proxyma.core.webcontent.WebProxyProvider> proxy =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(proxy.getIfAvailable()).thenReturn(proxyBypassing(".gateway.example"));
        behindProxy.setProxy(proxy);

        // Sent direct by No proxy: the sentence names the setting that sent it there.
        ProviderConnectivityService.Failure direct = behindProxy.classify(dns, "llm.gateway.example");
        assertThat(direct.code()).isEqualTo("unknown_host");
        assertThat(direct.message()).contains("went directly, not through the proxy")
                .contains("take it off No proxy in Settings > Network");
        ProviderConnectivityService.Failure refused = behindProxy.classify(
                new ResourceAccessException("x", new ConnectException("refused")), "llm.gateway.example");
        assertThat(refused.code()).isEqualTo("connection_refused");
        assertThat(refused.message()).contains("went directly, not through the proxy");

        // Sent to the proxy: a refused connection is the proxy's, and a timeout says it went there.
        assertThat(behindProxy.classify(new ResourceAccessException("x", new ConnectException("refused")),
                "api.anthropic.com").message()).startsWith("Could not connect to the proxy");
        assertThat(behindProxy.classify(new java.util.concurrent.TimeoutException(), "ollama.lab.example").message())
                .isEqualTo("Connection timed out - the request went through the proxy; "
                        + "add the host to No proxy if it is on your network.");
    }

    private static ai.proxyma.core.webcontent.WebProxyProvider proxyBypassing(String noProxy) {
        return new ai.proxyma.core.webcontent.WebProxyProvider() {
            @Override public java.net.Proxy getJavaProxy() {
                return new java.net.Proxy(java.net.Proxy.Type.HTTP, new java.net.InetSocketAddress("127.0.0.1", 3128));
            }
            @Override public String getNoProxyHosts() { return noProxy; }
            @Override public boolean bypassesProxy(String host) {
                return ai.proxyma.core.common.net.NoProxyRules.shouldBypass(host,
                        ai.proxyma.core.common.net.NoProxyRules.parse(noProxy));
            }
        };
    }

    @Test
    @DisplayName("the JDK client's own timeouts are timeouts")
    void jdkTimeoutsAreTimeouts() {
        assertThat(connectivity.classify(new ResourceAccessException("x",
                new java.net.http.HttpTimeoutException("request timed out"))).code()).isEqualTo("timeout");
        assertThat(connectivity.classify(new ResourceAccessException("x",
                new HttpConnectTimeoutException("connect timed out"))).code()).isEqualTo("timeout");
        assertThat(connectivity.classify(new java.util.concurrent.TimeoutException()).code()).isEqualTo("timeout");
    }

    @Test
    @DisplayName("an adapter's IllegalArgumentException is not 'unknown provider' - that is the controller's lookup alone")
    void illegalArgumentIsNotUnknownProvider() {
        assertThat(connectivity.classify(new IllegalArgumentException("Illegal character in authority")).code())
                .isNotEqualTo("unknown_provider");
        assertThat(connectivity.classify(new IllegalStateException("no API key")).code())
                .isEqualTo("missing_configuration");
    }

    // -- Every role built at startup -----------------------------------------------------------------

    private static final String P = UUID.randomUUID().toString();

    @Test
    @DisplayName("every role is built at startup - and a blank guardrail ceiling does not turn the classifier off")
    void everyRoleIsBuilt() {
        SettingsService settings = mock(SettingsService.class);
        ChatSettings chat = new ChatSettings();
        chat.setProvider(P);
        chat.setModel("m");
        when(settings.getSettings(ChatSettings.PREFIX, ChatSettings.class)).thenReturn(chat);
        EmbeddingSettings embedding = new EmbeddingSettings();
        embedding.setProvider(P);
        embedding.setModel("e");
        when(settings.getSettings(EmbeddingSettings.PREFIX, EmbeddingSettings.class)).thenReturn(embedding);
        QueryExpansionSettings qe = new QueryExpansionSettings();
        qe.setProvider(P);
        qe.setModel("m");
        when(settings.getSettings(QueryExpansionSettings.PREFIX, QueryExpansionSettings.class)).thenReturn(qe);
        RerankingSettings rerank = new RerankingSettings();
        rerank.setProvider(P);
        rerank.setModel("m");
        when(settings.getSettings(RerankingSettings.PREFIX, RerankingSettings.class)).thenReturn(rerank);
        SubAgentSettings sub = new SubAgentSettings();
        sub.setProvider(P);
        sub.setModel("small");
        when(settings.getSettings(SubAgentSettings.PREFIX, SubAgentSettings.class)).thenReturn(sub);
        OcrSettings ocr = new OcrSettings();
        ocr.setEngine(OcrSettings.Engine.VISION);
        ocr.setProvider(P);
        ocr.setModel("vision");
        when(settings.getSettings(OcrSettings.PREFIX, OcrSettings.class)).thenReturn(ocr);
        GraphSettings graph = new GraphSettings();
        graph.setEnabled(true);
        graph.setProvider(P);
        graph.setModel("m");
        when(settings.getSettings(GraphSettings.PREFIX, GraphSettings.class)).thenReturn(graph);
        GuardrailSettings guardrail = new GuardrailSettings();
        guardrail.setLlmEnabled(true);
        guardrail.setProvider(P);
        guardrail.setModel("judge");
        guardrail.setMaxOutputTokens(null); // a blank field, read back
        when(settings.getSettings(GuardrailSettings.PREFIX, GuardrailSettings.class)).thenReturn(guardrail);

        LlmProviderAdapter adapter = mock(LlmProviderAdapter.class);
        when(adapter.protocol()).thenReturn(ProviderProtocol.OPENAI_COMPATIBLE);
        when(adapter.buildChatModel(any(), anyString(), any(), anyInt())).thenAnswer(inv -> mock(ChatModel.class));
        when(adapter.buildEmbeddingModel(any(), anyString())).thenReturn(mock(EmbeddingModel.class));
        ProviderInstanceService instances = mock(ProviderInstanceService.class);
        ResolvedProvider resolved = new ResolvedProvider(UUID.fromString(P), "Box",
                ProviderProtocol.OPENAI_COMPATIBLE, "http://box", null, Map.of(), false);
        when(instances.resolve(P)).thenReturn(Optional.of(resolved));

        LlmProviderFactory factory = new LlmProviderFactory(settings, instances, List.of(adapter));
        factory.initAll();

        assertThat(factory.getChatModel()).isNotNull();
        assertThat(factory.getEmbeddingModel()).isNotNull();
        assertThat(factory.getQueryExpansionChatModel()).isNotNull();
        assertThat(factory.getRerankingChatModel()).isNotNull();
        assertThat(factory.getSubAgentChatModel()).isNotNull();
        assertThat(factory.getOcrChatModel()).isNotNull();
        assertThat(factory.getGraphChatModel()).isNotNull();
        assertThat(factory.getGuardrailChatModel()).isNotNull();
        verify(adapter).buildChatModel(any(), eq("judge"), any(), eq(256));

        // A role's own event rebuilds that role, by the settings prefix it is published under.
        factory.reinit(ProviderSettingsChangedEvent.forRole(GuardrailSettings.PREFIX));
        assertThat(factory.getGuardrailChatModel()).isNotNull();
    }
}
