package ai.proxyma.connectors.linear;

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
 * The Linear service, one workspace per connection. Two ways in:
 * <ul>
 *   <li>a personal API key (Settings > Security & access in Linear), sent as the bare
 *       {@code Authorization} value;</li>
 *   <li>a sign-in through an OAuth application registered in Linear (the person's own on the
 *       single-user editions, the deployment's on Enterprise Server), asking for {@code read}.
 *       Linear's access tokens last 24 hours and every refresh hands back a NEW refresh token; the
 *       framework's refresh persists the rotated one before the new access token is used.</li>
 * </ul>
 * Test is "who am I" - the {@code viewer} query.
 */
@Component
public class LinearConnectionPlugin implements ConnectionPlugin {

    static final String AUTHORIZE_URL = "https://linear.app/oauth/authorize";
    static final String TOKEN_URL = "https://api.linear.app/oauth/token";
    static final String READ_SCOPE = "read";

    static final String VIEWER = "query Viewer { viewer { id name email } }";

    static final ProtocolDescriptor DESCRIPTOR = ProtocolDescriptor.builder(ConnectionProtocol.LINEAR)
            .displayName("Linear")
            .mark("linear")
            .auth(AuthMethod.of(AuthKind.TOKEN, "Personal API key",
                            FieldSpec.secret("token", "Personal API key").asRequired()
                                    .help("Create one in Linear under Settings > Security & access.")),
                    AuthMethod.of(AuthKind.OAUTH2_CODE, "Sign in with Linear",
                            FieldSpec.text("clientId", "Client ID")
                                    .help("An OAuth application created in Linear under Settings > API."),
                            FieldSpec.secret("clientSecret", "Client secret")))
            .baseUrl(FieldSpec.url("baseUrl", "API URL").defaultValue(LinearApi.DEFAULT_BASE_URL).asAdvanced())
            .sourceTypes(ConnectorType.LINEAR)
            .docsAnchor("linear")
            .build();

    static final OAuth2Spec OAUTH2 = OAuth2Spec.of(AUTHORIZE_URL, TOKEN_URL, List.of(READ_SCOPE));

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
            JsonNode viewer = LinearApi.query(LinearApi.client(http, connection), "Viewer", VIEWER, Map.of()).path("viewer");
            String name = viewer.path("name").asText("");
            if (name.isBlank()) name = viewer.path("email").asText("");
            return ConnectionTestResult.ok("Signed in as {user}", "user", name);
        } catch (ConnectorHttpException e) {
            if (e.status() == 401) return ConnectionTestResult.failed("Linear did not accept the key.");
            return ConnectionTestResult.failed(e.getMessage());
        } catch (ConnectorAuthException e) {
            return ConnectionTestResult.failed(e.getMessage());
        }
    }
}
