package ai.proxyma.connectors.gitlab;

import ai.proxyma.core.connector.spi.ApiAuth;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.RateLimitHint;
import ai.proxyma.core.connector.spi.ResolvedCredentials;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * How every request to GitLab is made: the connection's GitLab address (gitlab.com, or a
 * self-managed instance at {@code https://HOST[/subpath]}) with {@code /api/v4} after it, and the
 * connection's credential on every request and on no other host - an access token (personal, group
 * or project) in GitLab's own {@code PRIVATE-TOKEN} header, or a sign-in's access token as a bearer,
 * refreshed once when GitLab refuses it.
 *
 * <p>GitLab says "slow down" with a 429, a {@code Retry-After} and its {@code RateLimit-Reset} (epoch
 * seconds); both are waited out when they are soon and reported as rate limited when they are not.
 */
final class GitLabApi {

    static final String DEFAULT_BASE_URL = "https://gitlab.com";
    static final String API_PATH = "/api/v4";
    static final String TOKEN_HEADER = "PRIVATE-TOKEN";

    /** A 429 that names when the window resets - {@code Retry-After} first, else {@code RateLimit-Reset}. */
    static final RateLimitHint RATE_LIMIT = (response, now) -> {
        if (response.status() != 429) return Optional.empty();
        if (response.header("Retry-After") != null) return RateLimitHint.STANDARD.waitFor(response, now);
        String reset = response.header("RateLimit-Reset");
        if (reset == null) return Optional.of(Duration.ZERO);
        try {
            Duration wait = Duration.between(now, Instant.ofEpochSecond(Long.parseLong(reset.trim())));
            return Optional.of(wait.isNegative() ? Duration.ZERO : wait.plusSeconds(1));
        } catch (NumberFormatException e) {
            return Optional.of(Duration.ZERO);
        }
    };

    private GitLabApi() {}

    static ApiClient client(ConnectorHttp http, ResolvedCredentials connection) {
        String base = apiBase(connection.hasBaseUrl() ? connection.baseUrl() : DEFAULT_BASE_URL);
        ApiAuth auth = connection.signsIn()
                ? ApiAuth.bearer(connection)
                : ApiAuth.header(TOKEN_HEADER, connection::accessToken);
        return http.client(base)
                .service("GitLab")
                .auth(auth)
                .rateLimit(RATE_LIMIT)
                .build();
    }

    /**
     * The REST API's address for a GitLab address as a person types it: {@code https://gitlab.com}
     * and {@code https://gitlab.com/api/v4} are the same API, and so are a self-managed instance's
     * {@code https://host/gitlab} and {@code https://host/gitlab/api/v4}.
     */
    static String apiBase(String address) {
        String base = webBase(address);
        return base + API_PATH;
    }

    /** The instance's web address - where projects and groups are pages - for any form of it. */
    static String webBase(String address) {
        String base = address == null || address.isBlank() ? DEFAULT_BASE_URL : address.strip();
        base = base.replaceAll("/+$", "");
        if (base.toLowerCase(Locale.ROOT).endsWith(API_PATH)) base = base.substring(0, base.length() - API_PATH.length());
        return base.replaceAll("/+$", "");
    }
}
