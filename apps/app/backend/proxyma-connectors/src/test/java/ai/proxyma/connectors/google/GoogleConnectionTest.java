package ai.proxyma.connectors.google;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.AuthKind;
import ai.proxyma.core.connector.spi.AuthMethod;
import ai.proxyma.core.connector.spi.ConnectionTestResult;
import ai.proxyma.core.connector.spi.ConnectorAuthException;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.FieldSpec;
import ai.proxyma.core.connector.spi.OAuth2Spec;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import ai.proxyma.core.connector.spi.TokenSupplier;
import ai.proxyma.core.connector.testing.LoopbackApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Google connection: how it signs in (what the framework's OAuth2 flow and service-account
 * assertion read from it), its Test against a loopback Google, and a refused token refreshed once.
 */
class GoogleConnectionTest {

    private LoopbackApi api;
    private final GoogleConnectionPlugin plugin = new GoogleConnectionPlugin();

    @BeforeEach
    void start() {
        api = LoopbackApi.start();
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private ResolvedCredentials token() {
        return ResolvedCredentials.token(api.baseUrl(), "ya29.test");
    }

    @Test
    @DisplayName("Test names the account Drive says it is")
    void signedInAs() {
        api.on("GET", "/drive/v3/about").json("{'user':{'displayName':'Ana Lee','emailAddress':'ana@example.com'}}");
        ConnectionTestResult ok = plugin.test(token(), ConnectorHttp.direct());
        assertThat(ok.ok()).isTrue();
        assertThat(ok.message()).isEqualTo("Signed in as ana@example.com");
        assertThat(ok.template()).isEqualTo("Signed in as {user}");
        assertThat(api.last().param("fields")).isEqualTo("user(displayName,emailAddress)");
        assertThat(api.last().header("Authorization")).isEqualTo("Bearer ya29.test");
    }

    @Test
    @DisplayName("a project without the Drive API is asked through Gmail; one with none of the three APIs says so")
    void apisNotEnabled() {
        api.on("GET", "/drive/v3/about").status(403).json("{'error':{'code':403,'message':'Drive API has not been used'}}");
        api.on("GET", "/gmail/v1/users/me/profile").json("{'emailAddress':'bo@example.com','historyId':'9'}");
        assertThat(plugin.test(token(), ConnectorHttp.direct()).message()).isEqualTo("Signed in as bo@example.com");

        api.reset();
        api.on("GET", "/drive/v3/about").status(403);
        api.on("GET", "/gmail/v1/users/me/profile").status(403);
        api.on("GET", "/calendar/v3/calendars/primary").status(403);
        ConnectionTestResult refused = plugin.test(token(), ConnectorHttp.direct());
        assertThat(refused.ok()).isFalse();
        assertThat(refused.message()).isEqualTo("Google refused access. Check that the Google Drive, Gmail and "
                + "Google Calendar APIs are enabled in the Google Cloud project.");
    }

    @Test
    @DisplayName("a refused token is a sentence, and a connection that cannot get one says why")
    void refusals() {
        api.on("GET", "/drive/v3/about").status(401).json("{'error':{'code':401}}");
        assertThat(plugin.test(token(), ConnectorHttp.direct()).message())
                .isEqualTo("Google did not accept the connection's sign-in or service account key.");

        TokenSupplier signedOut = () -> {
            throw new ConnectorAuthException("Sign in to Google again in Settings > Connections.");
        };
        ResolvedCredentials none = new ResolvedCredentials(null, null, AuthKind.OAUTH2_CODE, api.baseUrl(), null,
                Map.of(), Map.of(), signedOut);
        assertThat(plugin.test(none, ConnectorHttp.direct()).message()).isEqualTo("Sign in to Google again in Settings > Connections.");
    }

    @Test
    @DisplayName("a sign-in's token Google refuses is refreshed once, then sent again")
    void refusedTokenIsRefreshedOnce() {
        api.on("GET", "/drive/v3/about").status(401).then().json("{'user':{'emailAddress':'ana@example.com'}}");
        AtomicInteger issued = new AtomicInteger();
        TokenSupplier tokens = new TokenSupplier() {
            private String current = "old";
            @Override public String accessToken() { return current; }
            @Override public void invalidate() { current = "fresh-" + issued.incrementAndGet(); }
        };
        ResolvedCredentials signedIn = new ResolvedCredentials(null, null, AuthKind.OAUTH2_CODE, api.baseUrl(), null,
                Map.of(), Map.of(), tokens);
        assertThat(plugin.test(signedIn, ConnectorHttp.direct()).message()).isEqualTo("Signed in as ana@example.com");
        assertThat(api.requests()).extracting(r -> r.header("Authorization")).containsExactly("Bearer old", "Bearer fresh-1");
    }

    @Test
    @DisplayName("the sign-in: PKCE through Google with a refresh token every time, the three read-only scopes, and no write scope")
    void oauthSpec() {
        OAuth2Spec spec = plugin.oauth2();
        assertThat(spec.authorizeUrl()).isEqualTo("https://accounts.google.com/o/oauth2/v2/auth");
        assertThat(spec.tokenUrl()).isEqualTo("https://oauth2.googleapis.com/token");
        assertThat(spec.scopes()).containsExactly("https://www.googleapis.com/auth/drive.readonly",
                "https://www.googleapis.com/auth/gmail.readonly", "https://www.googleapis.com/auth/calendar.readonly");
        assertThat(spec.writeScopes()).isEmpty();
        assertThat(spec.extraAuthorizeParams()).containsEntry("access_type", "offline").containsEntry("prompt", "consent");

        // A test server's sign-in, from the deployment's settings.
        OAuth2Spec moved = new GoogleConnectionPlugin(api.url("/google/oauth/authorize"), api.url("/google/oauth/token")).oauth2();
        assertThat(moved.tokenUrl()).isEqualTo(api.url("/google/oauth/token"));
        assertThat(new GoogleConnectionPlugin(" ", "").oauth2().tokenUrl()).isEqualTo(GoogleConnectionPlugin.TOKEN_URL);
    }

    @Test
    @DisplayName("the descriptor: one service for three source types, signed in by a client of your own or a service account")
    void descriptor() {
        var d = GoogleConnectionPlugin.DESCRIPTOR;
        assertThat(d.sourceTypes()).containsExactlyInAnyOrder(ConnectorType.GOOGLE_DRIVE, ConnectorType.GMAIL,
                ConnectorType.GOOGLE_CALENDAR);
        assertThat(d.authMethods()).extracting(AuthMethod::kind).containsExactly(AuthKind.OAUTH2_CODE, AuthKind.SERVICE_ACCOUNT);
        // The keys OAuth2ConnectionService reads - a renamed field would sign nothing in.
        assertThat(d.auth(AuthKind.OAUTH2_CODE).fields()).extracting(FieldSpec::key).containsExactly("clientId", "clientSecret");
        AuthMethod account = d.auth(AuthKind.SERVICE_ACCOUNT);
        assertThat(account.secretFields()).extracting(FieldSpec::key).containsExactly("serviceAccountJson");
        assertThat(account.settingFields()).extracting(FieldSpec::key).containsExactly("subject");
        assertThat(d.defaultBaseUrl()).isEqualTo("https://www.googleapis.com");
        assertThat(d.baseUrl().advanced()).isTrue();
        assertThat(d.supportsWrite()).isFalse();
    }
}
