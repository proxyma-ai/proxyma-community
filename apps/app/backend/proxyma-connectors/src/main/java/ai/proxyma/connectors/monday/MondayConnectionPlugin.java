package ai.proxyma.connectors.monday;

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

import java.util.List;
import java.util.Map;

/**
 * The monday.com service, one account per connection. Two ways in:
 * <ul>
 *   <li>a personal API token (the avatar menu > Developers > API token), sent as the bare
 *       {@code Authorization} value;</li>
 *   <li>a sign-in through an app created in the monday.com Developer Center (the person's own on
 *       the single-user editions, the deployment's on Enterprise Server), asking for read scopes
 *       only. Its tokens do not expire.</li>
 * </ul>
 * Test is "who am I" - the {@code me} query, with the account's name.
 */
@Component
public class MondayConnectionPlugin implements ConnectionPlugin {

    static final String AUTHORIZE_URL = "https://auth.monday.com/oauth2/authorize";
    static final String TOKEN_URL = "https://auth.monday.com/oauth2/token";
    static final List<String> READ_SCOPES = List.of("me:read", "account:read", "boards:read", "updates:read", "users:read");

    static final String ME = "query Me { me { name email account { name } } }";

    static final ProtocolDescriptor DESCRIPTOR = ProtocolDescriptor.builder(ConnectionProtocol.MONDAY)
            .displayName("monday.com")
            .mark("monday")
            .auth(AuthMethod.of(AuthKind.TOKEN, "Personal API token",
                            FieldSpec.secret("token", "Personal API token").asRequired()
                                    .help("In monday.com, your avatar > Developers > API token.")),
                    AuthMethod.of(AuthKind.OAUTH2_CODE, "Sign in with monday.com",
                            FieldSpec.text("clientId", "Client ID")
                                    .help("An app created in the monday.com Developer Center, with read scopes."),
                            FieldSpec.secret("clientSecret", "Client secret")))
            .baseUrl(FieldSpec.url("baseUrl", "API URL").defaultValue(MondayApi.DEFAULT_BASE_URL).asAdvanced())
            .sourceTypes(ConnectorType.MONDAY)
            .docsAnchor("monday")
            .build();

    static final OAuth2Spec OAUTH2 = OAuth2Spec.of(AUTHORIZE_URL, TOKEN_URL, READ_SCOPES);

    @Override
    public ProtocolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public OAuth2Spec oauth2() {
        return OAUTH2;
    }

    @Override
    public ConnectionTestResult test(ResolvedCredentials connection, ConnectorHttp http) {
        try {
            JsonNode me = MondayApi.query(MondayApi.client(http, connection), "Me", ME, Map.of()).path("me");
            String name = me.path("name").asText("");
            if (name.isBlank()) name = me.path("email").asText("");
            String account = me.path("account").path("name").asText("");
            if (account.isBlank()) return ConnectionTestResult.ok("Signed in as {user}", "user", name);
            return ConnectionTestResult.ok("Signed in as {user} ({account})", "user", name, "account", account);
        } catch (ConnectorHttpException e) {
            if (e.status() == 401) return ConnectionTestResult.failed("monday.com did not accept the token.");
            return ConnectionTestResult.failed(e.getMessage());
        } catch (ConnectorAuthException e) {
            return ConnectionTestResult.failed(e.getMessage());
        }
    }
}
