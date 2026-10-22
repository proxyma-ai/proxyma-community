package ai.proxyma.connectors.salesforce;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.AuthKind;
import ai.proxyma.core.connector.spi.AuthMethod;
import ai.proxyma.core.connector.spi.ConnectionPlugin;
import ai.proxyma.core.connector.spi.ConnectionTestResult;
import ai.proxyma.core.connector.spi.ConnectorAuthException;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.ConnectorHttpException;
import ai.proxyma.core.connector.spi.FieldSpec;
import ai.proxyma.core.connector.spi.OAuth2Spec;
import ai.proxyma.core.connector.spi.ProtocolDescriptor;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Salesforce service, one org per connection, reached through an <b>External Client App</b> -
 * Salesforce stopped letting orgs create Connected Apps in Spring '26. Two ways in:
 * <ul>
 *   <li>a sign-in (authorization code with PKCE), as the person who signs in, through the app's
 *       consumer key and secret - the person's own on the single-user editions, the deployment's on
 *       Enterprise Server. The sign-in goes to the connection's own address when that is a
 *       Salesforce host - the org's My Domain URL, a sandbox's, or {@code test.salesforce.com} - and to
 *       {@code login.salesforce.com} otherwise ({@link #oauth2(Map, String)}). The token answer's
 *       {@code instance_url} is kept on the connection and is where the API is spoken
 *       (SalesforceApi), so a sandbox signed in at the login server still reaches its own org;</li>
 *   <li>the JWT bearer flow, as an integration user: the app's consumer key, the user's name and the
 *       private key whose certificate is uploaded to the app (SalesforceJwt). Production or sandbox,
 *       told from the address, which may be the org's My Domain URL or the login server.</li>
 * </ul>
 * Test is "who am I": the identity service's {@code userinfo}, "Signed in as ana@acme.com on
 * acme.my.salesforce.com".
 */
@Component
public class SalesforceConnectionPlugin implements ConnectionPlugin {

    static final String AUTHORIZE_URL = "https://login.salesforce.com/services/oauth2/authorize";
    static final String TOKEN_URL = "https://login.salesforce.com/services/oauth2/token";
    // api: the REST API; id: who signed in (Test); refresh_token: a sign-in that outlives the session.
    static final List<String> SCOPES = List.of("api", "id", "refresh_token");
    static final String USERINFO = "services/oauth2/userinfo";

    static final ProtocolDescriptor DESCRIPTOR = ProtocolDescriptor.builder(ConnectionProtocol.SALESFORCE)
            .displayName("Salesforce")
            .mark("salesforce")
            .auth(AuthMethod.of(AuthKind.OAUTH2_CODE, "Sign in with Salesforce",
                            FieldSpec.text("clientId", "Consumer key")
                                    .help("From the org's External Client App, with OAuth, PKCE and the api, id and "
                                            + "refresh_token scopes."),
                            FieldSpec.secret("clientSecret", "Consumer secret")),
                    AuthMethod.of(AuthKind.KEY_PAIR, "JWT bearer (integration user)",
                            FieldSpec.text(SalesforceJwt.CONSUMER_KEY, "Consumer key").asRequired()
                                    .help("From the External Client App whose JWT bearer flow has your certificate."),
                            FieldSpec.text(AuthMethod.USERNAME, "User name").asRequired()
                                    .placeholder("integration@acme.com")
                                    .help("The integration user's Salesforce user name, pre-authorized for the app."),
                            FieldSpec.secret(SalesforceJwt.PRIVATE_KEY, "Private key").asRequired()
                                    .help("The PEM private key of the certificate uploaded to the app, unencrypted."),
                            FieldSpec.text(SalesforceJwt.AUDIENCE, "Audience").asAdvanced()
                                    .placeholder(SalesforceJwt.LOGIN)
                                    .help("Told from the address when empty: https://test.salesforce.com for a sandbox.")))
            .baseUrl(FieldSpec.url("baseUrl", "My Domain URL").asRequired()
                    .placeholder("https://acme.my.salesforce.com")
                    .help("Your org's address. For a sandbox: https://acme--uat.sandbox.my.salesforce.com"))
            .sourceTypes(ConnectorType.SALESFORCE)
            .docsAnchor("salesforce")
            .build();

    static final String AUTHORIZE_PATH = "/services/oauth2/authorize";
    static final String TOKEN_PATH = "/services/oauth2/token";

    static final OAuth2Spec OAUTH2 = OAuth2Spec.builder(AUTHORIZE_URL, TOKEN_URL).scopes(SCOPES)
            .keepGrantField(SalesforceApi.INSTANCE_URL).build();

    @Override
    public ProtocolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public OAuth2Spec oauth2() {
        return OAUTH2;
    }

    /**
     * The org's own sign-in: every My Domain serves {@code /services/oauth2/authorize} and
     * {@code /token}, and so do both login servers - which is how a sandbox, whose users do not
     * exist on {@code login.salesforce.com}, signs in in the browser. Only an https Salesforce host
     * gets the client's secret; any other address (a relay, a typo) signs in at the production
     * login server, as every connection did before.
     */
    @Override
    public OAuth2Spec oauth2(Map<String, String> settings, String baseUrl) {
        String origin = salesforceOrigin(baseUrl);
        return origin == null ? OAUTH2 : OAUTH2.at(origin + AUTHORIZE_PATH, origin + TOKEN_PATH);
    }

    // https://<host> for an https address on a Salesforce domain (SalesforceJwt.SALESFORCE_DOMAINS),
    // else null - a Lightning or Setup address as its My Domain (SalesforceApi.myDomain).
    static String salesforceOrigin(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = URI.create(SalesforceApi.myDomain(url));
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getPort() > 0 && uri.getPort() != 443) return null;
            return SalesforceJwt.SALESFORCE_DOMAINS.stream().anyMatch(host::endsWith) ? "https://" + host : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public ConnectionTestResult test(ResolvedCredentials connection, ConnectorHttp http) {
        try {
            // A sign-in at a login server reaches its org through the instance_url it was answered
            // with; before there is one, the address alone cannot say which org that is.
            if (connection.authKind() != AuthKind.KEY_PAIR && isLoginServer(connection.baseUrl())
                    && connection.grant(SalesforceApi.INSTANCE_URL) == null) {
                return ConnectionTestResult.failed("A sign-in needs the org's own My Domain URL, such as "
                        + "https://acme.my.salesforce.com, not the login server.");
            }
            SalesforceApi.Org org = SalesforceApi.connect(http, connection);
            String instance = URI.create(org.instanceUrl()).getHost();
            String user;
            try {
                JsonNode me = org.api().getJson(USERINFO);
                user = me.path("preferred_username").asText("");
                if (user.isBlank()) user = me.path("name").asText("");
            } catch (ConnectorHttpException e) {
                // A token without the id scope may not ask who it is; the JWT flow's user is known.
                if (connection.authKind() != AuthKind.KEY_PAIR || e.status() == 401) throw e;
                user = connection.username();
            }
            return ConnectionTestResult.ok("Signed in as {user} on {instance}", "user", user, "instance", instance);
        } catch (ConnectorHttpException e) {
            if (e.status() == 401) return ConnectionTestResult.failed("{service} did not accept the token.", "service", "Salesforce");
            return ConnectionTestResult.failed(e.getMessage());
        } catch (ConnectorAuthException e) {
            return ConnectionTestResult.failed(e.getMessage());
        }
    }

    static boolean isLoginServer(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            String host = URI.create(url.strip()).getHost();
            host = host == null ? "" : host.toLowerCase(Locale.ROOT);
            return host.equals("login.salesforce.com") || host.equals("test.salesforce.com");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
