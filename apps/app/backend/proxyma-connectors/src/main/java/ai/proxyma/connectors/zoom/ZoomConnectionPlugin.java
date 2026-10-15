package ai.proxyma.connectors.zoom;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.ApiClient;
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

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * The Zoom service, one Zoom account per connection. Two ways in:
 * <ul>
 *   <li>a <b>Server-to-Server OAuth app</b> an account administrator creates in the Zoom App
 *       Marketplace: its account ID, client ID and client secret. It reads as the account, so a
 *       source names the people whose meetings it reads. Signed in by this connector
 *       ({@link ZoomAccountToken}) - Zoom's grant is not the framework's client-credentials one;</li>
 *   <li><b>Sign in with Zoom</b>: an authorization code with PKCE through an OAuth app (a General
 *       app, user-managed) of the operator's - the person's own on the single-user editions, the
 *       deployment's on Enterprise Server. It reads as the person who signs in. Zoom wants the client
 *       as HTTP Basic at its token endpoint, and rotates the refresh token on every refresh, which the
 *       framework stores before use.</li>
 * </ul>
 *
 * <p><b>Scopes</b> are the app's own: Zoom takes the ones chosen when the app was built and ignores a
 * {@code scope} parameter, so none is sent. proxyma.ai/docs/manual/#zoom lists the ones to choose.
 *
 * <p>Test is "who am I": {@code GET /users/me}, "Signed in as Ana Lee (ana@acme.com)". A
 * Server-to-Server app is not a person, and Zoom does not promise it a "me"; its Test says Connected
 * once its token is granted.
 *
 * <p>The sign-in and token addresses are Zoom's; {@code proxyma.connectors.zoom.authorize-url} and
 * {@code .token-url} move them for a test server (tools/testing/fake-services.mjs).
 */
@Component
public class ZoomConnectionPlugin implements ConnectionPlugin {

    static final ProtocolDescriptor DESCRIPTOR = ProtocolDescriptor.builder(ConnectionProtocol.ZOOM)
            .displayName("Zoom")
            .mark("zoom")
            .auth(AuthMethod.of(AuthKind.KEY_PAIR, "Server-to-Server OAuth app",
                            FieldSpec.text(ZoomApi.ACCOUNT_ID, "Account ID").asRequired()
                                    .help("From the app's App Credentials page in the Zoom App Marketplace."),
                            FieldSpec.text(ZoomApi.CLIENT_ID, "Client ID").asRequired(),
                            FieldSpec.secret(ZoomApi.CLIENT_SECRET, "Client secret").asRequired()),
                    AuthMethod.of(AuthKind.OAUTH2_CODE, "Sign in with Zoom",
                            FieldSpec.text("clientId", "Client ID")
                                    .help("From the Zoom app you created for Proxyma, under App Credentials."),
                            FieldSpec.secret("clientSecret", "Client secret")))
            .baseUrl(FieldSpec.url("baseUrl", "API URL").defaultValue(ZoomApi.DEFAULT_BASE_URL).asAdvanced()
                    .help("Leave it as it is unless you are told otherwise."))
            .sourceTypes(ConnectorType.ZOOM)
            .docsAnchor("zoom")
            .build();

    private final OAuth2Spec oauth2;
    private final String tokenUrl;

    /** Zoom's own sign-in, for a connector's tests. */
    public ZoomConnectionPlugin() {
        this(ZoomApi.AUTHORIZE_URL, ZoomApi.TOKEN_URL);
    }

    @Autowired
    public ZoomConnectionPlugin(@Value("${proxyma.connectors.zoom.authorize-url:" + ZoomApi.AUTHORIZE_URL + "}") String authorizeUrl,
                                @Value("${proxyma.connectors.zoom.token-url:" + ZoomApi.TOKEN_URL + "}") String tokenUrl) {
        this.tokenUrl = blankTo(tokenUrl, ZoomApi.TOKEN_URL);
        this.oauth2 = OAuth2Spec.builder(blankTo(authorizeUrl, ZoomApi.AUTHORIZE_URL), this.tokenUrl)
                .clientAuth(OAuth2Spec.ClientAuth.BASIC).build();
    }

    @Override
    public ProtocolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public OAuth2Spec oauth2() {
        return oauth2;
    }

    @Override
    public ConnectionTestResult test(ResolvedCredentials connection, ConnectorHttp http) {
        try {
            ApiClient zoom = ZoomApi.client(http, connection, tokenUrl, Clock.systemUTC());
            boolean account = connection.authKind() == AuthKind.KEY_PAIR;
            JsonNode me;
            try {
                me = zoom.getJson("users/me");
            } catch (ConnectorHttpException e) {
                // A Server-to-Server app whose token was granted is connected, whether or not Zoom
                // answers it a "me" (it may lack user:read, or "me" may mean nobody to it).
                if (account && e.status() != 401 && e.status() != 429 && e.status() < 500) return ConnectionTestResult.ok("Connected");
                throw e;
            }
            return ConnectionTestResult.ok("Signed in as {user}", "user", who(me));
        } catch (ConnectorHttpException e) {
            if (e.status() == 401) return ConnectionTestResult.failed("{service} did not accept the token.", "service", "Zoom");
            return ConnectionTestResult.failed(e.getMessage());
        } catch (ConnectorAuthException e) {
            return ConnectionTestResult.failed(e.getMessage());
        }
    }

    /** "Ana Lee (ana@acme.com)" - the name and the address Zoom has for a user, either alone where one is missing. */
    static String who(JsonNode user) {
        String name = (user.path("first_name").asText("") + " " + user.path("last_name").asText("")).strip();
        if (name.isEmpty()) name = user.path("display_name").asText("").strip();
        String email = user.path("email").asText("").strip();
        if (name.isEmpty()) return email;
        return email.isEmpty() ? name : name + " (" + email + ")";
    }

    String tokenUrl() {
        return tokenUrl;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
