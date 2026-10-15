package ai.proxyma.connectors.google;

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

import java.util.List;
import java.util.Map;

/**
 * The Google service - ONE connection for Google Drive, Gmail and Google Calendar (a credential
 * family). It signs in two ways, both handled by the framework's OAuth2ConnectionService:
 *
 * <ul>
 *   <li><b>Sign in with Google</b>: an OAuth2 authorization code with PKCE through an OAuth client
 *       the operator registers in Google Cloud - typed on the connection on Desktop and Home Server,
 *       set once for the deployment on Enterprise Server. {@code access_type=offline} and
 *       {@code prompt=consent} so Google hands back a refresh token every time.
 *   <li><b>Service account</b>: a Workspace service account's JSON key and the user it acts as,
 *       through domain-wide delegation - a signed JWT assertion (RFC 7523) exchanged for a token,
 *       with the user as its {@code sub}. No consent screen and no refresh token to expire.
 * </ul>
 *
 * <p><b>Scopes</b>: the three read-only ones, all requested at once. The framework asks for a
 * connection's scopes as the service declares them, not per source, so a connection signed in once
 * serves whichever of the three sources is added later.
 *
 * <p>The sign-in and token addresses are Google's; {@code proxyma.connectors.google.authorize-url}
 * and {@code .token-url} move them for a test server (tools/testing/fake-services.mjs). The API
 * address is the connection's own, under More options.
 */
@Component
public class GoogleConnectionPlugin implements ConnectionPlugin {

    static final String AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    static final List<String> SCOPES = List.of(
            "https://www.googleapis.com/auth/drive.readonly",
            "https://www.googleapis.com/auth/gmail.readonly",
            "https://www.googleapis.com/auth/calendar.readonly");

    // The keys OAuth2ConnectionService reads: the client on the connection's settings and secret,
    // the service account's key as its secret and the user it acts as as a setting.
    static final String CLIENT_ID = "clientId";
    static final String CLIENT_SECRET = "clientSecret";
    static final String SERVICE_ACCOUNT_JSON = "serviceAccountJson";
    static final String SUBJECT = "subject";

    static final ProtocolDescriptor DESCRIPTOR = ProtocolDescriptor.builder(ConnectionProtocol.GOOGLE)
            .displayName("Google")
            .mark("google")
            .auth(AuthMethod.of(AuthKind.OAUTH2_CODE, "Sign in with Google",
                            FieldSpec.text(CLIENT_ID, "Client ID")
                                    .placeholder("1234567890-abc.apps.googleusercontent.com")
                                    .help("From the OAuth client you created in Google Cloud, under APIs & Services > Credentials."),
                            FieldSpec.secret(CLIENT_SECRET, "Client secret")),
                    AuthMethod.of(AuthKind.SERVICE_ACCOUNT, "Service account",
                            FieldSpec.secret(SERVICE_ACCOUNT_JSON, "Service account key").asRequired()
                                    .help("The whole JSON key file of a service account with domain-wide delegation."),
                            FieldSpec.text(SUBJECT, "User to act as").asRequired()
                                    .placeholder("name@example.com")
                                    .pattern("[^@\\s]+@[^@\\s]+")
                                    .help("The Workspace user whose Drive, mail and calendar are read.")))
            .baseUrl(FieldSpec.url("baseUrl", "API URL").defaultValue(GoogleApi.DEFAULT_BASE_URL).asAdvanced()
                    .help("Leave it as it is unless you are told otherwise."))
            .sourceTypes(ConnectorType.GOOGLE_DRIVE, ConnectorType.GMAIL, ConnectorType.GOOGLE_CALENDAR)
            .docsAnchor("google")
            .build();

    private final OAuth2Spec oauth2;

    /** Google's own sign-in, for a connector's tests. */
    public GoogleConnectionPlugin() {
        this(AUTHORIZE_URL, TOKEN_URL);
    }

    @Autowired
    public GoogleConnectionPlugin(@Value("${proxyma.connectors.google.authorize-url:" + AUTHORIZE_URL + "}") String authorizeUrl,
                                  @Value("${proxyma.connectors.google.token-url:" + TOKEN_URL + "}") String tokenUrl) {
        this.oauth2 = OAuth2Spec.builder(blankTo(authorizeUrl, AUTHORIZE_URL), blankTo(tokenUrl, TOKEN_URL)).scopes(SCOPES)
                .authorizeParam("access_type", "offline").authorizeParam("prompt", "consent").build();
    }

    @Override
    public ProtocolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public OAuth2Spec oauth2() {
        return oauth2;
    }

    /**
     * Who the connection reads as: Drive's "about", else Gmail's profile, else the primary calendar -
     * the first of the three APIs the Google Cloud project has switched on answers. A 403 from all
     * three is a project with none of them enabled, which is what the sentence says.
     */
    @Override
    public ConnectionTestResult test(ResolvedCredentials connection, ConnectorHttp http) {
        try {
            ApiClient google = GoogleApi.client(http, connection, "Google");
            String who = null;
            int refused = 0;
            for (String[] probe : PROBES) {
                try {
                    JsonNode answer = google.getJson(probe[0], probe[1] == null ? Map.of() : Map.of("fields", probe[1]));
                    who = switch (probe[2]) {
                        case "about" -> firstNonBlank(answer.path("user").path("emailAddress").asText(""),
                                answer.path("user").path("displayName").asText(""));
                        case "profile" -> answer.path("emailAddress").asText("");
                        default -> answer.path("id").asText("");
                    };
                    break;
                } catch (ConnectorHttpException e) {
                    if (e.status() != 403) throw e;
                    refused++;
                }
            }
            if (who == null && refused == PROBES.length) {
                return ConnectionTestResult.failed("Google refused access. Check that the Google Drive, Gmail and "
                        + "Google Calendar APIs are enabled in the Google Cloud project.");
            }
            return ConnectionTestResult.ok("Signed in as {user}", "user", who == null ? "" : who);
        } catch (ConnectorHttpException e) {
            if (e.status() == 401) {
                return ConnectionTestResult.failed("Google did not accept the connection's sign-in or service account key.");
            }
            return ConnectionTestResult.failed(e.getMessage());
        } catch (ConnectorAuthException e) {
            return ConnectionTestResult.failed(e.getMessage());
        }
    }

    // path, fields, kind - in the order they are tried.
    private static final String[][] PROBES = {
            {"drive/v3/about", "user(displayName,emailAddress)", "about"},
            {"gmail/v1/users/me/profile", null, "profile"},
            {"calendar/v3/calendars/primary", null, "calendar"},
    };

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
