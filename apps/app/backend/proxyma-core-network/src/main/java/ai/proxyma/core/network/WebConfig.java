package ai.proxyma.core.network;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import org.springframework.context.annotation.Profile;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// CORS + security filters for the single-user web UI (desktop and home server).
// Server mode has its own security layer (Spring Security + OAuth2), so these filters are skipped.
//
// THE ACCESS CODE IS THE BOUNDARY, since 2026-10-14 (editions-assembly-01). There are no accounts in
// this mode and no sign-in, and for a long time "who can reach the port" was decided by the Host
// allow-list below - a header any non-browser client writes itself, on a listener bound to every
// interface. Now every API call presents the install's access code (AccessGate, accessFilter below),
// Desktop listens on loopback only (server.address in application-desktop.properties, pinned by
// ProxymaLauncher), and the Host allow-list is what it can actually be: a DNS-rebinding defense.
//
// The Windows installer and the Linux .deb must stay loopback-only, always. ProxymaLauncher pins
// `proxyma.network.allowed-hosts` to empty and `server.address` to 127.0.0.1 on the application JVM's
// command line, and a system property outranks an environment variable in Spring's property order,
// so neither PROXYMA_NETWORK_ALLOWED_HOSTS nor PROXYMA_SERVER_ADDRESS in the surrounding environment
// can open a desktop install. The launcher also drops every inherited `-Dproxyma.*` argument, so it
// cannot be passed through either. Exposure is a property of WHICH PACKAGE YOU INSTALLED, which is
// the only place it can be enforced rather than asserted.
//
// The home-server package sets the property from its own configuration. That package exists so
// this decision is made once, at download time, by someone who went looking for it.
@Configuration
@Profile("!server")
public class WebConfig {

    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

    // Always permitted, and not configurable: the machine itself. A Host header carrying a port is
    // split before comparison, and IPv6 literals arrive bracketed.
    private static final List<String> LOOPBACK = List.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final AccessGate accessGate;

    // Empty means loopback only, which is the default and what every desktop install runs.
    // A comma-separated list of host names or addresses opens those, and `*` opens everything.
    @Value("${proxyma.network.allowed-hosts:}")
    private String allowedHostsRaw;

    // Both default OFF and are only consulted when the instance is exposed. Neither is something a
    // remote user came for, and both are far more than "read my documents":
    //
    //   /api/v1/desktop/**  browses and registers ANY directory the process can read. On a headless
    //                       box its folder picker cannot open a dialog anyway.
    //   /mcp**              hands the agent's write tools - Jira, Confluence, Bitbucket, acting with
    //                       the stored connector credentials - to whoever calls it.
    //
    // On loopback both stay available, behind the access code like everything else.
    @Value("${proxyma.network.expose-local-file-api:false}")
    private boolean exposeLocalFileApi;

    @Value("${proxyma.network.expose-mcp:false}")
    private boolean exposeMcp;

    private List<String> allowedHosts = List.of();
    private boolean allowAnyHost;
    private boolean exposed;

    public WebConfig(AccessGate accessGate) {
        this.accessGate = accessGate;
    }

    @PostConstruct
    void resolveExposure() {
        List<String> parsed = new ArrayList<>();
        for (String entry : allowedHostsRaw.split(",")) {
            String host = entry.trim().toLowerCase(Locale.ROOT);
            if (!host.isEmpty()) parsed.add(host);
        }
        this.allowAnyHost = parsed.contains("*");
        this.allowedHosts = List.copyOf(parsed);
        this.exposed      = !parsed.isEmpty();

        if (!exposed) {
            log.info("Network: loopback only. Reachable at localhost and 127.0.0.1, nowhere else.");
            return;
        }
        // WARN, every start, and it names what is open. This mode has no accounts: the access code
        // is the one thing between a caller who can route to this port and every indexed document,
        // every conversation and the AI provider spend. That is a legitimate way to run it on a
        // network you control - it is not something anybody should discover from a log they never
        // read.
        log.warn("Network: THIS INSTANCE IS REACHABLE BEYOND LOOPBACK. Its access code is the only sign-in.");
        log.warn("Network: accepted Host headers: {}", allowAnyHost ? "ANY (*)" : allowedHosts);
        log.warn("Network: local file API {}, MCP endpoint {}",
                exposeLocalFileApi ? "EXPOSED" : "blocked",
                exposeMcp ? "EXPOSED" : "blocked");
    }

    public boolean isExposed() { return exposed; }

    /**
     * Whether a CORS request's {@code Origin} is this app's own page - the only page that may call
     * this API with the browser's credentials (swx-03).
     *
     * <p>THE PAGE IS ALWAYS SAME-ORIGIN: both editions serve their own SPA, and .env.desktop leaves
     * the API base empty. Spring still sees a CORS request in two shapes of that, and only those
     * two are let through:
     *
     * <ol>
     *   <li>the origin's host and port are the Host header's - a proxy that terminates TLS and keeps
     *       the Host, where the page says https and the app sees http. Vite marks its bundles
     *       {@code crossorigin}, so the SPA's own JS and CSS are CORS requests too, and refusing
     *       them is the blank page CorsOriginPatternsTest is about;</li>
     *   <li>an allowed host on its DEFAULT port - a proxy on 443 that rewrites the Host header to
     *       its upstream. A browser omits a default port from the origin.</li>
     * </ol>
     *
     * <p>NO PORT WILDCARDS, which is what it used to have: {@code http://localhost:*} with
     * credentials. A port is not part of a site, so a page served by any other local process -
     * {@code python -m http.server} in a Downloads folder, a docs preview, another app's XSS - was
     * allowed to read every answer this API gave the browser.
     *
     * <p>Package-private and static so it can be tested without a Spring context.
     */
    static boolean isOwnOrigin(String origin, String hostHeader, List<String> allowedHosts) {
        if (origin == null) return false;
        URI uri;
        try {
            uri = URI.create(origin.trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) return false;
        if (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) return false;
        host = host.toLowerCase(Locale.ROOT);
        String authority = uri.getPort() < 0 ? host : host + ":" + uri.getPort();
        if (hostHeader != null && authority.equals(hostHeader.trim().toLowerCase(Locale.ROOT))) return true;
        return uri.getPort() < 0 && allowedHosts.contains(host);
    }

    @Bean
    public CorsWebFilter corsWebFilter() {
        return new CorsWebFilter(exchange -> {
            ServerHttpRequest request = exchange.getRequest();
            String origin = request.getHeaders().getOrigin();
            // A configuration with no allowed origin, not null: null skips CORS processing, and a
            // refused origin's simple POST would then still run - only its answer would be hidden.
            CorsConfiguration cors = new CorsConfiguration();
            List<String> hosts = allowAnyHost ? List.of() : allowedHosts;
            if (isOwnOrigin(origin, request.getHeaders().getFirst(HttpHeaders.HOST), hosts)) {
                cors.setAllowedOrigins(List.of(origin));
                cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
                cors.setAllowedHeaders(List.of("*"));
                cors.setAllowCredentials(true);
            }
            return cors;
        });
    }

    // DNS rebinding protection: reject requests whose Host header is not one this instance answers
    // to. Loopback always passes; anything else has to be named in proxyma.network.allowed-hosts.
    //
    // NOTHING STORED BYPASSES IT. A vestigial "org mode" setting did, here and on CORS above, until
    // 2026-10-14 - and a configuration import or a PATCH carrying " true" could switch it on, turning
    // this check off for a single-user edition that has no other boundary.
    // NOT AN ACCESS CONTROL. A browser cannot forge a Host header, which is what makes this a defense
    // against a hostile page rebinding its own name to 127.0.0.1; any other client writes whatever
    // Host it likes, which is why the access code exists (accessFilter below).
    @Bean
    @Order(-300)
    public WebFilter hostValidationFilter() {
        return (ServerWebExchange exchange, WebFilterChain chain) -> {
            if (allowAnyHost) return chain.filter(exchange);
            ServerHttpRequest request = exchange.getRequest();
            String host = request.getHeaders().getFirst("Host");
            if (host != null) {
                String hostOnly = hostWithoutPort(host);
                if (!LOOPBACK.contains(hostOnly) && !allowedHosts.contains(hostOnly)) {
                    exchange.getResponse().setRawStatusCode(403);
                    return exchange.getResponse().setComplete();
                }
            }
            return chain.filter(exchange);
        };
    }

    // Splits the port off a Host header without breaking an IPv6 literal, which is bracketed and
    // full of colons: `[::1]:4246` -> `[::1]`, `homeserver.lan:4246` -> `homeserver.lan`.
    private static String hostWithoutPort(String host) {
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {
            int close = h.indexOf(']');
            return close < 0 ? h : h.substring(0, close + 1);
        }
        int colon = h.indexOf(':');
        return colon < 0 ? h : h.substring(0, colon);
    }

    // The two capabilities that are safe on loopback and are not safe on a network. Ordered ahead of
    // the fetch-metadata guard so a blocked path is blocked whatever the request looks like.
    //
    // 404 rather than 403: to a caller who was never meant to reach this, "not here" is the honest
    // answer and says less than "here, but not for you".
    @Bean
    @Order(-250)
    public WebFilter exposedCapabilityFilter() {
        return (ServerWebExchange exchange, WebFilterChain chain) -> {
            if (!exposed) return chain.filter(exchange);
            String path = exchange.getRequest().getPath().value();
            boolean blocked = (!exposeLocalFileApi && path.startsWith("/api/v1/desktop"))
                           || (!exposeMcp          && path.startsWith("/mcp"));
            if (blocked) {
                exchange.getResponse().setRawStatusCode(404);
                return exchange.getResponse().setComplete();
            }
            return chain.filter(exchange);
        };
    }

    // Fetch metadata guard: refuse what another site's page makes the browser send here - a fetch,
    // a form POST, an image or script pulling an API response - while still letting a person
    // FOLLOW A LINK here. It refused every cross-site request, the plain page visit included, so
    // the public demo (removed 2026-10-09) answered a blank 403 to anyone arriving from a search
    // result, Slack, email or LinkedIn, and loaded fine when the address was typed or the page
    // reloaded (feedback, 2026-09-30). A link to a Home Server posted in Teams failed the same way.
    //
    // The standard resource-isolation policy, stricter on two points: a navigation passes only as a
    // top-level GET document load, and SAME-SITE is held to the same rule as cross-site (swx-03).
    // A port is not part of a site, so every other web server on this machine - or on the Home
    // Server's host - is same-site with this one; the app's own page is same-ORIGIN, always.
    @Bean
    @Order(-200)
    public WebFilter fetchMetadataFilter() {
        return (ServerWebExchange exchange, WebFilterChain chain) -> {
            if (isRefusedCrossSite(exchange.getRequest())) {
                exchange.getResponse().setRawStatusCode(403);
                return exchange.getResponse().setComplete();
            }
            return chain.filter(exchange);
        };
    }

    // The access code - see AccessGate. After the three checks above, and ahead of Spring
    // Security's chain (-100) and every handler.
    @Bean
    @Order(-150)
    public WebFilter accessFilter() {
        return accessGate::filter;
    }

    static boolean isRefusedCrossSite(ServerHttpRequest request) {
        var headers = request.getHeaders();
        String site = headers.getFirst("Sec-Fetch-Site");
        if (!"cross-site".equals(site) && !"same-site".equals(site)) return false;
        boolean topLevelPageLoad = "navigate".equals(headers.getFirst("Sec-Fetch-Mode"))
                && "document".equals(headers.getFirst("Sec-Fetch-Dest"))
                && org.springframework.http.HttpMethod.GET.equals(request.getMethod());
        return !topLevelPageLoad;
    }

}
