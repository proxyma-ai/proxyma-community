package ai.proxyma.core.settings.connection;

import ai.proxyma.core.connector.spi.ConnectorCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Where a connection's sign-in ends, 2026-10-31: on Desktop and Home Server the sign-in opens in the
 * person's own browser, where the app is behind the access gate, so the callback answers with a page
 * of its own - written from fixed sentences, nothing from the request. Enterprise Server's sign-in
 * stays in the app's browser and still lands back in the app.
 */
class ConnectionOAuthControllerTest {

    private final OAuth2ConnectionService oauth = mock(OAuth2ConnectionService.class);

    private ConnectionOAuthController controller(boolean multiUser) {
        return new ConnectionOAuthController(oauth, ConnectorCatalog.of(List.of(), List.of()), multiUser, "");
    }

    private static MockServerWebExchange callback(String cookie) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest
                .get("http://127.0.0.1:4246/api/v1/connections/oauth/callback?state=s1&code=c1");
        if (cookie != null) request.cookie(new HttpCookie(OAuth2ConnectionService.FLOW_COOKIE, cookie));
        return MockServerWebExchange.from(request);
    }

    @Test
    @DisplayName("a sign-in opened in the system browser ends on a page that names the service and closes itself")
    void browserSignInEndsOnItsOwnPage() {
        when(oauth.unboundService("s1")).thenReturn(Optional.of("YouTube"));
        when(oauth.complete(any())).thenReturn(OAuth2ConnectionService.Outcome.CONNECTED);
        MockServerWebExchange exchange = callback(null);

        controller(false).callback("s1", "c1", null, exchange).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange.getResponse().getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).contains("Signed in to YouTube").contains("return to Proxyma").contains("window.close()");
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.LOCATION)).isNull();
    }

    @Test
    @DisplayName("a failure in the system browser is said in plain words, with no script and nothing from the request")
    void browserFailureIsPlain() {
        when(oauth.unboundService("s1")).thenReturn(Optional.empty());
        when(oauth.complete(any())).thenReturn(OAuth2ConnectionService.Outcome.EXPIRED);
        MockServerWebExchange exchange = callback(null);

        controller(false).callback("s1", "<script>alert(1)</script>", "x", exchange).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).contains("This sign-in has expired").doesNotContain("alert(1)").doesNotContain("<script>");
    }

    @Test
    @DisplayName("a sign-in that began in the app's own browser lands back in the app")
    void boundSignInRedirects() {
        when(oauth.unboundService("s1")).thenReturn(Optional.empty());
        when(oauth.complete(any())).thenReturn(OAuth2ConnectionService.Outcome.CONNECTED);
        for (boolean multiUser : new boolean[] {false, true}) {
            MockServerWebExchange exchange = callback("binding");
            controller(multiUser).callback("s1", "c1", null, exchange).block();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FOUND);
            assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo("/?connection-oauth=connected");
        }
        // Enterprise Server never answers with the page, cookie or not.
        MockServerWebExchange noCookie = callback(null);
        controller(true).callback("s1", "c1", null, noCookie).block();
        assertThat(noCookie.getResponse().getStatusCode()).isEqualTo(HttpStatus.FOUND);
    }

    @Test
    @DisplayName("begin hands back the address to open, with the callback at the address the app was opened at")
    void beginAnswersWithTheAddress() {
        UUID id = UUID.randomUUID();
        when(oauth.begin(isNull(), eq(id), eq("http://127.0.0.1:4246" + OAuth2ConnectionService.CALLBACK_PATH)))
                .thenReturn("https://accounts.example/auth?state=s1");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("http://127.0.0.1:4246/api/v1/connections/instances/" + id + "/oauth/begin"));

        var response = controller(false).begin(id.toString(), exchange).block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(Map.of("authorizeUrl", "https://accounts.example/auth?state=s1"));
        // Never with a cookie: there is no browser to bind it to.
        assertThat(exchange.getResponse().getCookies()).isEmpty();

        when(oauth.begin(any(), any(), anyString())).thenThrow(new IllegalStateException("This sign-in opens in the same browser on a server with accounts."));
        var refused = controller(true).begin(id.toString(), MockServerWebExchange.from(
                MockServerHttpRequest.post("http://h/api/v1/connections/instances/" + id + "/oauth/begin"))).block();
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("the page escapes the service name")
    void pageEscapes() {
        assertThat(ConnectionOAuthController.page(OAuth2ConnectionService.Outcome.CONNECTED, "A<b>&\"C"))
                .contains("Signed in to A&lt;b&gt;&amp;&quot;C").doesNotContain("<b>");
    }
}
