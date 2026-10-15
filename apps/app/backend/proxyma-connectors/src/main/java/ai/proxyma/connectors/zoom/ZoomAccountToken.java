package ai.proxyma.connectors.zoom;

import ai.proxyma.core.connector.spi.ApiAuth;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ApiResponse;
import ai.proxyma.core.connector.spi.ConnectorAuthException;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.ConnectorHttpException;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import ai.proxyma.core.connector.spi.TokenSupplier;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A Zoom Server-to-Server OAuth app's token - the account-level way in, with no browser and no
 * refresh token: {@code POST https://zoom.us/oauth/token} with {@code grant_type=account_credentials}
 * and the app's account ID in the form, the client ID and secret as HTTP Basic. Zoom answers a token
 * good for an hour ({@code expires_in}); a new request IS the refresh.
 *
 * <p>Not the framework's client-credentials grant, which sends {@code grant_type=client_credentials}
 * and no account ID - Zoom refuses that - so the connection signs in with the KEY_PAIR method and
 * this connector asks for its own token, as Salesforce's JWT flow does.
 *
 * <p>Kept in memory per connection and key until a minute before it expires, shared by the sessions
 * of one connection (a fetch batch opens a session of its own and should not cost a token each), and
 * forgotten once when Zoom refuses it. The secret goes to the token address and nowhere else; that
 * address is the operator's ({@code proxyma.connectors.zoom.token-url}), never one a person types.
 */
final class ZoomAccountToken implements TokenSupplier {

    static final Duration MARGIN = Duration.ofSeconds(60);
    private static final Map<String, Grant> GRANTS = new ConcurrentHashMap<>();

    record Grant(String accessToken, Instant expires) {}

    private final ConnectorHttp http;
    private final String tokenUrl;
    private final String accountId;
    private final String clientId;
    private final String clientSecret;
    private final String cacheKey;
    private final Clock clock;

    ZoomAccountToken(ConnectorHttp http, ResolvedCredentials connection, String tokenUrl, Clock clock) {
        this.http = http;
        this.tokenUrl = tokenUrl == null || tokenUrl.isBlank() ? ZoomApi.TOKEN_URL : tokenUrl.strip();
        this.accountId = trim(connection.setting(ZoomApi.ACCOUNT_ID));
        this.clientId = trim(connection.setting(ZoomApi.CLIENT_ID));
        String secret = connection.secret(ZoomApi.CLIENT_SECRET);
        this.clientSecret = trim(secret == null ? connection.soleSecret() : secret);
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.cacheKey = sha256(this.tokenUrl + "\n" + accountId + "\n" + clientId + "\n" + clientSecret);
    }

    @Override
    public String accessToken() {
        Grant g = GRANTS.get(cacheKey);
        if (g != null && clock.instant().isBefore(g.expires())) return g.accessToken();
        synchronized (GRANTS) {
            g = GRANTS.get(cacheKey);
            if (g != null && clock.instant().isBefore(g.expires())) return g.accessToken();
            g = exchange();
            GRANTS.put(cacheKey, g);
            return g.accessToken();
        }
    }

    @Override
    public void invalidate() {
        GRANTS.remove(cacheKey);
    }

    private Grant exchange() {
        if (accountId == null) {
            throw new ConnectorAuthException("This connection has no Zoom account ID. Add the app's in Settings > Connections.");
        }
        if (clientId == null || clientSecret == null) {
            throw new ConnectorAuthException("This connection has no client ID or client secret. Add the app's in Settings > Connections.");
        }
        URI uri = URI.create(tokenUrl);
        ApiClient login = http.client(uri.getScheme() + "://" + uri.getRawAuthority()).service("Zoom")
                .auth(ApiAuth.basic(clientId, clientSecret)).maxAttempts(2).build();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "account_credentials");
        form.put("account_id", accountId);
        ApiResponse response = login.postForm(tokenUrl, form);
        JsonNode body = parse(response);
        if (!response.ok()) {
            if (response.status() == 400 || response.status() == 401) {
                String reason = body == null ? "" : body.path("reason").asText(body.path("error").asText(""));
                throw new ConnectorAuthException("Zoom refused the Server-to-Server sign-in ("
                        + (reason.isBlank() ? String.valueOf(response.status()) : reason)
                        + "). Check the account ID, the client ID and the client secret, and that the app is activated.");
            }
            throw login.failure(response);
        }
        String token = body == null ? null : body.path("access_token").asText(null);
        if (token == null || token.isBlank()) throw new ConnectorAuthException("Zoom answered the sign-in without a token.");
        long seconds = body.path("expires_in").asLong(3600);
        Instant expires = clock.instant().plusSeconds(Math.max(0, seconds)).minus(MARGIN);
        return new Grant(token, expires);
    }

    private static JsonNode parse(ApiResponse response) {
        try {
            return response.json();
        } catch (ConnectorHttpException e) {
            return null;
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** Forgets every token - for tests. */
    static void forgetAll() {
        GRANTS.clear();
    }
}
