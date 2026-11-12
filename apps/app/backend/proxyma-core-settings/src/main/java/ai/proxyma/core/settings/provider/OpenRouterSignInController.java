package ai.proxyma.core.settings.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * SIGN IN WITH OPENROUTER's three doors (OpenRouterSignIn): the window begins one and follows it - both behind the
 * single-user editions' access gate - and OpenRouter sends the person's browser back to the callback, which AccessGate
 * leaves open (that browser carries no session) and which answers with a page of fixed sentences under the closing-page
 * CSP (SecurityHeadersConfig.CALLBACK_PAGES).
 */
@RestController
@RequestMapping("/api/v1/providers/openrouter")
public class OpenRouterSignInController {

    private final OpenRouterSignIn signIn;
    private final String redirectBase;

    public OpenRouterSignInController(OpenRouterSignIn signIn, @Value("${proxyma.oauth.redirect-base:}") String redirectBase) {
        this.signIn = signIn;
        this.redirectBase = redirectBase;
    }

    /** A sign-in for the window to open in the person's browser: {@code {authorizeUrl}}. */
    @PostMapping("/sign-in")
    public Mono<ResponseEntity<Map<String, Object>>> begin(ServerWebExchange exchange) {
        String redirect = redirectUri(exchange.getRequest().getURI());
        return Mono.fromCallable(() -> {
            try {
                return ResponseEntity.ok(Map.<String, Object>of("authorizeUrl", signIn.begin(redirect)));
            } catch (IllegalStateException e) {
                return ResponseEntity.badRequest().body(Map.<String, Object>of("error", e.getMessage()));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** Where the last sign-in stands, which the window asks while it waits. */
    @GetMapping("/sign-in")
    public Mono<ResponseEntity<OpenRouterSignIn.Status>> status() {
        return Mono.fromCallable(() -> ResponseEntity.ok(signIn.status())).subscribeOn(Schedulers.boundedElastic());
    }

    /** OpenRouter's return: the key exchanged and kept, and a page that says how it went. */
    @GetMapping("/callback")
    public Mono<Void> callback(ServerWebExchange exchange, @RequestParam(required = false) String code,
                               @RequestParam(required = false) String error) {
        return Mono.fromCallable(() -> signIn.complete(code, error)).subscribeOn(Schedulers.boundedElastic())
                .flatMap(outcome -> {
                    var response = exchange.getResponse();
                    response.setStatusCode(outcome == OpenRouterSignIn.Outcome.SIGNED_IN ? HttpStatus.OK : HttpStatus.BAD_REQUEST);
                    response.getHeaders().setContentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8));
                    response.getHeaders().setCacheControl("no-store");
                    byte[] bytes = page(outcome).getBytes(StandardCharsets.UTF_8);
                    return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
                });
    }

    /** The page the person's browser ends on: fixed sentences only, nothing from the request. */
    static String page(OpenRouterSignIn.Outcome outcome) {
        String title = switch (outcome) {
            case SIGNED_IN -> "Signed in to OpenRouter";
            case DENIED -> "Sign-in was declined";
            case EXPIRED -> "This sign-in has expired";
            case FAILED -> "Sign-in failed";
        };
        String body = switch (outcome) {
            case SIGNED_IN -> "Proxyma can use your OpenRouter account now. You can close this tab and return to Proxyma.";
            case DENIED -> "Nothing was connected. You can close this tab and try again from Proxyma.";
            case EXPIRED -> "Start the sign-in again from Proxyma. A sign-in link works once, for ten minutes.";
            case FAILED -> "The sign-in was not completed. Try again from Proxyma.";
        };
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>Proxyma - " + title + "</title>"
                + "<style>body{font-family:system-ui,sans-serif;background:#f2f4f5;color:#0f1215;margin:0;display:grid;"
                + "place-items:center;min-height:100vh}main{max-width:28rem;padding:2rem}h1{font-size:1.25rem;margin:0 0 .5rem}"
                + "p{margin:0;line-height:1.5;color:#353b42}@media(prefers-color-scheme:dark){body{background:#1f1f1f;color:#f5f6f7}"
                + "p{color:#cfd0d2}}</style></head><body><main><h1>" + title + "</h1><p>" + body + "</p></main>"
                + (outcome == OpenRouterSignIn.Outcome.SIGNED_IN ? "<script>setTimeout(function(){window.close()},1500)</script>" : "")
                + "</body></html>";
    }

    String redirectUri(URI requestUri) {
        if (redirectBase != null && !redirectBase.isBlank()) {
            return redirectBase.trim().replaceAll("/+$", "") + OpenRouterSignIn.CALLBACK_PATH;
        }
        String scheme = requestUri.getScheme() == null ? "https" : requestUri.getScheme();
        int port = requestUri.getPort();
        boolean defaultPort = port < 0 || ("https".equalsIgnoreCase(scheme) && port == 443)
                || ("http".equalsIgnoreCase(scheme) && port == 80);
        return scheme + "://" + requestUri.getHost() + (defaultPort ? "" : ":" + port) + OpenRouterSignIn.CALLBACK_PATH;
    }
}
