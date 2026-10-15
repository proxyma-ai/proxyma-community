package ai.proxyma.connectors.notion;

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
 * The Notion service. Two ways to sign in: an internal integration's token pasted (a workspace owner
 * creates the integration, and it sees only the pages and databases shared with it), or "Sign in with
 * Notion" through a public integration the operator registered - the person signing in picks the
 * pages to share on Notion's own consent screen. proxyma.ai/docs/manual/#notion has both.
 *
 * <p>Notion's OAuth takes no scopes - what the integration may do is its capabilities, set where it
 * is registered - and wants {@code owner=user} on the authorize URL and the client as HTTP Basic at
 * the token endpoint. Test is {@code GET /users/me}, the integration's own bot user.
 */
@Component
public class NotionConnectionPlugin implements ConnectionPlugin {

    static final ProtocolDescriptor DESCRIPTOR = ProtocolDescriptor.builder(ConnectionProtocol.NOTION)
            .displayName("Notion")
            .mark("notion")
            .auth(AuthMethod.of(AuthKind.TOKEN, "Integration token",
                            FieldSpec.secret("token", "Integration token").asRequired().placeholder("ntn_...")
                                    .help("From your internal integration's Configuration tab in Notion's developer "
                                            + "portal. Share each page or database to index with the integration.")),
                    AuthMethod.of(AuthKind.OAUTH2_CODE, "Sign in with Notion",
                            FieldSpec.text("clientId", "Client ID").asRequired(),
                            FieldSpec.secret("clientSecret", "Client secret").asRequired()
                                    .help("From your public Notion integration's Configuration tab. You choose the "
                                            + "pages to share when you sign in.")))
            .baseUrl(FieldSpec.url("baseUrl", "API URL").defaultValue(NotionApi.DEFAULT_BASE_URL).asAdvanced()
                    .help("Leave as it is unless you reach Notion through a relay."))
            .sourceTypes(ConnectorType.NOTION)
            .docsAnchor("notion")
            .build();

    static final OAuth2Spec OAUTH2 = OAuth2Spec.builder("https://api.notion.com/v1/oauth/authorize",
            "https://api.notion.com/v1/oauth/token").authorizeParam("owner", "user")
            .clientAuth(OAuth2Spec.ClientAuth.BASIC).tokenBody(OAuth2Spec.TokenBody.JSON)
            .tokenHeader("Notion-Version", NotionApi.VERSION).build();

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
            JsonNode me = NotionApi.client(http, connection).getJson("users/me");
            String name = me.path("name").asText("");
            if (name.isBlank()) name = me.path("id").asText("");
            String workspace = me.path("bot").path("workspace_name").asText("");
            if (workspace.isBlank()) return ConnectionTestResult.ok("Signed in as {user}", "user", name);
            return ConnectionTestResult.ok("Signed in to {workspace} as {user}", "workspace", workspace, "user", name);
        } catch (ConnectorHttpException e) {
            // The page's own wording, so it is said in the reader's language (connectionTestMessage.ts).
            if (e.status() == 401) return ConnectionTestResult.failed("{service} did not accept the token.", "service", "Notion");
            return ConnectionTestResult.failed(e.getMessage());
        } catch (ConnectorAuthException e) {
            return ConnectionTestResult.failed(e.getMessage());
        }
    }
}
