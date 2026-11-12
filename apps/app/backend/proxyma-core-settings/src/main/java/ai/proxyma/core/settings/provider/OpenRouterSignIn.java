package ai.proxyma.core.settings.provider;

import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ApiResponse;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SIGN IN WITH OPENROUTER (2026-11-07, the maintainer: most people "don't know or don't want to setup ... LLM API"). The
 * person signs in to OpenRouter in their own browser and allows Proxyma; OpenRouter hands back a code, which this
 * exchanges for an API key of the person's own account, kept encrypted as the OpenRouter provider - one made, or the one
 * there signed in again. Nobody copies a key.
 *
 * <p>OpenRouter's flow is PKCE with no {@code state}: its authorization page takes a callback address and a code
 * challenge, and sends the browser back with {@code ?code=}. The code is bound to that challenge, so only the verifier
 * kept HERE exchanges it - a code from anybody else's flow is refused by OpenRouter - and the verifier never leaves the
 * server. One sign-in is pending at a time (a new one replaces it), good once, for {@link #LIFETIME}.
 *
 * <p>Desktop and Home Server only, as a connection's browser sign-in (OAuth2ConnectionService.begin): only the gated
 * window starts one, and the callback is answered on the code alone because the system browser shares no cookie with the
 * app. Enterprise Server's providers are a Super admin's, set up by hand.
 */
@Service
public class OpenRouterSignIn {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterSignIn.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Where OpenRouter sends the browser back; open on the single-user editions (AccessGate). */
    public static final String CALLBACK_PATH = "/api/v1/providers/openrouter/callback";
    static final Duration LIFETIME = Duration.ofMinutes(10);
    /** The provider's name when one is made. */
    static final String NAME = "OpenRouter";

    /** How a sign-in ended. */
    public enum Outcome { SIGNED_IN, DENIED, EXPIRED, FAILED }

    /** Where the last sign-in stands: none started, waiting for the browser, or how it ended and the provider it made. */
    public record Status(String state, UUID providerId, Instant at) {}

    private record Flow(String verifier, String redirectUri, Instant expires, Instant started) {}

    private final ProviderInstanceService providers;
    private final ConnectorHttp http;
    private final boolean multiUser;
    private final String authorizeUrl;
    private final String keysUrl;
    private final Clock clock;
    private final AtomicReference<Flow> pending = new AtomicReference<>();
    private volatile Status last = new Status("NONE", null, null);

    @Autowired
    public OpenRouterSignIn(ProviderInstanceService providers, ConnectorHttp http,
                            @Value("${proxyma.multi-user:false}") boolean multiUser,
                            @Value("${proxyma.providers.openrouter.authorize-url:https://openrouter.ai/auth}") String authorizeUrl,
                            @Value("${proxyma.providers.openrouter.keys-url:https://openrouter.ai/api/v1/auth/keys}") String keysUrl) {
        this(providers, http, multiUser, authorizeUrl, keysUrl, Clock.systemUTC());
    }

    OpenRouterSignIn(ProviderInstanceService providers, ConnectorHttp http, boolean multiUser, String authorizeUrl,
                     String keysUrl, Clock clock) {
        this.providers = providers;
        this.http = http;
        this.multiUser = multiUser;
        this.authorizeUrl = authorizeUrl;
        this.keysUrl = keysUrl;
        this.clock = clock;
    }

    /**
     * A new sign-in, replacing one still pending: the address the person's browser opens.
     *
     * @throws IllegalStateException on Enterprise Server, which does not offer it
     */
    public String begin(String redirectUri) {
        if (multiUser) throw new IllegalStateException("Sign in with OpenRouter is for Proxyma Desktop and Home Server.");
        String verifier = randomValue();
        Instant now = clock.instant();
        pending.set(new Flow(verifier, redirectUri, now.plus(LIFETIME), now));
        last = new Status("PENDING", null, now);
        return authorizeUrl + "?callback_url=" + encode(redirectUri) + "&code_challenge=" + challenge(verifier)
                + "&code_challenge_method=S256";
    }

    /** The callback: the code exchanged for the person's key, kept as the OpenRouter provider. Never throws. */
    public Outcome complete(String code, String error) {
        Flow flow = pending.getAndSet(null);
        Instant now = clock.instant();
        if (flow == null || now.isAfter(flow.expires())) return end(Outcome.EXPIRED, null);
        if (error != null && !error.isBlank()) return end(Outcome.DENIED, null);
        if (code == null || code.isBlank() || code.length() > 2048) return end(Outcome.FAILED, null);
        try {
            String key = exchange(code.strip(), flow.verifier());
            if (key == null) return end(Outcome.FAILED, null);
            UUID id = keep(key);
            log.info("[Providers] signed in with OpenRouter; provider {} holds the key", id);
            return end(Outcome.SIGNED_IN, id);
        } catch (RuntimeException e) {
            log.warn("[Providers] the OpenRouter sign-in could not be completed: {}", e.getMessage());
            return end(Outcome.FAILED, null);
        }
    }

    /** Where the last sign-in stands, for the page waiting on it. */
    public Status status() {
        Flow flow = pending.get();
        if (flow != null && clock.instant().isAfter(flow.expires())) {
            pending.compareAndSet(flow, null);
            last = new Status(Outcome.EXPIRED.name(), null, clock.instant());
        }
        return last;
    }

    private Outcome end(Outcome outcome, UUID providerId) {
        last = new Status(outcome.name(), providerId, clock.instant());
        return outcome;
    }

    // POST {code, code_verifier, code_challenge_method}: OpenRouter answers {"key": "sk-or-..."}.
    private String exchange(String code, String verifier) {
        URI uri = URI.create(keysUrl);
        ApiClient client = http.client(uri.getScheme() + "://" + uri.getRawAuthority()).service("OpenRouter").maxAttempts(2).build();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("code_verifier", verifier);
        body.put("code_challenge_method", "S256");
        ApiResponse response = client.send(HttpMethod.POST, keysUrl, Map.of(), body, Map.of());
        JsonNode json = client.requireOk(response).json();
        String key = json.path("key").asText("");
        return key.isBlank() ? null : key;
    }

    // The OpenRouter provider there signed in again with the new key, else a new one.
    private UUID keep(String key) {
        for (ProviderInstanceView v : providers.list()) {
            if (v.protocol() == ProviderProtocol.OPENROUTER) {
                providers.update(v.id(), new ProviderInstanceService.ProviderInstanceInput(v.name(), ProviderProtocol.OPENROUTER,
                        null, key, false, null, null));
                return v.id();
            }
        }
        String name = NAME;
        for (int n = 2; nameTaken(name); n++) name = NAME + " " + n;
        return providers.create(new ProviderInstanceService.ProviderInstanceInput(name, ProviderProtocol.OPENROUTER,
                null, key, false, null, null)).id();
    }

    private boolean nameTaken(String name) {
        return providers.list().stream().anyMatch(v -> name.equalsIgnoreCase(v.name()));
    }

    static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String randomValue() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
