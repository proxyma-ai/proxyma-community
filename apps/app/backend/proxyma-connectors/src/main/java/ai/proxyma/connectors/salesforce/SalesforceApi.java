package ai.proxyma.connectors.salesforce;

import ai.proxyma.core.connector.spi.ApiAuth;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.AuthKind;
import ai.proxyma.core.connector.spi.ConnectorAuthException;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.RateLimitHint;
import ai.proxyma.core.connector.spi.ResolvedCredentials;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;

/**
 * How every request to a Salesforce org is made. The address and the token depend on how the
 * connection signs in:
 * <ul>
 *   <li>a <b>sign-in</b> (authorization code with PKCE, through the org's External Client App): the
 *       API is the connection's address - the org's My Domain URL, which serves both the sign-in and
 *       the API - and the token is the framework's, refreshed once on a 401;</li>
 *   <li>the <b>JWT bearer flow</b> (an integration user, a consumer key and a private key): the token
 *       comes from {@link SalesforceJwt}, and the API is the {@code instance_url} its answer named.</li>
 * </ul>
 * The token is a bearer on every request and on no other host.
 *
 * <p><b>Limits.</b> An org has a daily allowance of API requests, shared by every integration; once
 * it is spent Salesforce answers 403 {@code REQUEST_LIMIT_EXCEEDED} until the rolling day frees some,
 * which is reported as rate limited rather than waited out. A 429 or a 503 with {@code Retry-After}
 * is waited out as usual.
 *
 * <p><b>Version.</b> The REST API version is pinned ({@value #DEFAULT_VERSION}, Summer '25 - every
 * org serves it, and Salesforce keeps versions for years) and a source may name another.
 */
final class SalesforceApi {

    static final String DEFAULT_VERSION = "64.0";
    static final String REQUEST_LIMIT_EXCEEDED = "REQUEST_LIMIT_EXCEEDED";
    /** The token answer's field naming the org's API address - kept by the sign-in (OAuth2Spec.grantFields). */
    static final String INSTANCE_URL = "instance_url";

    /** The org's daily allowance, spent: a 403 that is a rate limit, not a refusal. */
    static final RateLimitHint RATE_LIMIT = ((RateLimitHint) (response, now) -> {
        if (response.status() != 403) return Optional.empty();
        byte[] body = response.bytes();
        if (body == null || body.length == 0) return Optional.empty();
        return new String(body, StandardCharsets.UTF_8).contains(REQUEST_LIMIT_EXCEEDED)
                ? Optional.of(RateLimitHint.NEXT_PASS) : Optional.empty();
    }).or(RateLimitHint.STANDARD);

    /** A client on an org, and the org's address. */
    record Org(ApiClient api, String instanceUrl) {
        String data(String version) {
            return "services/data/v" + version + "/";
        }
    }

    private SalesforceApi() {}

    /** The org a connection reaches - for the JWT flow this asks for a token, to learn the address. */
    static Org connect(ConnectorHttp http, ResolvedCredentials connection) {
        return connect(http, connection, Clock.systemUTC());
    }

    static Org connect(ConnectorHttp http, ResolvedCredentials connection, Clock clock) {
        if (connection.authKind() == AuthKind.KEY_PAIR) {
            SalesforceJwt jwt = new SalesforceJwt(http, connection, clock);
            String instance = jwt.instanceUrl();
            ResolvedCredentials signed = new ResolvedCredentials(connection.connectionId(), connection.protocol(),
                    AuthKind.KEY_PAIR, instance, connection.username(), Map.of(), Map.of(), jwt);
            return new Org(client(http, instance, ApiAuth.bearer(signed)), instance);
        }
        if (!connection.hasBaseUrl()) {
            throw new ConnectorAuthException("This connection has no My Domain URL. Add it in Settings > Connections.");
        }
        String base = connection.baseUrl().strip().replaceAll("/+$", "");
        // A sign-in's token answer names the org's API address (instance_url, kept on the connection
        // by OAuth2ConnectionService). It is the address that matters when the connection's own is a
        // login server - a sandbox signed in at test.salesforce.com - and it is checked like the JWT
        // flow's, since the token is sent there.
        String instance = connection.grant(INSTANCE_URL);
        if (instance != null && !instance.isBlank()) base = SalesforceJwt.checkedInstance(instance, base);
        return new Org(client(http, base, ApiAuth.bearer(connection)), base);
    }

    private static ApiClient client(ConnectorHttp http, String base, ApiAuth auth) {
        return http.client(base).service("Salesforce").auth(auth).rateLimit(RATE_LIMIT).build();
    }
}
