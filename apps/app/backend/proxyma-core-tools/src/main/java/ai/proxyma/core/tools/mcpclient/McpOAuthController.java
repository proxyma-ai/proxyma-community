package ai.proxyma.core.tools.mcpclient;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;

/**
 * Signing an MCP server connection in. Two endpoints:
 *
 * <ul>
 *   <li>{@code POST /api/v1/connections/instances/{id}/mcp-sign-in} - the caller's own connection;
 *       answers with the address to open. The page opens it in a new window (Desktop: the system
 *       browser) and watches the connection until it says signed in.</li>
 *   <li>{@code GET /api/v1/mcp-oauth/callback} - where the authorization server sends the browser
 *       back. permitAll in the security configuration, authenticated by its single-use state (see
 *       McpOAuthService). It answers with a short page saying how it went, carrying no text from
 *       the request - a page that repeated what it was given would say whatever an attacker wrote.</li>
 * </ul>
 */
@RestController
public class McpOAuthController {

    private final McpOAuthService oauth;

    public McpOAuthController(McpOAuthService oauth) {
        this.oauth = oauth;
    }

    @PostMapping("/api/v1/connections/instances/{id}/mcp-sign-in")
    public Mono<ResponseEntity<Map<String, Object>>> start(@PathVariable UUID id, ServerWebExchange exchange) {
        String redirectUri = redirectUri(exchange.getRequest().getURI());
        return exchange.getPrincipal().map(Principal::getName).defaultIfEmpty("")
                .flatMap(uid -> Mono.fromCallable(() -> ResponseEntity.ok(
                                Map.<String, Object>of("authorizeUrl", oauth.start(uid, id, redirectUri))))
                        .subscribeOn(Schedulers.boundedElastic()))
                .onErrorResume(McpOAuthService.McpSignInException.class, e -> Mono.just(
                        ResponseEntity.badRequest().body(Map.of("error", e.getMessage()))));
    }

    @GetMapping(value = McpOAuthService.CALLBACK_PATH, produces = MediaType.TEXT_HTML_VALUE)
    public Mono<ResponseEntity<String>> callback(@RequestParam(required = false) String state,
                                                 @RequestParam(required = false) String code,
                                                 @RequestParam(required = false) String error) {
        return Mono.fromCallable(() -> oauth.complete(state, code, error))
                .subscribeOn(Schedulers.boundedElastic())
                .map(outcome -> ResponseEntity.status(outcome == McpOAuthService.Outcome.SIGNED_IN ? HttpStatus.OK : HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.TEXT_HTML)
                        .body(page(outcome)));
    }

    // This installation's callback, at the address the page reached it by.
    static String redirectUri(URI requestUri) {
        String scheme = requestUri.getScheme() == null ? "https" : requestUri.getScheme();
        int port = requestUri.getPort();
        boolean defaultPort = port < 0 || ("https".equalsIgnoreCase(scheme) && port == 443)
                || ("http".equalsIgnoreCase(scheme) && port == 80);
        return scheme + "://" + requestUri.getHost() + (defaultPort ? "" : ":" + port) + McpOAuthService.CALLBACK_PATH;
    }

    static String page(McpOAuthService.Outcome outcome) {
        String title = switch (outcome) {
            case SIGNED_IN -> "Signed in";
            case DENIED -> "Sign-in was declined";
            case EXPIRED -> "This sign-in has expired";
            case FAILED -> "Sign-in failed";
        };
        String body = switch (outcome) {
            case SIGNED_IN -> "Proxyma can now use this MCP server. You can close this window and return to Proxyma.";
            case DENIED -> "Nothing was connected. You can close this window and try again from Settings > Connections.";
            case EXPIRED -> "Start the sign-in again from Settings > Connections. A sign-in link works once, for ten minutes.";
            case FAILED -> "The MCP server did not complete the sign-in. Try again from Settings > Connections.";
        };
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>Proxyma - " + title + "</title>"
                + "<style>body{font-family:system-ui,sans-serif;background:#f6f8f8;color:#14262b;margin:0;display:grid;"
                + "place-items:center;min-height:100vh}main{max-width:28rem;padding:2rem}h1{font-size:1.25rem;margin:0 0 .5rem}"
                + "p{margin:0;line-height:1.5;color:#3b4c50}@media(prefers-color-scheme:dark){body{background:#0f1a1d;color:#e6eef0}"
                + "p{color:#a9bbbf}}</style></head><body><main><h1>" + title + "</h1><p>" + body + "</p></main>"
                + (outcome == McpOAuthService.Outcome.SIGNED_IN ? "<script>setTimeout(function(){window.close()},1500)</script>" : "")
                + "</body></html>";
    }
}
