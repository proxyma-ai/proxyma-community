package ai.proxyma.connectors.box;

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

import java.util.List;
import java.util.Map;

/**
 * The Box service. Two ways to sign in, proxyma.ai/docs/manual/#box has both:
 *
 * <ul>
 *   <li><b>Sign in with Box</b>: an OAuth2 authorization code through a Platform App (User
 *       Authentication, OAuth 2.0) the operator registers in the Box Developer Console - its client
 *       ID and secret typed on the connection on Desktop and Home Server, set once for the deployment
 *       on Enterprise Server. Box ROTATES the refresh token: each one is good for a single refresh
 *       within 60 days, and the refresh hands back the next. OAuth2ConnectionService stores the new
 *       one before the access token is used, under a per-connection lock, so it is spent once. Box
 *       does not document PKCE; the challenge and verifier the framework sends are ignored by it, and
 *       the client secret is what the exchange rests on.
 *   <li><b>Developer token</b>: one generated in the Developer Console, for trying the connector out.
 *       It expires after 60 minutes and cannot be refreshed.
 * </ul>
 *
 * <p><b>Scope</b>: {@code root_readonly} - read every file and folder the user can - asked for on the
 * sign-in, so a connection never holds more than reading needs even when the app was given more.
 *
 * <p><b>Client credentials</b> (Box's Client Credentials Grant, a Platform App with Server
 * Authentication that an enterprise admin authorized): no browser, no refresh token - a new token is
 * asked for when the last expires. Its token request names whose token it is,
 * {@code box_subject_type} ({@code enterprise} for the app's service account, {@code user} for one
 * user) and {@code box_subject_id} (the enterprise or user id), taken from the connection's
 * settings; Box documents no {@code scope} on this grant, so none is sent. The service account
 * reads what is shared with it; a user subject reads what that user can.
 *
 * <p>The sign-in and token addresses are Box's; {@code proxyma.connectors.box.authorize-url} and
 * {@code .token-url} move them for a test server (tools/testing/fake-services.mjs).
 */
@Component
public class BoxConnectionPlugin implements ConnectionPlugin {

    static final String AUTHORIZE_URL = "https://account.box.com/api/oauth2/authorize";
    static final String TOKEN_URL = "https://api.box.com/oauth2/token";
    static final List<String> SCOPES = List.of("root_readonly");
    static final String SUBJECT_TYPE = "subjectType";
    static final String SUBJECT_ID = "subjectId";

    static final ProtocolDescriptor DESCRIPTOR = ProtocolDescriptor.builder(ConnectionProtocol.BOX)
            .displayName("Box")
            .mark("box")
            .auth(AuthMethod.of(AuthKind.OAUTH2_CODE, "Sign in with Box",
                            FieldSpec.text("clientId", "Client ID").asRequired()
                                    .help("From your Platform App's Configuration tab in the Box Developer Console."),
                            FieldSpec.secret("clientSecret", "Client secret").asRequired()),
                    AuthMethod.of(AuthKind.OAUTH2_CLIENT, "Client credentials (server app)",
                            FieldSpec.text("clientId", "Client ID").asRequired()
                                    .help("From a Platform App with Server Authentication (Client Credentials Grant), "
                                            + "authorized by your Box admin."),
                            FieldSpec.secret("clientSecret", "Client secret").asRequired(),
                            FieldSpec.select(SUBJECT_TYPE, "Read as", List.of(
                                            new FieldSpec.Option("enterprise", "The app's service account"),
                                            new FieldSpec.Option("user", "One user")))
                                    .defaultValue("enterprise").asRequired(),
                            FieldSpec.text(SUBJECT_ID, "Enterprise or user ID").asRequired()
                                    .help("Your enterprise ID for the service account, else the user's ID. Both are "
                                            + "in the Box Admin Console.")),
                    AuthMethod.of(AuthKind.TOKEN, "Developer token",
                            FieldSpec.secret("token", "Developer token").asRequired()
                                    .help("Generated on your Platform App's Configuration tab in the Box Developer Console. "
                                            + "It expires after 60 minutes, so use it to try Proxyma out.")))
            .baseUrl(FieldSpec.url("baseUrl", "API URL").defaultValue(BoxApi.DEFAULT_BASE_URL).asAdvanced()
                    .help("Leave it as it is unless you are told otherwise."))
            .sourceTypes(ConnectorType.BOX)
            .docsAnchor("box")
            .build();

    private final OAuth2Spec oauth2;

    /** Box's own sign-in, for a connector's tests. */
    public BoxConnectionPlugin() {
        this(AUTHORIZE_URL, TOKEN_URL);
    }

    @Autowired
    public BoxConnectionPlugin(@Value("${proxyma.connectors.box.authorize-url:" + AUTHORIZE_URL + "}") String authorizeUrl,
                               @Value("${proxyma.connectors.box.token-url:" + TOKEN_URL + "}") String tokenUrl) {
        this.oauth2 = OAuth2Spec.builder(blankTo(authorizeUrl, AUTHORIZE_URL), blankTo(tokenUrl, TOKEN_URL)).scopes(SCOPES)
                .clientGrantParam("box_subject_type", SUBJECT_TYPE).clientGrantParam("box_subject_id", SUBJECT_ID)
                .noClientGrantScopes().build();
    }

    @Override
    public ProtocolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public OAuth2Spec oauth2() {
        return oauth2;
    }

    /** {@code GET /users/me}: the user's name and login. */
    @Override
    public ConnectionTestResult test(ResolvedCredentials connection, ConnectorHttp http) {
        try {
            JsonNode me = BoxApi.client(http, connection).getJson("users/me", Map.of("fields", "name,login"));
            String name = me.path("name").asText("");
            String login = me.path("login").asText("");
            if (name.isBlank()) return ConnectionTestResult.ok("Signed in as {user}", "user", login);
            if (login.isBlank()) return ConnectionTestResult.ok("Signed in as {user}", "user", name);
            return ConnectionTestResult.ok("Signed in as {user} ({account})", "user", name, "account", login);
        } catch (ConnectorHttpException e) {
            // The page's own wording, so it is said in the reader's language (connectionTestMessage.ts).
            if (e.status() == 401) return ConnectionTestResult.failed("{service} did not accept the token.", "service", "Box");
            return ConnectionTestResult.failed(e.getMessage());
        } catch (ConnectorAuthException e) {
            return ConnectionTestResult.failed(e.getMessage());
        }
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
