package ai.proxyma.core.settings.access;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.util.Set;

/**
 * Who is calling, for a controller that decides by role.
 *
 * <p>ONE COPY. The same ReactiveSecurityContextHolder chain was written out in a dozen controllers
 * (settings-core-17), each with its own spelling of the role names and its own answer for a caller
 * with no authentication - and a check that is copied is a check that drifts.
 *
 * <p>The single-user editions have no security filter chain at all (proxyma-core-auth is
 * server-only), so nothing ever populates the context there: every role question answers TRUE when
 * {@code multiUser} is false, because the one local person is every role. Who that person is - who
 * may reach the port at all - is the edition's own front door, not this class.
 */
public final class CallerRoles {

    public static final String SUPER_ADMIN = "ROLE_SUPER_ADMIN";
    public static final String ADMIN = "ROLE_ADMIN";

    private CallerRoles() {}

    /** A Super admin on Enterprise Server; the local owner on Desktop and Home Server. */
    public static Mono<Boolean> isSuperAdmin(boolean multiUser) {
        return multiUser ? hasAny(SUPER_ADMIN) : Mono.just(true);
    }

    /** An Admin or a Super admin on Enterprise Server; the local owner on Desktop and Home Server. */
    public static Mono<Boolean> isAdmin(boolean multiUser) {
        return multiUser ? hasAny(SUPER_ADMIN, ADMIN) : Mono.just(true);
    }

    /** Whether the signed-in caller holds any of these authorities. False with nobody signed in. */
    public static Mono<Boolean> hasAny(String... authorities) {
        Set<String> wanted = Set.of(authorities);
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(auth -> holdsAny(auth, wanted))
                .defaultIfEmpty(false);
    }

    /** The caller's user id, or "" when there is no principal (Desktop, Home Server, a visitor). */
    public static Mono<String> userId(ServerWebExchange exchange) {
        return exchange.getPrincipal().map(Principal::getName).defaultIfEmpty("");
    }

    private static boolean holdsAny(Authentication auth, Set<String> wanted) {
        if (auth == null) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if (wanted.contains(a.getAuthority())) return true;
        }
        return false;
    }
}
