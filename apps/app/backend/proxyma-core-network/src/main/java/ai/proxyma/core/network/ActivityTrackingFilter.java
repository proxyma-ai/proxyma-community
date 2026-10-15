package ai.proxyma.core.network;

import ai.proxyma.core.system.activity.ActivityCounterStore;
import ai.proxyma.core.system.activity.RequestUserIdAttribute;
import ai.proxyma.core.system.activity.UserPresencePort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

// Records one request per call for the Admin Users tab's platform-activity chart, plus per-user
// per-feature usage (which feature, how long) and a throttled lastSeenAt presence touch. Ordered
// right after ApiRateLimitFilter (Integer.MIN_VALUE + 1) so a 429'd request isn't counted as
// activity - runs before Spring Security's own filter chain (order -100), so the principal isn't
// resolved on THIS filter's own `exchange` reference yet.
//
// Reads identity via exchange.getAttribute(RequestUserIdAttribute.KEY), NOT
// ReactiveSecurityContextHolder.getContext() - an earlier version tried the latter, composed
// after chain.filter(exchange) via .then(...), on the theory that Spring Security's
// contextWrite() further down the same composed chain.filter(exchange) Mono would be visible
// here too. It never was: Mono.then(other) subscribes `other` with the Context that flowed into
// the whole then() expression from OUTSIDE, not one written by a contextWrite() buried inside
// the first Mono's own chain - a write made deep inside chain.filter(exchange) (by JwtAuthFilter)
// never becomes visible to a sibling Mono joined afterwards via .then(). The result was silent:
// no exception, just an always-empty Authentication, so lastSeenAt/presence never updated for
// anyone. Exchange attributes have no such limitation - they're a single mutable map shared by
// the whole filter chain regardless of Mono/Context nesting, the same handoff
// JwtAuthFilter.USER_CLAIMS_KEY already uses successfully for AccountDeletionLockoutFilter.
// Composing after the chain also makes the elapsed time here the full request duration. Two
// WebFilters running over every request is negligible: both this and the rate limiter are O(1)
// ConcurrentHashMap operations with no I/O (the presence write is throttled and runs on a virtual
// thread inside UserPresenceService).
@Component
@Profile("server")
@Order(Integer.MIN_VALUE + 1)
public class ActivityTrackingFilter implements WebFilter {

    private final ActivityCounterStore counterStore;
    // ObjectProvider, not a direct dependency: the implementation (UserPresenceService) lives in
    // proxyma-core-auth, which this module must not depend on - same dependency-direction trick
    // as ActivityCounterStore living in proxyma-core-common.
    private final ObjectProvider<UserPresencePort> presencePort;

    public ActivityTrackingFilter(ActivityCounterStore counterStore,
                                  ObjectProvider<UserPresencePort> presencePort) {
        this.counterStore = counterStore;
        this.presencePort = presencePort;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long startNanos = System.nanoTime();
        return chain.filter(exchange).doOnSuccess(v -> recordActivity(exchange, startNanos));
    }

    private void recordActivity(ServerWebExchange exchange, long startNanos) {
        String userId = exchange.getAttribute(RequestUserIdAttribute.KEY);
        counterStore.recordRequest(userId);
        String feature = classifyFeature(exchange.getRequest().getPath().value());
        if (userId != null && feature != null) {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            counterStore.recordFeature(userId, feature, durationMs);
            UserPresencePort presence = presencePort.getIfAvailable();
            if (presence != null) presence.touch(userId);
        }
    }

    // Feature keys: CHAT (the agent), MCP (protocol transport; per-tool attribution happens in
    // proxyma-core-tools' callback decorator as TOOL:<name>), API:<segment> for everything else
    // under /api/v1. Returns null for paths that aren't meaningful usage: static assets, the SSE
    // stream (a single connection that stays open for the whole session - its "duration" would
    // swamp every real number), and "maintenance" - MaintenanceGate.tsx polls
    // /api/v1/maintenance/status every 4s for the entire lifetime of every open session (it's
    // infrastructure polling, not a feature a user chose to use), which otherwise dwarfs every
    // genuine feature/tool bucket in the Activity tab's pie chart and inflates the Users table's
    // "Total Active Time" column for every logged-in user regardless of what they actually did.
    private String classifyFeature(String path) {
        if (path.startsWith("/mcp")) return "MCP";
        if (!path.startsWith("/api/v1/")) return null;
        String segment = path.substring("/api/v1/".length());
        int slash = segment.indexOf('/');
        if (slash >= 0) segment = segment.substring(0, slash);
        if (segment.isBlank() || segment.equals("events") || segment.equals("maintenance")) return null;
        if (segment.equals("chat")) return "CHAT";
        return "API:" + segment;
    }
}
