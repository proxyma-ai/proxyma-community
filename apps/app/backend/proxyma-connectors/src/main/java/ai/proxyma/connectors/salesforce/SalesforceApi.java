package ai.proxyma.connectors.salesforce;

import ai.proxyma.core.connector.spi.ApiAuth;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.AuthKind;
import ai.proxyma.core.connector.spi.ConnectorAuthException;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.RateLimitHint;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import ai.proxyma.core.connector.spi.ServiceAddress;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Locale;
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
    private static final String LIGHTNING = ".lightning.force.com";
    private static final String SETUP = ".salesforce-setup.com";

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
        String base = myDomain(connection.baseUrl());
        // A sign-in's token answer names the org's API address (instance_url, kept on the connection
        // by OAuth2ConnectionService). It is the address that matters when the connection's own is a
        // login server - a sandbox signed in at test.salesforce.com - and it is checked like the JWT
        // flow's, since the token is sent there.
        String instance = connection.grant(INSTANCE_URL);
        if (instance != null && !instance.isBlank()) base = SalesforceJwt.checkedInstance(instance, base);
        return new Org(client(http, base, ApiAuth.bearer(connection)), base);
    }

    /**
     * The org's address for whatever was typed as its My Domain URL - very often the browser's bar,
     * {@code https://acme.lightning.force.com/lightning/page/home}, whose path joined to the token
     * endpoint answers with a page. A My Domain URL has no path, and its Lightning and Setup hosts
     * are the same org's: {@code X.lightning.force.com} is {@code X.my.salesforce.com} (a sandbox's
     * {@code X--SBX.sandbox.lightning.force.com} is {@code X--SBX.sandbox.my.salesforce.com}), and
     * {@code X.my.salesforce-setup.com} is {@code X.my.salesforce.com}. Any other Salesforce host
     * keeps its scheme and host alone. A host that is not Salesforce's (a relay, a test server) keeps
     * its path up to Salesforce's own {@code /lightning}, {@code /services} or {@code /_ui}.
     */
    static String myDomain(String address) {
        if (address == null || address.isBlank()) return address;
        String host = ServiceAddress.host(address);
        if (host.isEmpty()) return address.strip().replaceAll("/+$", ""); // not a URL: refused in words
        String mapped = host;
        if (host.endsWith(LIGHTNING)) {
            mapped = host.substring(0, host.length() - LIGHTNING.length()) + ".my.salesforce.com";
        } else if (host.endsWith(SETUP)) {
            mapped = host.substring(0, host.length() - SETUP.length()) + ".salesforce.com";
        }
        String org = mapped;
        if (SalesforceJwt.SALESFORCE_DOMAINS.stream().noneMatch(org::endsWith)) {
            return ServiceAddress.cutAt(address, "lightning", "services", "_ui");
        }
        URI uri = URI.create(address.strip());
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + org + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
    }

    private static ApiClient client(ConnectorHttp http, String base, ApiAuth auth) {
        return http.client(base).service("Salesforce").auth(auth).rateLimit(RATE_LIMIT).build();
    }
}
