package ai.proxyma.core.connector.spi;

import java.util.Map;

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
}
