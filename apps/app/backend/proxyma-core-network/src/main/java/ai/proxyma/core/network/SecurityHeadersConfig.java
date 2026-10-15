package ai.proxyma.core.network;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

/**
 * The Content-Security-Policy and Referrer-Policy every edition's responses carry (swx-06).
 *
 * <p>THE PAGE RENDERS UNTRUSTED CONTENT ON A TRUSTED ORIGIN: model output, indexed documents, an
 * attachment someone sent. Nothing set a policy anywhere, so a script that got into the page - a
 * .docx preview's embedded HTML (swx-01), a sanitizer gap - ran with the user's session, and a
 * model-written image could carry data to any host with no click (swx-02). The policy is what
 * still holds when one of those defenses has a gap:
 *
 * <ul>
 *   <li>{@code script-src 'self'} - the bundle and nothing else. index.html's pre-paint appearance
 *       script lives in /appearance-init.js for this reason, not inline.</li>
 *   <li>{@code style-src 'self' 'unsafe-inline'} - React, docx-preview and the sanitized markdown
 *       all write style attributes. CSS injection is the price, and it is not script.</li>
 *   <li>{@code img-src}/{@code media-src} - this origin, data: and blob: (previews, the chat's own
 *       media). A remote image in an answer does not load.</li>
 *   <li>{@code connect-src 'self'} - the SPA calls its own origin only.</li>
 *   <li>{@code frame-src}/{@code object-src} - 'self' and blob:, which is how a PDF is previewed.</li>
 *   <li>{@code frame-ancestors 'none'} - nobody frames this app, as Spring Security's
 *       X-Frame-Options: DENY already says to older browsers.</li>
 * </ul>
 *
 * <p>Spring Security's own headers stay as they are: nosniff, X-Frame-Options and, behind TLS
 * only, HSTS. A WebFilter rather than {@code headers()} in each security chain so the two
 * editions cannot drift apart: Desktop's chain and Enterprise Server's are separate classes.
 */
@Configuration
public class SecurityHeadersConfig {

    static final String POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data: blob:",
            "media-src 'self' blob:",
            "font-src 'self' data:",
            "connect-src 'self'",
            "frame-src 'self' blob:",
            "object-src 'self' blob:",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    // The MCP sign-in's closing page (McpOAuthController) is written whole by the server from fixed
    // sentences, and closes itself with one inline script. Nothing a user or a model wrote reaches
    // it, so it keeps its inline script and loads nothing else.
    static final String CALLBACK_POLICY = String.join("; ",
            "default-src 'none'",
            "script-src 'unsafe-inline'",
            "style-src 'unsafe-inline'",
            "base-uri 'none'",
            "form-action 'none'",
            "frame-ancestors 'none'");

    static final String MCP_OAUTH_CALLBACK = "/api/v1/mcp-oauth/callback";

    // First of all: every response, refusals from the filters after it included.
    @Bean
    @Order(-400)
    public WebFilter securityHeadersFilter() {
        return (ServerWebExchange exchange, WebFilterChain chain) -> {
            HttpHeaders headers = exchange.getResponse().getHeaders();
            String path = exchange.getRequest().getPath().pathWithinApplication().value();
            headers.set("Content-Security-Policy", MCP_OAUTH_CALLBACK.equals(path) ? CALLBACK_POLICY : POLICY);
            // No Referer anywhere. The app has no use for one, and a link out of a conversation
            // should not tell the site it leads to which conversation it was in.
            headers.set("Referrer-Policy", "no-referrer");
            return chain.filter(exchange);
        };
    }
}
