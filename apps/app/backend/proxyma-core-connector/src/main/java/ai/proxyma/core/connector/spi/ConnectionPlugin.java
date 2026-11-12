package ai.proxyma.core.connector.spi;

import org.springframework.http.HttpMethod;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One SERVICE a connection talks to: a stateless Spring bean that describes it (how it signs in,
 * where its server is, which source types it serves) and tests a connection with one cheap call.
 * The connection dialog, the Test button and the connection service's validation all read it
 * through {@link ConnectorCatalog}.
 */
public interface ConnectionPlugin {

    ProtocolDescriptor descriptor();

    /**
     * One cheap authenticated call - "who am I" on the service. MUST NOT throw: every failure is a
     * {@link ConnectionTestResult} whose message the person who just typed the credential can act
     * on, never a stack trace or an HTML error page.
     */
    ConnectionTestResult test(ResolvedCredentials connection, ConnectorHttp http);

    /** How the service signs in with OAuth2, where it does - else null. */
    default OAuth2Spec oauth2() {
        return null;
    }

    /**
     * How ONE connection signs in - for a service whose sign-in addresses are the account's own (a
     * Zendesk account signs in at its own subdomain), worked out from the connection's non-secret
     * settings and its server address. {@link #oauth2()} for every other service. Null refuses the
     * sign-in: a plugin that builds its addresses from what a person typed returns null for a value
     * that would send the OAuth client's secret anywhere but the service's own hosts.
     */
    default OAuth2Spec oauth2(Map<String, String> settings, String baseUrl) {
        return oauth2();
    }

    /**
     * GRANULAR CONSENT (2026-11-08): what a sign-in's GRANTED scopes leave this connection unable to do,
     * as a sentence the person reads beside "Sign in again" - or empty when the grant serves it. A
     * consent screen lets a person untick a scope (Google's does), and the token answer's {@code scope}
     * says what was allowed; a connection signed in without what it needs is marked as needing a new
     * sign-in rather than reading as fine. Asked only when the answer names its scopes.
     *
     * @param granted the scopes the service says it granted
     * @param write   whether the connection's Write switch is on
     */
    default Optional<String> consentShortfall(Set<String> granted, boolean write) {
        return Optional.empty();
    }

    /**
     * What one request costs against the service's daily quota, in the service's own units - 0 for a
     * service that meters none. Counted only where a quota is shared by many installs: a built-in OAuth
     * client's (BuiltInQuota). Every request is counted, a refused or invalid one included, as Google
     * counts it.
     */
    default int requestUnits(HttpMethod method, URI uri) {
        return 0;
    }

    /**
     * Whether a request only asks whose account the connection signs in as - YouTube's {@code channels.list mine}. A
     * page's such request goes through a small daily reserve once the built-in client's share is spent
     * (BuiltInQuota.spendReserve), so a channel connected then is still named after itself (2026-11-11).
     */
    default boolean identityRequest(HttpMethod method, URI uri) {
        return false;
    }
}
