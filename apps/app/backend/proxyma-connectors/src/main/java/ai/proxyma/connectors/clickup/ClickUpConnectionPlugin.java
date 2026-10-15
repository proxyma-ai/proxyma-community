package ai.proxyma.connectors.clickup;

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

/**
 * The ClickUp service. Two ways in:
 * <ul>
 *   <li>a personal API token (Settings > Apps in ClickUp; it starts with {@code pk_} and does not
 *       expire), sent as the bare {@code Authorization} value;</li>
 *   <li>a sign-in through an OAuth app created in ClickUp (the person's own on the single-user
 *       editions, the deployment's on Enterprise Server). ClickUp has no scopes: the person picks the
 *       workspaces the app may read while signing in, and its access token does not expire.</li>
 * </ul>
 * Test is "who am I" - {@code GET /user}.
 */
@Component
public class ClickUpConnectionPlugin implements ConnectionPlugin {

    static final String AUTHORIZE_URL = "https://app.clickup.com/api";
    static final String TOKEN_URL = "https://api.clickup.com/api/v2/oauth/token";

    static final ProtocolDescriptor DESCRIPTOR = ProtocolDescriptor.builder(ConnectionProtocol.CLICKUP)
            .displayName("ClickUp")
            .mark("clickup")
            .auth(AuthMethod.of(AuthKind.TOKEN, "Personal API token",
                            FieldSpec.secret("token", "Personal API token").asRequired()
                                    .help("Create one in ClickUp under Settings > Apps. It starts with pk_.")),
                    AuthMethod.of(AuthKind.OAUTH2_CODE, "Sign in with ClickUp",
                            FieldSpec.text("clientId", "Client ID")
                                    .help("An app created in ClickUp under Settings > Integrations > ClickUp API."),
                            FieldSpec.secret("clientSecret", "Client secret")))
            .baseUrl(FieldSpec.url("baseUrl", "API URL").defaultValue(ClickUpApi.DEFAULT_BASE_URL).asAdvanced())
            .sourceTypes(ConnectorType.CLICKUP)
            .docsAnchor("clickup")
            .build();

    static final OAuth2Spec OAUTH2 = OAuth2Spec.of(AUTHORIZE_URL, TOKEN_URL, List.of());

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
            JsonNode user = ClickUpApi.client(http, connection).getJson("user").path("user");
            String name = user.path("username").asText("");
            if (name.isBlank()) name = user.path("email").asText("");
            return ConnectionTestResult.ok("Signed in as {user}", "user", name);
        } catch (ConnectorHttpException e) {
            if (e.status() == 401) return ConnectionTestResult.failed("ClickUp did not accept the token.");
            return ConnectionTestResult.failed(e.getMessage());
        } catch (ConnectorAuthException e) {
            return ConnectionTestResult.failed(e.getMessage());
        }
    }
}
