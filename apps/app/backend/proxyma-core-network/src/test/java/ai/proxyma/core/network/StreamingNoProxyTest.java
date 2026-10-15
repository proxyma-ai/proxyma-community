package ai.proxyma.core.network;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The streaming client bypasses the proxy for the same hosts the blocking client does.
 *
 * <p>The 0.27.1 feedback: chat could not connect through the proxy while the provider test was fine.
 * The test is a blocking call, through RestClientConfig's own ProxySelector, which reads noProxy as
 * the comma list Settings > Network asks for. A chat turn STREAMS, through Reactor Netty, which was
 * handed the same text as a regex - so "localhost,127.0.0.1,llm.corp" matched no host, every turn
 * went to the proxy for a model the proxy could not or would not reach, and "*.corp" did not
 * compile. Here a real Netty client is given a proxy that is not listening: a request reaches the
 * local server only if the bypass decision lets it go direct.
 */
class StreamingNoProxyTest {

    private HttpServer server;
    private int deadProxyPort;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            byte[] body = "{\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (ServerSocket s = new ServerSocket(0)) {
            deadProxyPort = s.getLocalPort(); // closed again: nothing listens there
        }
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String get(String host, String noProxy) {
        HttpClient client = HttpClient.create()
                .proxy(spec -> spec.type(ProxyProvider.Proxy.HTTP)
                        .host("127.0.0.1").port(deadProxyPort)
                        .nonProxyHostsPredicate(RestClientConfig.bypassPredicate(noProxy)))
                .responseTimeout(Duration.ofSeconds(5));
        return client.get()
                .uri("http://" + host + ":" + server.getAddress().getPort() + "/v1/models")
                .responseContent().aggregate().asString()
                .block(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("a comma list, as Settings asks for it, sends a listed host direct")
    void commaListBypasses() {
        assertThat(get("127.0.0.1", "proxy.corp.example,127.0.0.1,llm.corp.example")).contains("data");
    }

    @Test
    @DisplayName("with no list set, loopback goes direct - as on the blocking client")
    void unsetListBypassesLoopback() {
        assertThat(get("127.0.0.1", null)).contains("data");
        assertThat(get("localhost", null)).contains("data");
    }

    @Test
    @DisplayName("a host the list does not name goes to the proxy")
    void unlistedHostUsesTheProxy() {
        // Not loopback, which is direct whatever the list says: the refusal is the dead proxy's.
        assertThatThrownBy(() -> get("unlisted.example", "llm.corp.example"))
                .hasMessageContaining(String.valueOf(deadProxyPort));
    }

    @Test
    @DisplayName("wildcard and leading-dot entries match, and nothing fails to compile")
    void wildcardEntries() {
        var bypass = RestClientConfig.bypassPredicate("*.corp.example; .lab.example | localhost");
        assertThat(bypass.test(InetSocketAddress.createUnresolved("llm.corp.example", 443))).isTrue();
        assertThat(bypass.test(InetSocketAddress.createUnresolved("gpu.lab.example", 8000))).isTrue();
        assertThat(bypass.test(InetSocketAddress.createUnresolved("localhost", 11434))).isTrue();
        assertThat(bypass.test(InetSocketAddress.createUnresolved("api.openai.com", 443))).isFalse();
    }
}
