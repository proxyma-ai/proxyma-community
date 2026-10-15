package ai.proxyma.core.network;

import org.springframework.web.server.ServerWebExchange;

/**
 * Who a request is from, as far as its own credential PROVES - asked by ApiRateLimitFilter, which
 * runs before authentication and so cannot read the security context.
 *
 * <p>The contract is "verified or nothing". A value returned here gets a budget of its own, so an
 * identity read off a credential without checking its signature would hand every forged token a
 * fresh budget - on the endpoints open to visitors, a way round the limiter altogether.
 *
 * <p>Implemented in proxyma-core-auth, which only the server profile builds - the one profile
 * ApiRateLimitFilter runs under. Where there is no bean every request is keyed by its address.
 */
public interface RateLimitIdentity {

    /**
     * The id of the user this request's credential is signed for, or null. Must not block: it is
     * called on the event loop for every API request, so a signature check is the whole of what
     * it may do - no database, no network.
     */
    String verifiedUser(ServerWebExchange exchange);
}
