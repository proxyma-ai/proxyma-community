package ai.proxyma.core.connector.spi;

import ai.proxyma.core.connector.testing.LoopbackApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two pieces of the framework the built-in YouTube sign-in leans on (2026-11-08): a token's own budget
 * can answer a request in place of sending it, and a sign-in asks for its write scopes without the read
 * scopes they cover.
 */
class RequestBudgetTest {

    private LoopbackApi api;

    @BeforeEach
    void start() {
        api = LoopbackApi.start();
    }

    @AfterEach
    void stop() {
        api.close();
    }

    // A connection whose token supplier answers past a budget of `allowed` requests, recording each one asked.
    private ResolvedCredentials budgeted(int allowed, List<String> asked) {
        AtomicInteger sent = new AtomicInteger();
        TokenSupplier tokens = new TokenSupplier() {
            @Override public String accessToken() { return "tok"; }
            @Override public ApiResponse refuseBeforeSending(HttpMethod method, URI uri) {
                asked.add(method + " " + uri.getRawPath());
                if (sent.incrementAndGet() <= allowed) return null;
                return new ApiResponse(uri, 403, null,
                        "{'error':{'errors':[{'reason':'quotaExceeded'}]}}".replace('\'', '"').getBytes(StandardCharsets.UTF_8));
            }
        };
        return new ResolvedCredentials(UUID.randomUUID(), null, AuthKind.OAUTH2_CODE, api.baseUrl(), null, Map.of(),
                Map.of(), tokens);
    }

    @Test
    @DisplayName("a request past the token's budget is answered in its place, unsent and never retried")
    void refusedUnsent() {
        api.on("GET", "/items").json("{'ok':true}");
        List<String> asked = new ArrayList<>();
        ApiClient client = ConnectorHttp.direct(d -> { }).client(api.baseUrl()).service("Fake")
                .auth(ApiAuth.bearer(budgeted(1, asked))).build();

        assertThat(client.get("items").status()).isEqualTo(200);
        ApiResponse refused = client.get("items");

        assertThat(refused.status()).isEqualTo(403);
        assertThat(GoogleError.reasons(refused)).contains("quotaExceeded");
        assertThat(api.requests("/items")).hasSize(1);
        assertThat(asked).containsExactly("GET /items", "GET /items");
    }

    @Test
    @DisplayName("a request with no connection's token is never asked about")
    void plainTokenNotAsked() {
        api.on("GET", "/items").json("{'ok':true}");
        ApiClient client = ConnectorHttp.direct(d -> { }).client(api.baseUrl()).service("Fake")
                .auth(ApiAuth.bearer(ResolvedCredentials.token(api.baseUrl(), "tok"))).build();

        assertThat(client.get("items").ok()).isTrue();
    }

    @Test
    @DisplayName("with Write on, the write scopes are asked and the read scopes they cover are not")
    void writeReplacesRead() {
        OAuth2Spec spec = OAuth2Spec.builder("https://a.example/auth", "https://a.example/token")
                .scopes(List.of("read", "numbers")).writeScopes(List.of("write")).writeReplaces(List.of("read"))
                .revokeUrl("https://a.example/revoke").build();

        assertThat(spec.requestedScopes(false)).containsExactly("read", "numbers");
        assertThat(spec.requestedScopes(true)).containsExactly("numbers", "write");
        assertThat(spec.at("https://b.example/auth", "https://b.example/token").requestedScopes(true))
                .containsExactly("numbers", "write");
        assertThat(spec.at("https://b.example/auth", "https://b.example/token").revokeUrl()).isEqualTo("https://a.example/revoke");
        // A spec that replaces nothing asks for both, as before.
        OAuth2Spec plain = OAuth2Spec.builder("https://a.example/auth", "https://a.example/token")
                .scopes(List.of("read")).writeScopes(List.of("write")).build();
        assertThat(plain.requestedScopes(true)).containsExactly("read", "write");
        assertThat(plain.revokeUrl()).isNull();
    }
}
