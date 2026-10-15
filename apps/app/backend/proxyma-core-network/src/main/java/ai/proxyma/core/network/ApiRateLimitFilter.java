package ai.proxyma.core.network;

import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

// Per-IP (per-token for /mcp/**) sliding-window rate limiter for proxyma-server. Mirrors the
// dependency-free ConcurrentHashMap design already proven in proxyma-license's
// LicenseRateLimitFilter - no external store needed at single-instance scale; one that is ever
// scaled horizontally needs a shared store instead. Server-mode only: desktop is a single local user with no network
// exposure and has no need for this. @Order(Integer.MIN_VALUE) guarantees this runs before
// Spring Security's own filter chain (and therefore before JwtAuthFilter), same as the license
// filter's ordering relative to its own (auth-free) app.
@Component
@Profile("server")
@Order(Integer.MIN_VALUE)
public class ApiRateLimitFilter implements WebFilter {

    private static final long WINDOW_MILLIS = 60_000L;

    private record Bucket(String name, int limitPerMinute) {}

    private static final Bucket AUTH_LOGIN    = new Bucket("auth-login", 10);
    private static final Bucket AUTH_REFRESH  = new Bucket("auth-refresh", 20);
    private static final Bucket AUTH_FORGOT   = new Bucket("auth-forgot", 5);
    // Spring Security's OAuth sign-in: /oauth2/authorization/{id} and the provider's callback. Each
    // start of a sign-in opens a WebSession for its state, and the in-memory store holds 10,000 at
    // most - a script starting sign-ins from one address filled it, and every real sign-in failed
    // until they expired. Per address, and roomy: one sign-in is two requests, and an office signs
    // in through one address at nine in the morning.
    private static final Bucket OAUTH         = new Bucket("oauth", 60);
    private static final Bucket SEARCH        = new Bucket("search", 30);
    private static final Bucket CHAT          = new Bucket("chat", 30);
    private static final Bucket CHAT_STREAM   = new Bucket("chat-stream", 60);
    private static final Bucket MCP           = new Bucket("mcp", 60);
    // Bulk folder uploads fire many sequential POST /api/v1/files requests (plus
    // /api/v1/files/check-duplicates before each batch) from one browser session - without a
    // dedicated bucket these shared the contested DEFAULT budget with every other API call the
    // same session makes mid-upload, tripping 429 on folders with more than a couple dozen files.
    private static final Bucket FILES         = new Bucket("files", 600);
    // The polls an open tab makes ON ITS OWN, whatever the person does: the maintenance gate every
    // 4 s, the announcement banner every 8 s, and a key account's presence heartbeat every 20 s.
    // Each answers from memory or the settings cache. In DEFAULT they spent about 26 of its 120 a
    // minute per tab before the person clicked anything, so a second and third tab - the chat page
    // beside a key account - left little for the work itself, and a burst of live updates answered
    // the page with 429s (measured 2026-10-06). A budget of their own keeps them from crowding out
    // what a person actually asks for; five tabs fit in it.
    private static final Bucket STATUS        = new Bucket("status", 150);
    // The ingestion API: a script pushing a source's documents one request each, the way a folder
    // upload sends its files - in DEFAULT it would share 120 a minute with everything else.
    private static final Bucket INGEST        = new Bucket("ingest", 600);
    private static final Bucket DEFAULT       = new Bucket("default", 120);

    // Key: "{user, token or ip}:{bucket-name}"; Value: [windowStartMillis, count]
    private final ConcurrentHashMap<String, long[]> buckets = new ConcurrentHashMap<>();

    // Null where nothing can prove who a request is from - see RateLimitIdentity.
    private RateLimitIdentity identity;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setIdentity(RateLimitIdentity identity) {
        this.identity = identity;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        // THE API ONLY, since 2026-09-29. Everything else - the page, its script chunks, fonts,
        // icons - fell into DEFAULT, 120 a minute per address, which one load of the app spends
        // half of. Two reloads in a minute answered the page itself with {"error":"Too many
        // requests"} and a lazy chunk with a 429, so the app went blank mid-navigation. Static
        // files cost nothing to serve; what this protects is the API and MCP behind them.
        if (!path.startsWith("/api/") && !path.startsWith("/mcp") && !isOAuthSignIn(path)) {
            return chain.filter(exchange);
        }
        Bucket bucket = resolveBucket(path);
        String key = keyFor(exchange, path, bucket) + ":" + bucket.name();

        long now = System.currentTimeMillis();
        long[] entry = count(key, now);
        // An MCP request is keyed by its bearer token BEFORE anything has checked it, so a client
        // sending a new random token each time had a fresh budget each time - each costing a
        // database lookup and a log line. The address also has a budget of its own on /mcp, wide
        // enough for every legitimate client behind one egress address.
        boolean addressSpent = key.startsWith("tok:")
                && count("ip:" + resolveIp(exchange) + ":MCP-ADDRESS", now)[1] > (long) bucket.limitPerMinute() * MCP_ADDRESS_FACTOR;

        if (entry[1] > bucket.limitPerMinute() || addressSpent) {
            long retryAfter = (WINDOW_MILLIS - (now - entry[0])) / 1000 + 1;
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            exchange.getResponse().getHeaders().set("Retry-After", String.valueOf(retryAfter));
            exchange.getResponse().getHeaders().set("Content-Type", "application/json");
            return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory()
                    .wrap("{\"error\":\"Too many requests\"}".getBytes(StandardCharsets.UTF_8))));
        }

        // Occasional stale-entry eviction (no background thread needed) - same probabilistic
        // sweep as LicenseRateLimitFilter - and always once the map is large, which a stream of
        // distinct keys makes it faster than a 1-in-200 sweep keeps up with.
        if (Math.random() < 0.005 || buckets.size() > MAX_KEYS) {
            buckets.entrySet().removeIf(e -> now - e.getValue()[0] >= WINDOW_MILLIS);
        }

        return chain.filter(exchange);
    }

    // How many times the per-token MCP budget one address may spend across all its tokens.
    private static final int MCP_ADDRESS_FACTOR = 10;
    private static final int MAX_KEYS = 50_000;

    private long[] count(String key, long now) {
        return buckets.compute(key, (k, v) -> {
            if (v == null || now - v[0] >= WINDOW_MILLIS) return new long[]{now, 1};
            v[1]++;
            return v;
        });
    }

    private static boolean isOAuthSignIn(String path) {
        return path.startsWith("/oauth2/") || path.startsWith("/login/oauth2/");
    }

    private Bucket resolveBucket(String path) {
        if (isOAuthSignIn(path)) return OAUTH;
        if (path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/register")) return AUTH_LOGIN;
        if (path.equals("/api/v1/auth/refresh")) return AUTH_REFRESH;
        if (path.equals("/api/v1/auth/forgot-password")) return AUTH_FORGOT;
        if (path.startsWith("/api/v1/search")) return SEARCH;
        // The SSE stream gets its own generous bucket - opening/reopening a stream per
        // conversation view or switch is cheap and shouldn't share a budget with real LLM calls.
        if (path.startsWith("/api/v1/chat/events")) return CHAT_STREAM;
        // Only the actual message-send and stop actions stay in the conservative CHAT bucket
        // (each triggers a real LLM call); conversation list/get/delete are plain metadata reads
        // and fall through to DEFAULT so they don't eat into the chat send/stop budget.
        // inject is a user message reaching the model mid-turn - the send budget, like a new turn.
        if (path.equals("/api/v1/chat") || path.matches("/api/v1/chat/conversations/[^/]+/(stop|inject)")) return CHAT;
        if (path.startsWith("/mcp")) return MCP;
        if (path.startsWith("/api/v1/files")) return FILES;
        if (path.startsWith("/api/v1/ingest/")) return INGEST;
        if (isStatusPoll(path)) return STATUS;
        return DEFAULT;
    }

    private static final java.util.regex.Pattern PRESENCE =
            java.util.regex.Pattern.compile("/api/v1/key-accounts/[^/]+/presence");

    static boolean isStatusPoll(String path) {
        return path.equals("/api/v1/maintenance/status") || path.equals("/api/v1/maintenance/announcement")
                || PRESENCE.matcher(path).matches();
    }

    // WHOSE BUDGET A REQUEST SPENDS.
    //
    // A SIGNED-IN USER'S OWN, since 2026-10-03. Every bucket but MCP was keyed by address, and an
    // organization's users do not arrive from one address each: a VPN concentrator, an office NAT
    // or a zero-trust gateway puts hundreds behind one. An open tab polls about 28 times a minute
    // and more while a turn runs, so five people who had typed nothing spent the address's 120,
    // and everybody behind it shared 30 chat messages a minute.
    //
    // The address still keys three things: a request with no verified session (a visitor, a
    // forged or expired token - RateLimitIdentity answers only for a signature it has checked),
    // the sign-in, refresh and forgot-password buckets, which exist to slow down guessing and
    // must not be reset by presenting somebody's valid session, and every edition without
    // accounts.
    //
    // /mcp/** is keyed by the caller's bearer token (hashed, never stored raw) - legitimate MCP
    // clients share a NAT'd corporate egress IP too, and that was the first place it showed.
    private String keyFor(ServerWebExchange exchange, String path, Bucket bucket) {
        if (bucket == AUTH_LOGIN || bucket == AUTH_REFRESH || bucket == AUTH_FORGOT || bucket == OAUTH) {
            return "ip:" + resolveIp(exchange);
        }
        if (path.startsWith("/mcp")) {
            String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (auth != null && auth.startsWith("Bearer ")) {
                return "tok:" + sha256(auth.substring(7).trim());
            }
        }
        String user = identity != null ? identity.verifiedUser(exchange) : null;
        if (user != null && !user.isBlank()) return "user:" + user;
        return "ip:" + resolveIp(exchange);
    }

    // X-Forwarded-For is believed only from a PROXY: a peer on this machine or on a private network,
    // which is where the reverse proxy in front of a server install sits (the bundled Caddy, a
    // Docker bridge, a corporate load balancer). SEC-48: it used to be believed from anyone, so a
    // client talking to the port directly sent a different address with every login attempt and
    // the ten-a-minute lockout never engaged.
    private String resolveIp(ServerWebExchange exchange) {
        var addr = exchange.getRequest().getRemoteAddress();
        java.net.InetAddress peer = addr != null ? addr.getAddress() : null;
        return clientIp(exchange.getRequest().getHeaders().getFirst("X-Forwarded-For"), peer);
    }

    // The LAST entry, never the first. nginx's $proxy_add_x_forwarded_for - the manual's own
    // snippet - and Cloudflare both APPEND the address they saw to whatever the client sent, so
    // the first entry is the client's to write and the last is the proxy's. Reading the first
    // left SEC-48 open behind every appending proxy: a new made-up address per login attempt.
    static String clientIp(String forwarded, java.net.InetAddress peer) {
        if (forwarded != null && !forwarded.isBlank() && peer != null && isProxyPeer(peer)) {
            String last = forwarded.substring(forwarded.lastIndexOf(',') + 1).trim();
            if (!last.isEmpty()) return last;
        }
        return peer != null ? peer.getHostAddress() : "unknown";
    }

    static boolean isProxyPeer(java.net.InetAddress peer) {
        return peer.isLoopbackAddress() || peer.isSiteLocalAddress() || peer.isLinkLocalAddress()
                // IPv6 unique-local (fc00::/7), the private range isSiteLocalAddress does not cover.
                || (peer instanceof java.net.Inet6Address && (peer.getAddress()[0] & 0xfe) == 0xfc);
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
