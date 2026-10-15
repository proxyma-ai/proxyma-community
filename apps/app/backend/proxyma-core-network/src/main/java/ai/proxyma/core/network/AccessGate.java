package ai.proxyma.core.network;

import ai.proxyma.core.common.ingest.IngestTokenCheck;
import ai.proxyma.core.system.util.AccessCode;
import ai.proxyma.core.system.util.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Who may use a single-user install's API: whoever holds its access code (see {@link AccessCode}).
 *
 * <p>Desktop and the Home Server have no accounts. Until 2026-10-14 the boundary was "who can reach
 * the port" and the Host header decided it - a header every non-browser client writes itself - so
 * anyone on the same network, or another account on the same computer, had the whole API
 * (editions-assembly-01, registry-sync-01, tools-core-05, connector-framework-01). Now every
 * {@code /api/**} and {@code /mcp} request presents one of two credentials or is answered 401:
 *
 * <ul>
 *   <li>the {@value #COOKIE} cookie - HttpOnly, SameSite=Strict, a browser session long - which a
 *       browser gets by presenting the code once: {@code GET /?access=<code>} (how the Desktop
 *       window and the tray open the app, so a desktop user never sees the code) or
 *       {@code POST /api/v1/access} (the "Enter access code" screen);</li>
 *   <li>{@code Authorization: Bearer pxm_...} - the install's token, for MCP clients and scripts.</li>
 * </ul>
 *
 * <p>And one narrow third: a custom source's INGEST token opens that source's ingestion endpoints
 * ({@code /api/v1/ingest/sources/{id}/documents}, and below it) and nothing else - see
 * {@link #ingestSource}. A script feeding one source should not have to carry the access code, which
 * opens everything: the token is made behind this gate by somebody who passed it, is kept only as a
 * hash, is revoked on the source's page, and the endpoints check it again in full.
 *
 * <p>What stays open: the page and its assets (the access screen has to load), the health probes,
 * the handshake itself, and the three OAuth callbacks that come back in the SYSTEM browser with no
 * session - each is authenticated by its own single-use state, exactly as on Enterprise Server.
 *
 * <p>The Host allow-list in WebConfig stays, as what it can actually be: a DNS-rebinding defense.
 */
@Component
@Profile("!server")
public class AccessGate {

    private static final Logger log = LoggerFactory.getLogger(AccessGate.class);

    /** The browser's credential. Named like Enterprise Server's PROXYMA-TOKEN. */
    public static final String COOKIE = "PROXYMA-ACCESS";

    /** The query parameter of the handshake: {@code /?access=<code>}. */
    public static final String QUERY_PARAMETER = "access";

    // A one-time code is minted for a browser URL (the tray's Open in Browser, a Chromium --app
    // window) and redeemed within seconds. Five minutes covers a browser started cold on a machine
    // whose antivirus scans it on the way in.
    static final Duration ONE_TIME_LIFETIME = Duration.ofMinutes(5);

    // A wrong code waits before it is answered, so guessing one costs time as well as luck. The
    // generated code is 256 bits; this is for the operator who chose their own.
    static final Duration REFUSAL_DELAY = Duration.ofMillis(400);

    // Reachable with no credential. Exact paths, never prefixes - a prefix would hand out whatever
    // is added under it later.
    private static final Set<String> OPEN_GETS = Set.of(
            "/api/v1/health", "/api/v1/health/live", "/api/v1/health/ready",
            // The OAuth sign-ins come back as a top-level navigation, often in the system browser
            // on Desktop, and are authenticated by their single-use state (McpOAuthService,
            // OAuth2ConnectionService) - the same reason the server's SecurityConfig permits them. A
            // SameSite=Strict session cookie does not come back on a navigation from the service's
            // own domain, so a callback behind it could never land. (Microsoft 365's own callback is
            // Enterprise Server's alone, and its SecurityConfig's to permit.)
            "/api/v1/mcp-oauth/callback",
            "/api/v1/connections/oauth/callback");

    static final String ACCESS_PATH = "/api/v1/access";

    // The ingestion endpoints of ONE source, exactly - the list, an upsert, the batch, one document.
    // A pattern rather than a prefix, for the reason OPEN_GETS gives: whatever is added under
    // /api/v1/ingest later is not handed to an ingest token by this line.
    private static final Pattern INGEST_ROUTE = Pattern.compile(
            "/api/v1/ingest/sources/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})"
                    + "/documents(?:/[^/]+)?");

    // Who can tell an ingest token from anything else - the registry. Absent in a context without it.
    private ObjectProvider<IngestTokenCheck> ingestTokens;

    @Autowired(required = false)
    void setIngestTokens(ObjectProvider<IngestTokenCheck> ingestTokens) {
        this.ingestTokens = ingestTokens;
    }

    private final String code;
    private final String sessionValue;
    private final String bearerToken;
    /** True when the operator chose the code (PROXYMA_ACCESS_CODE) rather than the install generating one. */
    private final boolean configured;
    private final String storedAt;

    @Value("${proxyma.home-server:false}")
    private boolean homeServer;

    // Code -> expiry. Single use: redeeming one removes it.
    private final Map<String, Instant> oneTimeCodes = new ConcurrentHashMap<>();

    @Autowired
    public AccessGate(AppPaths appPaths, @Value("${proxyma.access.code:}") String configuredCode) {
        this(resolve(configuredCode, appPaths), configuredCode != null && !configuredCode.isBlank(),
                appPaths.dataRoot().resolve(AccessCode.FILE_NAME).toString());
    }

    AccessGate(String code, boolean configured, String storedAt) {
        this.code = code;
        this.sessionValue = AccessCode.sessionValue(code);
        this.bearerToken = AccessCode.bearerToken(code);
        this.configured = configured;
        this.storedAt = storedAt;
    }

    private static String resolve(String configuredCode, AppPaths appPaths) {
        try {
            return AccessCode.resolve(configuredCode, appPaths.dataRoot());
        } catch (IOException e) {
            // Fatal, deliberately. An install that cannot keep its code has no way to let its own
            // window in; starting anyway would mean starting open, which is the bug this replaces.
            throw new UncheckedIOException("Could not create the access code in " + appPaths.dataRoot(), e);
        }
    }

    /** The code itself - handed to the Desktop window, never written to a log. */
    public String code() { return code; }

    /** The install's {@code pxm_} token, for Settings > AI Assistants. */
    public String bearerToken() { return bearerToken; }

    /**
     * A code good for one handshake within {@link #ONE_TIME_LIFETIME}, for a URL handed to a
     * browser - where the long-lived code would stay in the history and, on Linux, in a command
     * line every account on the machine can read.
     */
    public String issueOneTimeCode() {
        Instant now = Instant.now();
        oneTimeCodes.values().removeIf(expiry -> expiry.isBefore(now));
        String oneTime = AccessCode.generate();
        oneTimeCodes.put(oneTime, now.plus(ONE_TIME_LIFETIME));
        return oneTime;
    }

    /** Whether {@code presented} is the code, or a one-time code not yet used (which it then uses up). */
    boolean redeem(String presented) {
        if (presented == null || presented.isBlank()) return false;
        String candidate = presented.trim();
        if (AccessCode.matches(candidate, code)) return true;
        Instant expiry = oneTimeCodes.remove(candidate);
        return expiry != null && expiry.isAfter(Instant.now());
    }

    /** Whether the request carries the access cookie or the bearer token. */
    boolean admits(ServerHttpRequest request) {
        HttpCookie cookie = request.getCookies().getFirst(COOKIE);
        if (cookie != null && AccessCode.matches(cookie.getValue(), sessionValue)) return true;
        String auth = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        return auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)
                && AccessCode.matches(auth.substring(7).trim(), bearerToken);
    }

    /**
     * Whether a request needs no credential. Everything outside {@code /api} and {@code /mcp} is the
     * page and its assets, which the access screen itself is made of.
     */
    static boolean isOpen(ServerHttpRequest request) {
        if (isPage(request)) return true;
        String path = request.getPath().pathWithinApplication().value();
        HttpMethod method = request.getMethod();
        if (HttpMethod.GET.equals(method) && OPEN_GETS.contains(path)) return true;
        if (HttpMethod.POST.equals(method) && ACCESS_PATH.equals(path)) return true;
        // A CORS preflight carries no credentials by definition and runs nothing; CorsWebFilter
        // answers it, and refuses an origin that is not this app's own.
        return HttpMethod.OPTIONS.equals(method)
                && request.getHeaders().containsKey(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD);
    }

    private static boolean isPage(ServerHttpRequest request) {
        String path = request.getPath().pathWithinApplication().value();
        return !path.startsWith("/api/") && !path.startsWith("/mcp");
    }

    /** The cookie a successful handshake sets. No Max-Age: it lasts as long as the browser session. */
    ResponseCookie sessionCookie(ServerHttpRequest request) {
        return ResponseCookie.from(COOKIE, sessionValue)
                .httpOnly(true)
                .sameSite("Strict")
                .secure(isSecure(request))
                .path("/")
                .build();
    }

    // Behind a TLS-terminating proxy the app sees plain http; the proxy's X-Forwarded-Proto says
    // what the browser used. A client lying about it only changes its own cookie.
    private static boolean isSecure(ServerHttpRequest request) {
        if ("https".equalsIgnoreCase(request.getURI().getScheme())) return true;
        return "https".equalsIgnoreCase(request.getHeaders().getFirst("X-Forwarded-Proto"));
    }

    /** The WebFilter body - see WebConfig.accessFilter, which orders it. */
    Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (isPage(request) && HttpMethod.GET.equals(request.getMethod())) {
            String presented = request.getQueryParams().getFirst(QUERY_PARAMETER);
            if (presented != null) return handshake(exchange, presented);
        }
        if (isOpen(request) || admits(request)) return chain.filter(exchange);
        UUID source = ingestSource(request);
        IngestTokenCheck check = ingestTokens == null ? null : ingestTokens.getIfAvailable();
        String bearer = bearer(request);
        if (source != null && check != null && bearer != null) {
            // A database read, so off the event loop.
            return Mono.fromCallable(() -> check.admits(source, bearer))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(ok -> ok ? chain.filter(exchange) : refuse(exchange.getResponse(), "access_required"));
        }
        return refuse(exchange.getResponse(), "access_required");
    }

    /** The source an ingestion API request names, or null for any other request. */
    static UUID ingestSource(ServerHttpRequest request) {
        HttpMethod method = request.getMethod();
        if (!(HttpMethod.GET.equals(method) || HttpMethod.POST.equals(method) || HttpMethod.DELETE.equals(method))) {
            return null;
        }
        Matcher m = INGEST_ROUTE.matcher(request.getPath().pathWithinApplication().value());
        return m.matches() ? UUID.fromString(m.group(1)) : null;
    }

    private static String bearer(ServerHttpRequest request) {
        String auth = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String token = auth.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    // GET /?access=<code>: set the cookie and go to the same page without the code in the address,
    // so it is in neither the history nor a Referer. A wrong code redirects too, without a cookie -
    // the page then shows the access screen, which is the honest answer.
    private Mono<Void> handshake(ServerWebExchange exchange, String presented) {
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();
        boolean admitted = redeem(presented);
        if (admitted) response.addCookie(sessionCookie(request));
        response.setStatusCode(HttpStatus.FOUND);
        // A path, never an absolute URL: one built from the Host header would let that header
        // choose where a browser holding the code is sent.
        response.getHeaders().setLocation(URI.create(request.getPath().pathWithinApplication().value()));
        response.getHeaders().setCacheControl("no-store");
        Mono<Void> done = response.setComplete();
        return admitted ? done : Mono.delay(REFUSAL_DELAY).then(done);
    }

    // The body is JSON with a machine code, like Enterprise Server's 401: an MCP client that parses
    // the body of a refusal fails on an empty one ("SDK auth failed: Failed to parse JSON"). The
    // page recognizes access_required and shows the access screen.
    static Mono<Void> refuse(ServerHttpResponse response, String error) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().setCacheControl("no-store");
        byte[] body = ("{\"error\":\"" + error + "\"}").getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    /**
     * Tells the operator of a Home Server the code, once per start, on the CONSOLE - which is
     * {@code docker compose logs}. Not through the logger: its file is in the data volume and in
     * the log tail a feedback report can attach, and neither should carry it.
     *
     * <p>Desktop says nothing: its window and tray hand the code over by themselves.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void announce() {
        if (!homeServer) return;
        String line = "=".repeat(78);
        StringBuilder text = new StringBuilder().append('\n').append(line).append('\n');
        if (configured) {
            text.append(" Proxyma asks for an access code: the one set in PROXYMA_ACCESS_CODE.\n");
        } else {
            text.append(" Proxyma access code: ").append(code).append('\n')
                    .append(" Open Proxyma in a browser and enter it when asked. It is kept in\n")
                    .append(" ").append(storedAt).append("; to choose your own, set\n")
                    .append(" PROXYMA_ACCESS_CODE (").append(AccessCode.MIN_LENGTH).append(" characters or more) and restart.\n");
        }
        text.append(" AI assistants connecting to /mcp use the token shown in Settings > AI Assistants.\n")
                .append(line).append('\n');
        System.out.println(text);
        log.info("Access: every API call needs the access code or the install's token (see the console)");
    }
}
