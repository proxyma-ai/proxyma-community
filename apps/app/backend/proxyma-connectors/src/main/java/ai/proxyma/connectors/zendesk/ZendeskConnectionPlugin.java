package ai.proxyma.connectors.zendesk;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Zendesk service, one account (one subdomain) per connection. Two ways in:
 * <ul>
 *   <li>an agent's email address and an API token (Admin Center > Apps and integrations > APIs >
 *       Zendesk API, with token access turned on), sent as {@code email/token:API_TOKEN} Basic;</li>
 *   <li>a sign-in through an OAuth client registered in that Zendesk account (the person's own on the
 *       single-user editions, the deployment's on Enterprise Server), asking for {@code read}.</li>
 * </ul>
 *
 * <p><b>Every Zendesk account signs in at its own address</b> -
 * {@code https://SUBDOMAIN.zendesk.com/oauth/authorizations/new} and {@code .../oauth/tokens} - so
 * the sign-in is worked out per connection ({@link #oauth2(Map, String)}): from its subdomain, else
 * from its Zendesk address when that is on zendesk.com. The OAuth client's secret is sent to a
 * zendesk.com host and to no other, whatever was typed. {@code proxyma.connectors.zendesk.oauth-url}
 * moves the sign-in to a test server (tools/testing/fake-services.mjs).
 *
 * <p>Test is "who am I" - {@code GET /users/me} - and names the account: "Signed in as Ana on acme".
 * Zendesk answers an unauthenticated {@code /users/me} with an anonymous user rather than a 401, so
 * an anonymous answer is a refusal too.
 */
@Component
public class ZendeskConnectionPlugin implements ConnectionPlugin {

    static final String READ_SCOPE = "read";
    static final String AUTHORIZE_PATH = "/oauth/authorizations/new";
    static final String TOKEN_PATH = "/oauth/tokens";

    static final ProtocolDescriptor DESCRIPTOR = ProtocolDescriptor.builder(ConnectionProtocol.ZENDESK)
            .displayName("Zendesk")
            .mark("zendesk")
            .auth(AuthMethod.of(AuthKind.BASIC, "Email and API token",
                            FieldSpec.text(ZendeskApi.SUBDOMAIN, "Subdomain").asRequired().placeholder("yourcompany")
                                    .pattern("[A-Za-z0-9][A-Za-z0-9-]*")
                                    .help("The first part of your Zendesk address - yourcompany in yourcompany.zendesk.com."),
                            FieldSpec.text(AuthMethod.USERNAME, "Agent email address").asRequired()
                                    .placeholder("you@example.com")
                                    .help("The email address of the agent whose access is used."),
                            FieldSpec.secret(ZendeskApi.TOKEN, "API token").asRequired()
                                    .help("Create one in Admin Center under Apps and integrations > APIs > Zendesk API, "
                                            + "with token access turned on.")),
                    // Not required here: on Enterprise Server a sign-in's fields are not shown (the
                    // OAuth client is the deployment's), and the account is the Zendesk address instead.
                    AuthMethod.of(AuthKind.OAUTH2_CODE, "Sign in with Zendesk",
                            FieldSpec.text(ZendeskApi.SUBDOMAIN, "Subdomain").placeholder("yourcompany")
                                    .pattern("[A-Za-z0-9][A-Za-z0-9-]*")
                                    .help("The first part of your Zendesk address - yourcompany in yourcompany.zendesk.com."),
                            FieldSpec.text("clientId", "Client identifier")
                                    .help("An OAuth client created in Admin Center under Apps and integrations > APIs > "
                                            + "OAuth Clients."),
                            FieldSpec.secret("clientSecret", "Client secret")))
            .baseUrl(FieldSpec.url("baseUrl", "Zendesk address").placeholder("https://yourcompany.zendesk.com").asAdvanced()
                    .help("Only for an address other than your subdomain's, such as your own domain."))
            .sourceTypes(ConnectorType.ZENDESK)
            .docsAnchor("zendesk")
            .build();

    private final String oauthUrl;

    /** Each account's own sign-in, for a connector's tests. */
    public ZendeskConnectionPlugin() {
        this("");
    }

    @Autowired
    public ZendeskConnectionPlugin(@Value("${proxyma.connectors.zendesk.oauth-url:}") String oauthUrl) {
        this.oauthUrl = oauthUrl == null ? "" : oauthUrl.strip().replaceAll("/+$", "");
    }

    @Override
    public ProtocolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    /**
     * The service-wide answer, for what asks whether Zendesk signs in at all; a sign-in itself is
     * always worked out for its connection.
     */
    @Override
    public OAuth2Spec oauth2() {
        String base = oauthUrl.isEmpty() ? "https://subdomain.zendesk.com" : oauthUrl;
        return spec(base);
    }

    /**
     * The connection's own account: {@code https://SUBDOMAIN.zendesk.com}, from its subdomain, else
     * from its Zendesk address when that is a zendesk.com host. Null - no sign-in - for anything else,
     * so the client secret never leaves zendesk.com.
     */
    @Override
    public OAuth2Spec oauth2(Map<String, String> settings, String baseUrl) {
        if (!oauthUrl.isEmpty()) return spec(oauthUrl);
        String subdomain = ZendeskApi.subdomain(settings);
        if (subdomain == null && baseUrl != null && !baseUrl.isBlank()) {
            try {
                String host = URI.create(baseUrl.strip()).getHost();
                host = host == null ? "" : host.toLowerCase(Locale.ROOT);
                if (host.endsWith(".zendesk.com")) {
                    subdomain = ZendeskApi.subdomain(Map.of(ZendeskApi.SUBDOMAIN, host));
                }
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return subdomain == null ? null : spec("https://" + subdomain + ".zendesk.com");
    }

    private static OAuth2Spec spec(String account) {
        return OAuth2Spec.of(account + AUTHORIZE_PATH, account + TOKEN_PATH, List.of(READ_SCOPE));
    }

    @Override
    public ConnectionTestResult test(ResolvedCredentials connection, ConnectorHttp http) {
        String refused = connection.authKind() == AuthKind.BASIC
                ? "Zendesk did not accept the email address and API token."
                : "Zendesk did not accept the sign-in. Sign in again.";
        try {
            JsonNode me = ZendeskApi.client(http, connection).getJson("users/me").path("user");
            // An anonymous user has no id: Zendesk's answer to a credential it did not take.
            if (me.path("id").isNull() || me.path("id").isMissingNode()) return ConnectionTestResult.failed(refused);
            String name = me.path("name").asText("");
            if (name.isBlank()) name = me.path("email").asText("");
            return ConnectionTestResult.ok("Signed in as {user} on {site}", "user", name,
                    "site", ZendeskApi.accountName(connection));
        } catch (ConnectorHttpException e) {
            if (e.status() == 401) return ConnectionTestResult.failed(refused);
            if (e.status() == 404) {
                return ConnectionTestResult.failed("Zendesk has no account at that subdomain. Check the subdomain.");
            }
            return ConnectionTestResult.failed(e.getMessage());
        } catch (ConnectorAuthException e) {
            return ConnectionTestResult.failed(e.getMessage());
        }
    }
}
