package ai.proxyma.connectors.dropbox;

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
 * The Dropbox service. Two ways to sign in, proxyma.ai/docs/manual/#dropbox has both:
 *
 * <ul>
 *   <li><b>Sign in with Dropbox</b>: an OAuth2 authorization code with PKCE through a scoped app the
 *       operator registers in the Dropbox App Console - its app key and secret typed on the
 *       connection on Desktop and Home Server, set once for the deployment on Enterprise Server.
 *       {@code token_access_type=offline} so Dropbox hands back a refresh token, which does not expire
 *       on its own and is not rotated; the access token lasts four hours and the framework refreshes
 *       it (OAuth2ConnectionService).
 *   <li><b>Access token</b>: one generated on the app's Settings tab, for trying the connector out.
 *       It expires after four hours and cannot be refreshed.
 * </ul>
 *
 * <p><b>Scopes</b>: the three read-only ones the connector uses - {@code account_info.read} for the
 * Test and the account's root, {@code files.metadata.read} for listings, {@code files.content.read}
 * for downloads and Paper exports. The app has to have them checked in the App Console; a sign-in
 * cannot ask for more than the app was given.
 *
 * <p>The sign-in and token addresses are Dropbox's; {@code proxyma.connectors.dropbox.authorize-url}
 * and {@code .token-url} move them for a test server (tools/testing/fake-services.mjs).
 */
@Component
public class DropboxConnectionPlugin implements ConnectionPlugin {

    static final String AUTHORIZE_URL = "https://www.dropbox.com/oauth2/authorize";
    static final String TOKEN_URL = "https://api.dropboxapi.com/oauth2/token";
    static final List<String> SCOPES = List.of("account_info.read", "files.metadata.read", "files.content.read");

    static final ProtocolDescriptor DESCRIPTOR = ProtocolDescriptor.builder(ConnectionProtocol.DROPBOX)
            .displayName("Dropbox")
            .mark("dropbox")
            .auth(AuthMethod.of(AuthKind.OAUTH2_CODE, "Sign in with Dropbox",
                            FieldSpec.text("clientId", "App key").asRequired()
                                    .help("From your app's Settings tab in the Dropbox App Console."),
                            FieldSpec.secret("clientSecret", "App secret")
                                    .help("Optional: the sign-in works without it.")),
                    AuthMethod.of(AuthKind.TOKEN, "Access token",
                            FieldSpec.secret("token", "Access token").asRequired().placeholder("sl.")
                                    .help("Generated on your app's Settings tab in the Dropbox App Console. "
                                            + "It expires after four hours, so use it to try Proxyma out.")))
            .baseUrl(FieldSpec.url("baseUrl", "API URL").defaultValue(DropboxApi.DEFAULT_BASE_URL).asAdvanced()
                    .help("Leave it as it is unless you are told otherwise."))
            .sourceTypes(ConnectorType.DROPBOX)
            .docsAnchor("dropbox")
            .build();

    private final OAuth2Spec oauth2;

    /** Dropbox's own sign-in, for a connector's tests. */
    public DropboxConnectionPlugin() {
        this(AUTHORIZE_URL, TOKEN_URL);
    }

    @Autowired
    public DropboxConnectionPlugin(@Value("${proxyma.connectors.dropbox.authorize-url:" + AUTHORIZE_URL + "}") String authorizeUrl,
                                   @Value("${proxyma.connectors.dropbox.token-url:" + TOKEN_URL + "}") String tokenUrl) {
        this.oauth2 = OAuth2Spec.builder(blankTo(authorizeUrl, AUTHORIZE_URL), blankTo(tokenUrl, TOKEN_URL)).scopes(SCOPES)
                .authorizeParam("token_access_type", "offline").build();
    }

    @Override
    public ProtocolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public OAuth2Spec oauth2() {
        return oauth2;
    }

    /** {@code users/get_current_account}: the account's name and email. */
    @Override
    public ConnectionTestResult test(ResolvedCredentials connection, ConnectorHttp http) {
        try {
            JsonNode account = new DropboxApi(http, connection).account();
            String name = account.path("name").path("display_name").asText("");
            String email = account.path("email").asText("");
            if (name.isBlank()) return ConnectionTestResult.ok("Signed in as {user}", "user", email);
            if (email.isBlank()) return ConnectionTestResult.ok("Signed in as {user}", "user", name);
            return ConnectionTestResult.ok("Signed in as {user} ({account})", "user", name, "account", email);
        } catch (ConnectorHttpException e) {
            // The page's own wording, so it is said in the reader's language (connectionTestMessage.ts).
            if (e.status() == 401) return ConnectionTestResult.failed("{service} did not accept the token.", "service", "Dropbox");
            return ConnectionTestResult.failed(e.getMessage());
        } catch (ConnectorAuthException e) {
            return ConnectionTestResult.failed(e.getMessage());
        }
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
