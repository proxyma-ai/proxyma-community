package ai.proxyma.core.system.activity;

// Shared ServerWebExchange attribute key for the authenticated user id, written by
// proxyma-core-auth's JwtAuthFilter and read by proxyma-core-network's ActivityTrackingFilter
// after chain.filter(exchange) completes. A plain exchange attribute is used deliberately, not
// Reactor Context: Mono.then(other) subscribes `other` with the Context that flowed into the
// whole then() expression from outside, not one written by a contextWrite() buried inside the
// first Mono's own chain - a context write made deep inside chain.filter(exchange) (e.g. by
// JwtAuthFilter) never becomes visible to a sibling Mono joined afterwards via .then(). Exchange
// attributes have no such limitation - they're a single mutable map shared by the whole filter
// chain regardless of Mono/Context nesting, the same handoff JwtAuthFilter.USER_CLAIMS_KEY
// already uses successfully for AccountDeletionLockoutFilter.
public final class RequestUserIdAttribute {

    public static final String KEY = "PROXYMA_REQUEST_USER_ID";

    private RequestUserIdAttribute() {
    }
}
