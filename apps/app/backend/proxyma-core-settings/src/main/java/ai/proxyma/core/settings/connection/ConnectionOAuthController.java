package ai.proxyma.core.settings.connection;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.spi.ConnectionPlugin;
import ai.proxyma.core.connector.spi.ConnectorCatalog;
import ai.proxyma.core.settings.access.CallerRoles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The browser-facing half of an OAuth2 sign-in for a connection - every service that declares one
 * (OAuth2ConnectionService), Microsoft 365 apart, which keeps its own.
 *
 * <p>The connection is created first, like any other. On Enterprise Server its "Sign in with ..."
 * button then NAVIGATES to {@code /instances/{id}/oauth/start}, which answers 302 to the service. The
 * service sends the browser back to {@code /oauth/callback}, which answers 302 to the SPA with a CODE
 * and nothing else - a link that carried its own text would let anyone make this product's page show
 * what they wrote.
 *
 * <p>On Desktop and Home Server the button asks {@code POST /instances/{id}/oauth/begin} for the
 * address and OPENS it in a new window - on Desktop the system browser, as any app signs in - and the
 * page watches the connection until it reads signed in. That flow is unbound
 * (OAuth2ConnectionService's class note), and its callback answers with a short page of its own
 * ({@link #page}) rather than the SPA, which is behind the access gate in that browser.
 *
 * <p><b>{@code /oauth/callback} is permitAll in the security configuration, and that is not a
 * relaxation</b> - the same reasoning as Microsoft's: a session can expire while the person is at
 * the service's sign-in, and the callback is authenticated by a single-use state and the cookie that
 * binds the flow to the browser that started it instead.
 */
@RestController
@RequestMapping("/api/v1/connections")
public class ConnectionOAuthController {

    private static final Logger log = LoggerFactory.getLogger(ConnectionOAuthController.class);

    private final OAuth2ConnectionService oauth;
    private final ConnectorCatalog catalog;
    private final boolean multiUser;
    // The address the service sends the browser back to, when the request's own is not the one the
    // OAuth app was registered with (a server behind a proxy that rewrites the host).
    private final String redirectBase;

    public ConnectionOAuthController(OAuth2ConnectionService oauth, ConnectorCatalog catalog,
                                     @Value("${proxyma.multi-user:false}") boolean multiUser,
                                     @Value("${proxyma.oauth.redirect-base:}") String redirectBase) {
        this.oauth = oauth;
        this.catalog = catalog;
        this.multiUser = multiUser;
        this.redirectBase = redirectBase;
    }

    @GetMapping("/instances/{id}/oauth/start")
    public Mono<Void> start(@PathVariable String id, ServerWebExchange exchange) {
        return CallerRoles.userId(exchange)
                .flatMap(uid -> Mono.fromCallable(() -> oauth.start(uid.isEmpty() ? null : uid, UUID.fromString(id),
                                redirectUri(exchange.getRequest().getURI())))
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(flow -> {
                    setFlowCookie(exchange, flow.browserBinding());
                    return redirect(exchange, flow.authorizeUrl());
                })
                .onErrorResume(e -> {
                    boolean notConfigured = e instanceof IllegalStateException;
                    if (!notConfigured) log.warn("[OAuth] A connection's sign-in could not be started: {}", e.getMessage());
                    return redirect(exchange, spaUrl(notConfigured
                            ? OAuth2ConnectionService.Outcome.NOT_CONFIGURED : OAuth2ConnectionService.Outcome.FAILED));
                });
    }

    /**
     * A sign-in to open in the person's own browser - Desktop and Home Server. Behind the access gate
     * like every API; the caller's own connection only.
     */
    @PostMapping("/instances/{id}/oauth/begin")
    public Mono<ResponseEntity<Map<String, Object>>> begin(@PathVariable String id, ServerWebExchange exchange) {
        String redirectUri = redirectUri(exchange.getRequest().getURI());
        return CallerRoles.userId(exchange)
                .flatMap(uid -> Mono.fromCallable(() -> {
                    try {
                        String url = oauth.begin(uid.isEmpty() ? null : uid, UUID.fromString(id), redirectUri);
                        return ResponseEntity.ok(Map.<String, Object>of("authorizeUrl", url));
                    } catch (IllegalArgumentException e) {
                        return ResponseEntity.status(HttpStatus.NOT_FOUND).<Map<String, Object>>body(Map.of("error", e.getMessage()));
                    } catch (IllegalStateException e) {
                        return ResponseEntity.badRequest().<Map<String, Object>>body(Map.of("error", e.getMessage()));
                    }
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    @GetMapping("/oauth/callback")
    public Mono<Void> callback(@RequestParam(required = false) String state,
                               @RequestParam(required = false) String code,
                               @RequestParam(required = false) String error,
                               ServerWebExchange exchange) {
        var cookie = exchange.getRequest().getCookies().getFirst(OAuth2ConnectionService.FLOW_COOKIE);
        String binding = cookie == null ? null : cookie.getValue();
        // A sign-in opened in the person's own browser ends on a page of its own: the app is behind
        // the access gate there. So does one whose flow is gone, when no cookie says this browser
        // started it - on a single-user edition that is the system browser too.
        var unbound = oauth.unboundService(state);
        boolean ownPage = !multiUser && (unbound.isPresent() || binding == null);
        String service = unbound.orElse(null);
        return CallerRoles.userId(exchange)
                .flatMap(sessionUser -> Mono.fromCallable(() -> oauth.complete(new OAuth2ConnectionService.CallbackRequest(
                                state, code, error, binding, sessionUser.isEmpty() ? null : sessionUser)))
                        .subscribeOn(Schedulers.boundedElastic()))
                .onErrorResume(e -> {
                    log.warn("[OAuth] A connection's sign-in callback failed: {}", e.getMessage());
                    return Mono.just(OAuth2ConnectionService.Outcome.FAILED);
                })
                .flatMap(outcome -> {
                    // Cleared on every path: the flow it bound is spent either way.
                    clearFlowCookie(exchange);
                    return ownPage ? writePage(exchange, outcome, service) : redirect(exchange, spaUrl(outcome));
                });
    }

    /**
     * The page a sign-in in the person's own browser ends on - written whole from fixed sentences and
     * the service's name from the catalog, never from the request, so nobody can make it say what they
     * wrote. Its one inline script closes a window the app opened (SecurityHeadersConfig's callback policy).
     */
    static String page(OAuth2ConnectionService.Outcome outcome, String service) {
        String to = service == null ? "the service" : service;
        String title = switch (outcome) {
            case CONNECTED -> "Signed in to " + to;
            case DENIED -> "Sign-in was declined";
            case EXPIRED -> "This sign-in has expired";
            case NOT_CONFIGURED -> "Sign-in is not set up";
            case FAILED -> "Sign-in failed";
            case INCOMPLETE -> "Signed in to " + to + ", with something not allowed";
        };
        String body = switch (outcome) {
            case CONNECTED -> "You can close this tab and return to Proxyma.";
            case DENIED -> "Nothing was connected. You can close this tab and try again from Proxyma.";
            case EXPIRED -> "Start the sign-in again from Proxyma. A sign-in link works once, for ten minutes.";
            case NOT_CONFIGURED -> "The connection has no OAuth client. Add its client ID and secret in Proxyma, then sign in again.";
            case FAILED -> "The sign-in was not completed. Try again from Proxyma.";
            case INCOMPLETE -> "Proxyma needs a permission you did not allow. Return to Proxyma: it says which, and how to sign in again.";
        };
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>Proxyma - " + escape(title) + "</title>"
                + "<style>body{font-family:system-ui,sans-serif;background:#f2f4f5;color:#0f1215;margin:0;display:grid;"
                + "place-items:center;min-height:100vh}main{max-width:28rem;padding:2rem}h1{font-size:1.25rem;margin:0 0 .5rem}"
                + "p{margin:0;line-height:1.5;color:#353b42}@media(prefers-color-scheme:dark){body{background:#1f1f1f;color:#f5f6f7}"
                + "p{color:#cfd0d2}}</style></head><body><main><h1>" + escape(title) + "</h1><p>" + escape(body) + "</p></main>"
                + (outcome == OAuth2ConnectionService.Outcome.CONNECTED ? "<script>setTimeout(function(){window.close()},1500)</script>" : "")
                + "</body></html>";
    }

    // The catalog's names are the product's own, but nothing reaches HTML unescaped.
    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static Mono<Void> writePage(ServerWebExchange exchange, OAuth2ConnectionService.Outcome outcome, String service) {
        var response = exchange.getResponse();
        response.setStatusCode(outcome == OAuth2ConnectionService.Outcome.CONNECTED ? HttpStatus.OK : HttpStatus.BAD_REQUEST);
        response.getHeaders().setContentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8));
        response.getHeaders().setCacheControl("no-store");
        byte[] bytes = page(outcome, service).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    /** Signs one of the caller's own connections out; the connection and its sources stay. */
    @DeleteMapping("/instances/{id}/oauth")
    public Mono<ResponseEntity<Void>> signOut(@PathVariable String id, ServerWebExchange exchange) {
        return CallerRoles.userId(exchange).flatMap(uid -> Mono.fromCallable(() -> {
            try {
                oauth.signOut(uid.isEmpty() ? null : uid, UUID.fromString(id));
                return ResponseEntity.noContent().<Void>build();
            } catch (IllegalArgumentException e) {
                return ResponseEntity.notFound().<Void>build();
            }
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * The services that sign in with OAuth2 here, and whether each has the client a sign-in needs -
     * on Enterprise Server the deployment's; elsewhere every connection brings its own.
     */
    @GetMapping("/oauth/clients")
    public Mono<ResponseEntity<List<Map<String, Object>>>> clients() {
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (ConnectionProtocol p : ConnectionProtocol.values()) {
                ConnectionPlugin plugin = catalog.connectionPlugin(p).orElse(null);
                if (plugin == null || plugin.oauth2() == null) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("protocol", p.name());
                row.put("perConnection", !multiUser);
                row.put("configured", !multiUser || oauth.deploymentClientId(p).isPresent());
                out.add(row);
            }
            return ResponseEntity.ok(out);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public record ClientRequest(String clientId, String clientSecret) {}

    /** Sets the deployment's OAuth client for one service - a Super admin's, Enterprise Server only. */
    @PutMapping("/oauth/clients/{protocol}")
    public Mono<ResponseEntity<?>> saveClient(@PathVariable String protocol, @RequestBody ClientRequest request) {
        if (!multiUser) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of("error",
                    "Each connection brings its own OAuth client in this edition.")));
        }
        return CallerRoles.isSuperAdmin(true).flatMap(allowed -> {
            if (!allowed) {
                return Mono.just(ResponseEntity.status(403).body(Map.of("error",
                        "Only a Super admin can set up a service's sign-in.")));
            }
            return Mono.<ResponseEntity<?>>fromCallable(() -> {
                ConnectionProtocol p = ConnectionProtocol.fromValue(protocol);
                if (p == null) return ResponseEntity.notFound().build();
                try {
                    oauth.saveDeploymentClient(p, request.clientId(), request.clientSecret());
                    return ResponseEntity.noContent().build();
                } catch (IllegalArgumentException | IllegalStateException e) {
                    return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
                }
            }).subscribeOn(Schedulers.boundedElastic());
        });
    }

    // -- plumbing ------------------------------------------------------------------------------

    String redirectUri(URI requestUri) {
        if (redirectBase != null && !redirectBase.isBlank()) {
            return redirectBase.trim().replaceAll("/+$", "") + OAuth2ConnectionService.CALLBACK_PATH;
        }
        String scheme = requestUri.getScheme() == null ? "https" : requestUri.getScheme();
        int port = requestUri.getPort();
        boolean defaultPort = port < 0 || ("https".equalsIgnoreCase(scheme) && port == 443)
                || ("http".equalsIgnoreCase(scheme) && port == 80);
        return scheme + "://" + requestUri.getHost() + (defaultPort ? "" : ":" + port) + OAuth2ConnectionService.CALLBACK_PATH;
    }

    private void setFlowCookie(ServerWebExchange exchange, String value) {
        exchange.getResponse().addCookie(ResponseCookie.from(OAuth2ConnectionService.FLOW_COOKIE, value)
                .httpOnly(true)
                .secure(isHttps(exchange))
                // Lax, not Strict: the callback is a top-level navigation from the service's domain,
                // and a Strict cookie is not sent on one.
                .sameSite("Lax")
                .path(OAuth2ConnectionService.CALLBACK_PATH)
                .maxAge(OAuth2ConnectionService.FLOW_LIFETIME)
                .build());
    }

    private void clearFlowCookie(ServerWebExchange exchange) {
        exchange.getResponse().addCookie(ResponseCookie.from(OAuth2ConnectionService.FLOW_COOKIE, "")
                .httpOnly(true)
                .secure(isHttps(exchange))
                .sameSite("Lax")
                .path(OAuth2ConnectionService.CALLBACK_PATH)
                .maxAge(Duration.ZERO)
                .build());
    }

    private static boolean isHttps(ServerWebExchange exchange) {
        return "https".equalsIgnoreCase(exchange.getRequest().getURI().getScheme());
    }

    private static String spaUrl(OAuth2ConnectionService.Outcome outcome) {
        return "/?connection-oauth=" + outcome.code();
    }

    private static Mono<Void> redirect(ServerWebExchange exchange, String location) {
        exchange.getResponse().setStatusCode(HttpStatus.FOUND);
        exchange.getResponse().getHeaders().set(HttpHeaders.LOCATION, location);
        return exchange.getResponse().setComplete();
    }
}
