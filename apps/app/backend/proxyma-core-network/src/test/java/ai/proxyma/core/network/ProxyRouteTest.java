package ai.proxyma.core.network;

import ai.proxyma.core.settings.dto.ProxySettings;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.system.util.Failures;
import ai.proxyma.core.webcontent.WebProxyProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A failure to connect says which way the request went.
 *
 * <p>The 0.36.0 feedback: a LiteLLM gateway reachable only through the proxy, and a No proxy list
 * whose ".corp.example"-style suffix, added for two servers on the company network, also covered the
 * gateway. Every request to it went direct, the local DNS did not know the name, and the streamed
 * chat turn reported "WebClientRequestException" - no message anywhere in its chain - while the
 * provider Test said "Connection refused". The route was right by the list; what was wrong is that
 * nothing said so.
 *
 * <p>Here the real streaming client is built from Settings > Network as a user sets it, with a proxy
 * that is not listening. ".invalid" never resolves (RFC 2606), so a bypassed name fails exactly as
 * the gateway's did - an UnresolvedAddressException on a direct connect - without depending on any
 * real host.
 */
class ProxyRouteTest {

    private int deadProxyPort;

    @BeforeEach
    void freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            deadProxyPort = s.getLocalPort(); // closed again: nothing listens there
        }
    }

    private WebClient streamingClient(String noProxy) {
        ProxySettings proxy = new ProxySettings();
        proxy.setMode(ProxySettings.Mode.custom.name());
        proxy.setHttpsProxy("http://127.0.0.1:" + deadProxyPort);
        proxy.setNoProxy(noProxy);
        SettingsService settings = mock(SettingsService.class);
        when(settings.getSettings(eq(ProxySettings.PREFIX), eq(ProxySettings.class))).thenReturn(proxy);
        when(settings.getSettingsFields(eq(ProxySettings.PREFIX))).thenReturn(Map.of("mode", "custom"));
        return new RestClientConfig(settings, false, true, List.of()).externalWebClientBuilder().build();
    }

    private static Throwable get(WebClient client, String url) {
        return catchThrowable(() -> client.get().uri(url).retrieve().bodyToMono(String.class)
                .block(Duration.ofSeconds(20)));
    }

    private static boolean inChain(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (type.isInstance(t)) return true;
        }
        return false;
    }

    @Test
    @DisplayName("a host sent direct by No proxy that does not resolve says it went direct, and why")
    void bypassedHostThatDoesNotResolve() {
        Throwable failure = get(streamingClient("localhost,127.0.0.1,gpu1.gateway.invalid,.gateway.invalid"),
                "http://llm.gateway.invalid/v1/messages");

        assertThat(failure).isInstanceOf(WebClientRequestException.class);
        // The failure the feedback's log shows, still in the chain for every check that walks it.
        assertThat(inChain(failure, UnresolvedAddressException.class)).isTrue();
        assertThat(failure.getMessage())
                .contains("llm.gateway.invalid")
                .contains("went directly, not through the proxy")
                .contains("No proxy");
        // And what the chat shows: a sentence, not a class name.
        String shown = Failures.describe(failure);
        assertThat(shown).startsWith("The server's name could not be resolved");
        assertThat(shown).contains("take it off No proxy in Settings > Network");
    }

    @Test
    @DisplayName("a host sent to the proxy that cannot be reached there says it went through the proxy")
    void proxiedHostThatCannotConnect() {
        Throwable failure = get(streamingClient(".gateway.invalid"), "http://api.elsewhere.invalid/v1/messages");

        assertThat(failure).isInstanceOf(WebClientRequestException.class);
        assertThat(failure.getMessage()).contains("went through the proxy");
    }

    @Test
    @DisplayName("a failure after the connection came up says nothing about the route")
    void notAConnectFailure() {
        WebClientRequestException tls = new WebClientRequestException(
                new javax.net.ssl.SSLException("handshake failed"), HttpMethod.POST,
                URI.create("https://llm.gateway.invalid/v1/messages"), new HttpHeaders());

        assertThat(RestClientConfig.withRoute(tls, Set.of(".gateway.invalid"))).isSameAs(tls);
    }

    @Test
    @DisplayName("the route follows the same list as the clients, and no proxy means nothing to say")
    void routeOf() {
        WebProxyProvider proxy = provider(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 3128)),
                ".gateway.invalid");
        assertThat(ProxyRoute.of(proxy, "llm.gateway.invalid")).isEqualTo(ProxyRoute.BYPASSED);
        assertThat(ProxyRoute.of(proxy, "api.anthropic.com")).isEqualTo(ProxyRoute.PROXY);
        assertThat(ProxyRoute.of(provider(Proxy.NO_PROXY, ".gateway.invalid"), "llm.gateway.invalid"))
                .isEqualTo(ProxyRoute.NONE);
        assertThat(ProxyRoute.of(null, "llm.gateway.invalid")).isEqualTo(ProxyRoute.NONE);
        assertThat(ProxyRoute.NONE.note()).isEmpty();
    }

    @Test
    @DisplayName("a refusal on a direct connect keeps its ConnectException")
    void refusedStaysAConnectException() {
        WebClientRequestException refused = new WebClientRequestException(
                new ConnectException("Connection refused"), HttpMethod.GET,
                URI.create("http://llm.gateway.invalid:4000/v1/models"), new HttpHeaders());

        Throwable annotated = RestClientConfig.withRoute(refused, Set.of(".gateway.invalid"));
        assertThat(annotated).isInstanceOf(WebClientRequestException.class);
        assertThat(annotated.getCause()).isInstanceOf(ConnectException.class);
        assertThat(annotated.getCause().getCause()).isSameAs(refused.getCause());
        assertThat(annotated.getMessage()).contains("went directly, not through the proxy");
    }

    private static WebProxyProvider provider(Proxy proxy, String noProxy) {
        return new WebProxyProvider() {
            @Override public Proxy getJavaProxy() { return proxy; }
            @Override public String getNoProxyHosts() { return noProxy; }
            @Override public boolean bypassesProxy(String host) {
                return RestClientConfig.shouldBypass(host, RestClientConfig.parseNoProxyHosts(noProxy));
            }
        };
    }
}
