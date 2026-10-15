package ai.proxyma.core.network;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * swx-06: every response carries a Content-Security-Policy and a Referrer-Policy, in every edition.
 * The policy is what still holds when another defense has a gap - the .docx preview's embedded HTML
 * (swx-01), an answer's remote image (swx-02) - so its load-bearing directives are pinned one by one.
 */
class SecurityHeadersTest {

    private static HttpHeaders headersFor(String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
        new SecurityHeadersConfig().securityHeadersFilter().filter(exchange, ex -> Mono.empty()).block();
        return exchange.getResponse().getHeaders();
    }

    @Test
    void thePageAndTheApiCarryTheStrictPolicy() {
        for (String path : java.util.List.of("/", "/api/v1/chat/conversations/x/files/report.html", "/assets/a.js")) {
            String csp = headersFor(path).getFirst("Content-Security-Policy");
            assertThat(csp).as(path)
                    .contains("script-src 'self'")
                    .contains("img-src 'self' data: blob:")
                    .contains("media-src 'self' blob:")
                    .contains("connect-src 'self'")
                    .contains("frame-ancestors 'none'")
                    .doesNotContain("unsafe-eval")
                    .doesNotContain("script-src 'self' 'unsafe-inline'");
            assertThat(headersFor(path).getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        }
    }

    @Test
    void onlyTheMcpSignInPageMayRunItsOwnInlineScript() {
        String csp = headersFor("/api/v1/mcp-oauth/callback").getFirst("Content-Security-Policy");
        assertThat(csp).contains("default-src 'none'").contains("script-src 'unsafe-inline'").contains("frame-ancestors 'none'");
    }
}
