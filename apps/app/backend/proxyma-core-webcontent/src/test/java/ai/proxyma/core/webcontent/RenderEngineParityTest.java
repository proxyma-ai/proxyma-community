package ai.proxyma.core.webcontent;

import org.htmlunit.WebClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
import java.net.Proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The render engines reach the web as every other client does (ingest-11, -27): certificates
 * validated, and the proxy skipped for exactly the hosts the noProxy list names.
 */
class RenderEngineParityTest {

    private static final class CorporateProxy implements WebProxyProvider {
        @Override public Proxy getJavaProxy() {
            return new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.corp.example", 3128));
        }
        @Override public String getNoProxyHosts() { return "corp.example"; }
        // Stands in for RestClientConfig.shouldBypass: a bare name and every name under it.
        @Override public boolean bypassesProxy(String host) {
            return host.equals("corp.example") || host.endsWith(".corp.example");
        }
    }

    @SuppressWarnings("unchecked")
    private static JsRenderService htmlUnit(WebProxyProvider provider) {
        ObjectProvider<WebProxyProvider> providers = mock(ObjectProvider.class);
        when(providers.getIfAvailable()).thenReturn(provider);
        return new JsRenderService(providers);
    }

    @Test
    @DisplayName("ingest-11: HtmlUnit validates certificates, with the JVM's default trust")
    void htmlUnitValidates() throws Exception {
        try (WebClient client = new WebClient()) {
            htmlUnit(null).configure(client, "https://example.org/");

            assertThat(client.getOptions().isUseInsecureSSL()).isFalse();
            assertThat(client.getOptions().getSSLContext()).isSameAs(SSLContext.getDefault());
        }
    }

    @Test
    @DisplayName("ingest-27: HtmlUnit goes direct to an intranet host under a bare noProxy name, and through the proxy elsewhere")
    void htmlUnitFollowsTheSharedBypassRule() {
        JsRenderService render = htmlUnit(new CorporateProxy());
        try (WebClient intranet = new WebClient(); WebClient internet = new WebClient()) {
            render.configure(intranet, "https://wiki.corp.example/page");
            render.configure(internet, "https://example.org/page");

            assertThat(intranet.getOptions().getProxyConfig().getProxyHost()).isNull();
            assertThat(internet.getOptions().getProxyConfig().getProxyHost()).isEqualTo("proxy.corp.example");
            assertThat(internet.getOptions().getProxyConfig().getProxyPort()).isEqualTo(3128);
        }
    }

    @Test
    @DisplayName("ingest-27: a noProxy list is written as Chromium reads it, meaning what it means to every other client")
    void chromiumBypass() {
        assertThat(PlaywrightRenderService.chromiumBypass("corp.example, .internal;*.lab 10.* *"))
                .isEqualTo("corp.example,*.corp.example,internal,*.internal,lab,*.lab,10.*,*");
        assertThat(PlaywrightRenderService.chromiumBypass(null)).isEmpty();
        assertThat(PlaywrightRenderService.chromiumBypass("  ")).isEmpty();
    }
}
