package ai.proxyma.core.settings.provider;

import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.testing.LoopbackApi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SIGN IN WITH OPENROUTER (2026-11-07: an AI model with no key copied). The browser goes to OpenRouter with a PKCE
 * challenge and comes back with a code, which only the verifier kept here exchanges; the key becomes the OpenRouter
 * provider - a new one, or the one there signed in again - and the sign-in is good once, for ten minutes, and never on
 * Enterprise Server.
 */
class OpenRouterSignInTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REDIRECT = "http://127.0.0.1:4246" + OpenRouterSignIn.CALLBACK_PATH;

    private LoopbackApi openRouter;
    private ProviderInstanceService providers;
    private final List<ProviderInstanceView> existing = new ArrayList<>();
    private Instant now = Instant.parse("2026-11-07T08:00:00Z");
    private OpenRouterSignIn signIn;

    @BeforeEach
    void setUp() {
        openRouter = LoopbackApi.start();
        providers = mock(ProviderInstanceService.class);
        when(providers.list()).thenAnswer(inv -> List.copyOf(existing));
        when(providers.create(any())).thenAnswer(inv -> view(UUID.randomUUID(),
                inv.<ProviderInstanceService.ProviderInstanceInput>getArgument(0).name()));
        signIn = service(false);
    }

    @AfterEach
    void stop() {
        openRouter.close();
    }

    private OpenRouterSignIn service(boolean multiUser) {
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now; }
        };
        return new OpenRouterSignIn(providers, ConnectorHttp.direct(d -> { }), multiUser, openRouter.url("/auth"),
                openRouter.url("/api/v1/auth/keys"), clock);
    }

    private static ProviderInstanceView view(UUID id, String name) {
        return new ProviderInstanceView(id, name, ProviderProtocol.OPENROUTER, "OpenRouter", null, "sk-...", true, Map.of(), false);
    }

    private static Map<String, String> query(String url) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            out.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    @Test
    @DisplayName("the browser goes to OpenRouter with an S256 challenge; the code is exchanged with the verifier, and the key becomes the provider")
    void signsIn() throws Exception {
        openRouter.on("POST", "/api/v1/auth/keys").json("{'key':'sk-or-v1-abc','user_id':'u1'}");
        String url = signIn.begin(REDIRECT);
        assertThat(url).startsWith(openRouter.url("/auth") + "?");
        Map<String, String> q = query(url);
        assertThat(q).containsEntry("callback_url", REDIRECT).containsEntry("code_challenge_method", "S256");
        assertThat(signIn.status().state()).isEqualTo("PENDING");

        assertThat(signIn.complete("the-code", null)).isEqualTo(OpenRouterSignIn.Outcome.SIGNED_IN);
        JsonNode sent = JSON.readTree(openRouter.last().body());
        assertThat(sent.path("code").asText()).isEqualTo("the-code");
        assertThat(OpenRouterSignIn.challenge(sent.path("code_verifier").asText())).as("the verifier stayed here")
                .isEqualTo(q.get("code_challenge"));

        ArgumentCaptor<ProviderInstanceService.ProviderInstanceInput> made = ArgumentCaptor.forClass(ProviderInstanceService.ProviderInstanceInput.class);
        verify(providers).create(made.capture());
        assertThat(made.getValue().protocol()).isEqualTo(ProviderProtocol.OPENROUTER);
        assertThat(made.getValue().apiKey()).isEqualTo("sk-or-v1-abc");
        assertThat(made.getValue().name()).isEqualTo("OpenRouter");
        assertThat(signIn.status().state()).isEqualTo("SIGNED_IN");
        assertThat(signIn.status().providerId()).isNotNull();

        // Good once.
        assertThat(signIn.complete("the-code", null)).isEqualTo(OpenRouterSignIn.Outcome.EXPIRED);
        assertThat(openRouter.requests("/api/v1/auth/keys")).hasSize(1);
    }

    @Test
    @DisplayName("signing in again replaces the key of the OpenRouter provider there, rather than making a second")
    void signsInAgain() {
        UUID id = UUID.randomUUID();
        existing.add(view(id, "My OpenRouter"));
        openRouter.on("POST", "/api/v1/auth/keys").json("{'key':'sk-or-v1-new'}");
        signIn.begin(REDIRECT);
        assertThat(signIn.complete("c", null)).isEqualTo(OpenRouterSignIn.Outcome.SIGNED_IN);
        ArgumentCaptor<ProviderInstanceService.ProviderInstanceInput> updated = ArgumentCaptor.forClass(ProviderInstanceService.ProviderInstanceInput.class);
        verify(providers).update(eq(id), updated.capture());
        assertThat(updated.getValue().apiKey()).isEqualTo("sk-or-v1-new");
        assertThat(updated.getValue().name()).as("its name kept").isEqualTo("My OpenRouter");
        verify(providers, never()).create(any());
        assertThat(signIn.status().providerId()).isEqualTo(id);
    }

    @Test
    @DisplayName("a declined, refused, late or unasked-for return keeps nothing; Enterprise Server never begins one")
    void refusals() {
        assertThat(signIn.complete("c", null)).as("nothing begun").isEqualTo(OpenRouterSignIn.Outcome.EXPIRED);

        signIn.begin(REDIRECT);
        assertThat(signIn.complete(null, "access_denied")).isEqualTo(OpenRouterSignIn.Outcome.DENIED);

        openRouter.on("POST", "/api/v1/auth/keys").status(403).json("{'error':'Invalid code_verifier'}");
        signIn.begin(REDIRECT);
        assertThat(signIn.complete("c", null)).isEqualTo(OpenRouterSignIn.Outcome.FAILED);
        assertThat(signIn.status().state()).isEqualTo("FAILED");

        signIn.begin(REDIRECT);
        now = now.plus(Duration.ofMinutes(11));
        assertThat(signIn.status().state()).isEqualTo("EXPIRED");
        assertThat(signIn.complete("c", null)).isEqualTo(OpenRouterSignIn.Outcome.EXPIRED);
        verify(providers, never()).create(any());

        assertThatThrownBy(() -> service(true).begin(REDIRECT)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the page it ends on says how it went in fixed words, and closes itself only on success")
    void page() {
        assertThat(OpenRouterSignInController.page(OpenRouterSignIn.Outcome.SIGNED_IN)).contains("Signed in to OpenRouter")
                .contains("window.close()");
        assertThat(OpenRouterSignInController.page(OpenRouterSignIn.Outcome.DENIED)).doesNotContain("<script");
        assertThat(new OpenRouterSignInController(signIn, "").redirectUri(URI.create("http://127.0.0.1:4246/api/v1/providers/openrouter/sign-in")))
                .isEqualTo(REDIRECT);
    }
}
