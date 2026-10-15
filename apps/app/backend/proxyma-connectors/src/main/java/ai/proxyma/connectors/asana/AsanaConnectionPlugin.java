package ai.proxyma.connectors.asana;

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
 * The Asana service. Two ways in:
 * <ul>
 *   <li>a personal access token (the Asana developer console), sent as a bearer;</li>
 *   <li>a sign-in through an OAuth app registered in Asana (the person's own on the single-user
 *       editions, the deployment's on Enterprise Server), asking for read scopes only. Its access
 *       tokens last an hour and are refreshed with the long-lived refresh token.</li>
 * </ul>
 * Test is "who am I" - {@code GET /users/me}.
 */
@Component
public class AsanaConnectionPlugin implements ConnectionPlugin {

    static final String AUTHORIZE_URL = "https://app.asana.com/-/oauth_authorize";
    static final String TOKEN_URL = "https://app.asana.com/-/oauth_token";
    // Asana's granular read scopes for what a source reads. The Events API has no OAuth scope of
    // its own; a sign-in it refuses simply learns of deletions at the next full pass.
    static final List<String> READ_SCOPES = List.of("workspaces:read", "projects:read", "project_sections:read",
            "tasks:read", "stories:read", "users:read", "custom_fields:read", "tags:read");

    static final ProtocolDescriptor DESCRIPTOR = ProtocolDescriptor.builder(ConnectionProtocol.ASANA)
            .displayName("Asana")
            .mark("asana")
            .auth(AuthMethod.of(AuthKind.TOKEN, "Personal access token",
                            FieldSpec.secret("token", "Personal access token").asRequired()
                                    .help("Create one in the Asana developer console, under Personal access tokens.")),
                    AuthMethod.of(AuthKind.OAUTH2_CODE, "Sign in with Asana",
                            FieldSpec.text("clientId", "Client ID")
                                    .help("An app created in the Asana developer console, with read scopes."),
                            FieldSpec.secret("clientSecret", "Client secret")))
            .baseUrl(FieldSpec.url("baseUrl", "API URL").defaultValue(AsanaApi.DEFAULT_BASE_URL).asAdvanced())
            .sourceTypes(ConnectorType.ASANA)
            .docsAnchor("asana")
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
            JsonNode me = AsanaApi.client(http, connection).getJson("users/me", Map.of("opt_fields", "name,email")).path("data");
            String name = me.path("name").asText("");
            if (name.isBlank()) name = me.path("email").asText("");
            return ConnectionTestResult.ok("Signed in as {user}", "user", name);
        } catch (ConnectorHttpException e) {
            if (e.status() == 401) return ConnectionTestResult.failed("Asana did not accept the token.");
            return ConnectionTestResult.failed(e.getMessage());
        } catch (ConnectorAuthException e) {
            return ConnectionTestResult.failed(e.getMessage());
        }
    }
}
