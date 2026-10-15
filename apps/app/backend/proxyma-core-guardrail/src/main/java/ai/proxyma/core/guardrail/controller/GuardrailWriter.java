package ai.proxyma.core.guardrail.controller;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import reactor.core.publisher.Mono;

/**
 * Who may CHANGE the guardrail's corpus or its metrics window, and who did.
 *
 * <p>The guardrail settings are a Super admin's to change and an Admin's to read; the corpus and the
 * window were open to every Admin for every method. An Admin could upload an examples file that
 * blocked messages for everybody, delete the rules, or reset the window that held the evidence -
 * and the reset was audited with no user. On Enterprise Server a change is now a Super admin's, the
 * same split as the settings; reading stays as it was. Desktop and Home Server have one user.
 */
record GuardrailWriter(String userId, boolean allowed) {

    static Mono<GuardrailWriter> of(boolean multiUser) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(auth -> new GuardrailWriter(auth.getName(), !multiUser || isSuperAdmin(auth)))
                .defaultIfEmpty(new GuardrailWriter(null, !multiUser));
    }

    private static boolean isSuperAdmin(Authentication auth) {
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_SUPER_ADMIN"::equals);
    }
}
