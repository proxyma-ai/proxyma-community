package ai.proxyma.connectors.zoom;

import ai.proxyma.core.connector.spi.ApiAuth;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.AuthKind;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.RateLimitHint;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import ai.proxyma.core.common.net.RetryAfter;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;

/**
 * How every request to Zoom is made: the connection's API address ({@code https://api.zoom.us/v2},
 * or a test server's under More options) with a bearer on every request and on no other host. The
 * token depends on how the connection signs in:
 * <ul>
 *   <li>a <b>Server-to-Server OAuth app</b> (an account ID, a client ID and a secret): the token comes
 *       from {@link ZoomAccountToken}, asked for again when it nears its hour;</li>
 *   <li>a <b>sign-in</b> (an OAuth app of the operator's, authorization code with PKCE): the
 *       framework's token, refreshed once on a 401.</li>
 * </ul>
 *
 * <p><b>Rate limits</b> are per account and per category - Light, Medium, Heavy - per second, and
 * every endpoint read here is Light or Medium (30 and 20 a second on Pro, 80 and 60 on Business). A
 * per-second 429 is waited out ({@code Retry-After} when Zoom sends one, a short backoff when not); a
 * DAILY one ({@code X-RateLimit-Type: Daily-limit}) is longer than any pass should sit, so the
 * source is reported rate limited and the next sync tries again.
 */
final class ZoomApi {

    static final String DEFAULT_BASE_URL = "https://api.zoom.us/v2";
    static final String TOKEN_URL = "https://zoom.us/oauth/token";
    static final String AUTHORIZE_URL = "https://zoom.us/oauth/authorize";
    static final String MARKDOWN = "text/markdown";

    /** The connection's Server-to-Server settings and secret, by key. */
    static final String ACCOUNT_ID = "accountId";
    static final String CLIENT_ID = "clientId";
    static final String CLIENT_SECRET = "clientSecret";

    /** A daily limit spent waits longer than a pass may, so it is reported; a per-second one is waited out. */
    static final RateLimitHint RATE_LIMIT = ((RateLimitHint) (response, now) -> {
        if (response.status() != 429) return Optional.empty();
        String type = response.header("X-RateLimit-Type");
        if (type != null && type.toLowerCase(Locale.ROOT).contains("daily")) {
            Optional<Duration> asked = RetryAfter.parse(response.header("Retry-After"), now);
            return Optional.of(asked.filter(d -> d.compareTo(Duration.ofHours(1)) > 0).orElse(Duration.ofHours(1)));
        }
        return Optional.empty();
    }).or(RateLimitHint.STANDARD);

    private ZoomApi() {}

    /** The API client of a connection, its token from where the connection signs in. */
    static ApiClient client(ConnectorHttp http, ResolvedCredentials connection, String tokenUrl, Clock clock) {
        return http.client(baseUrl(connection)).service("Zoom").auth(auth(http, connection, tokenUrl, clock))
                .rateLimit(RATE_LIMIT).build();
    }

    /** The bearer of a connection: a Server-to-Server app's own token, else the framework's sign-in. */
    static ApiAuth auth(ConnectorHttp http, ResolvedCredentials connection, String tokenUrl, Clock clock) {
        if (connection.authKind() != AuthKind.KEY_PAIR) return ApiAuth.bearer(connection);
        ZoomAccountToken token = new ZoomAccountToken(http, connection, tokenUrl, clock);
        return ApiAuth.bearer(new ResolvedCredentials(connection.connectionId(), connection.protocol(), AuthKind.KEY_PAIR,
                connection.baseUrl(), null, java.util.Map.of(), java.util.Map.of(), token));
    }

    static String baseUrl(ResolvedCredentials connection) {
        String base = connection.hasBaseUrl() ? connection.baseUrl().strip() : DEFAULT_BASE_URL;
        return base.replaceAll("/+$", "");
    }

    /**
     * Whether a recording's {@code download_url} may be asked for WITH the connection's token: an
     * https address on Zoom's own domain ({@code zoom.us} or a subdomain such as {@code us06web.zoom.us}),
     * or the API's own origin when the connection points at a test server. The token goes nowhere else.
     */
    static boolean mayCarryToken(URI download, String apiBase) {
        if (download == null || download.getHost() == null) return false;
        String host = download.getHost().toLowerCase(Locale.ROOT);
        String scheme = download.getScheme() == null ? "" : download.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("https") && (host.equals("zoom.us") || host.endsWith(".zoom.us"))) return true;
        URI api = URI.create(apiBase);
        return scheme.equalsIgnoreCase(api.getScheme()) && host.equalsIgnoreCase(api.getHost())
                && port(download) == port(api);
    }

    private static int port(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /**
     * A meeting UUID as one path segment. Zoom asks for a UUID that begins with "/" or holds "//" to
     * be encoded TWICE (its server decodes once before routing); every other UUID is encoded once.
     */
    static String uuidSegment(String uuid) {
        String once = ApiClient.segment(uuid);
        return uuid.startsWith("/") || uuid.contains("//") ? ApiClient.segment(once) : once;
    }

    static Instant instant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso);
        } catch (RuntimeException e) {
            try {
                return OffsetDateTime.parse(iso).toInstant();
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }

    static InputStream markdown(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
