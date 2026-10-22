package ai.proxyma.connectors.gitlab;

import ai.proxyma.core.connector.spi.ApiAuth;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.RateLimitHint;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import ai.proxyma.core.connector.spi.ServiceAddress;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

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
    // GitLab's own pages at the top level - names no group or user can take - so a path starting
    // with one is a page of the instance at the host's root.
    private static final Set<String> RESERVED_TOP_LEVEL = Set.of("dashboard", "explore", "users", "groups", "admin",
            "profile", "help", "projects", "search", "-");

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
        return client(http, connection, connection.hasBaseUrl() ? connection.baseUrl() : DEFAULT_BASE_URL);
    }

    /** The connection's credential on another address of the same instance - for the Test's search. */
    static ApiClient client(ConnectorHttp http, ResolvedCredentials connection, String address) {
        String base = apiBase(address);
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

    /**
     * The instance's web address - where projects and groups are pages - for any form of it, a page
     * copied from the browser included. gitlab.com is always its root. A self-managed GitLab may live
     * under a path of its own ({@code https://host/gitlab}), so only what GitLab itself puts in a path
     * is cut: {@code /api/v4}, the {@code /-/} that starts every project and group sub-page, and the
     * reserved top-level pages ({@code /dashboard}, {@code /explore} ...) as the first segment. A bare
     * project or group page ({@code https://host/group/project}) cannot be told from such a path by its
     * text; the connection's Test finds the instance for that one (GitLabConnectionPlugin).
     */
    static String webBase(String address) {
        String base = address == null || address.isBlank() ? DEFAULT_BASE_URL : address.strip();
        String host = ServiceAddress.host(base);
        if (host.isEmpty()) return base.replaceAll("/+$", ""); // not a URL: the client refuses it in words
        if (host.equals("gitlab.com") || host.equals("www.gitlab.com")) return DEFAULT_BASE_URL;
        List<String> segments = ServiceAddress.segments(base);
        if (!segments.isEmpty() && RESERVED_TOP_LEVEL.contains(segments.get(0).toLowerCase(Locale.ROOT))) {
            return ServiceAddress.origin(base);
        }
        return ServiceAddress.cutAt(base, API_PATH, "-");
    }
}
