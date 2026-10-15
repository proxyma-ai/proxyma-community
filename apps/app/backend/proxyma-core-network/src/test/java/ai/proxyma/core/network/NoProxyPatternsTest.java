package ai.proxyma.core.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a NO_PROXY list sends direct - the forms people actually write.
 *
 * <p>The 0.27.2 feedback: a company laptop's environment carried a list of the shape below, typed
 * by hand over the years - a typo where 127.0.0.1 was meant, a Windows-style "127.0.0.*", leading
 * dots for whole domains and a bare host. Only exact names and leading "." or "*." were understood,
 * so loopback went to the local proxy and "127.0.0.*" matched nothing.
 */
class NoProxyPatternsTest {

    private static final Set<String> LIST = RestClientConfig.parseNoProxyHosts(
            "127.0.0,localhost,.corp.example,.dmz.example,llm.server.corp.example,127.0.0.*");

    private static boolean direct(String host) {
        return RestClientConfig.shouldBypass(host, LIST);
    }

    @Test
    @DisplayName("loopback is always direct, whatever the list says")
    void loopbackAlwaysDirect() {
        assertThat(RestClientConfig.shouldBypass("127.0.0.1", Set.of())).isTrue();
        assertThat(RestClientConfig.shouldBypass("localhost", Set.of())).isTrue();
        assertThat(RestClientConfig.shouldBypass("::1", Set.of())).isTrue();
        assertThat(RestClientConfig.shouldBypass("[::1]", Set.of())).isTrue();
    }

    @Test
    @DisplayName("a trailing wildcard, as Windows writes it")
    void trailingWildcard() {
        var list = RestClientConfig.parseNoProxyHosts("10.20.*,192.168.1.*");
        assertThat(RestClientConfig.shouldBypass("10.20.3.4", list)).isTrue();
        assertThat(RestClientConfig.shouldBypass("192.168.1.20", list)).isTrue();
        assertThat(RestClientConfig.shouldBypass("192.168.2.8", list)).isFalse();
        assertThat(RestClientConfig.shouldBypass("10.2.3.4", list)).isFalse();
    }

    @Test
    @DisplayName("a leading dot covers the domain and every name under it")
    void leadingDot() {
        assertThat(direct("wiki.corp.example")).isTrue();
        assertThat(direct("a.b.dmz.example")).isTrue();
        assertThat(direct("corp.example")).isTrue();
        assertThat(direct("notcorp.example")).isFalse();
    }

    @Test
    @DisplayName("a bare name covers itself and every name under it, as curl reads it")
    void bareNameCoversSubdomains() {
        var list = RestClientConfig.parseNoProxyHosts("intranet.example");
        assertThat(RestClientConfig.shouldBypass("intranet.example", list)).isTrue();
        assertThat(RestClientConfig.shouldBypass("jira.intranet.example", list)).isTrue();
        assertThat(RestClientConfig.shouldBypass("myintranet.example", list)).isFalse();
    }

    @Test
    @DisplayName("everything else goes to the proxy")
    void othersUseTheProxy() {
        assertThat(direct("api.openai.com")).isFalse();
        assertThat(direct("telemetry.proxyma.ai")).isFalse();
        assertThat(direct("10.0.0.5")).isFalse();
    }

    @Test
    @DisplayName("the streaming client reads the list the same way")
    void streamingPredicateAgrees() {
        var bypass = RestClientConfig.bypassPredicate("127.0.0.*,.corp.example");
        assertThat(bypass.test(java.net.InetSocketAddress.createUnresolved("llm.corp.example", 443))).isTrue();
        assertThat(bypass.test(java.net.InetSocketAddress.createUnresolved("127.0.0.1", 8000))).isTrue();
        assertThat(bypass.test(java.net.InetSocketAddress.createUnresolved("api.openai.com", 443))).isFalse();
    }
}
