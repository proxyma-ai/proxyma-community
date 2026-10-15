package ai.proxyma.connectors.github;

import ai.proxyma.core.connector.spi.ApiAuth;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.RateLimitHint;
import ai.proxyma.core.connector.spi.ResolvedCredentials;

/**
 * How every request to GitHub is made: the connection's API address (github.com's
 * {@code https://api.github.com}, or a GitHub Enterprise Server's {@code https://HOST/api/v3}), its
 * token as a bearer on every request and on no other host, the REST API version this connector was
 * written against, and GitHub's own way of saying "slow down" - a 403 or 429 with
 * {@code x-ratelimit-remaining: 0} and a reset time, or a secondary limit's {@code Retry-After} -
 * waited out when it is soon and reported as rate limited when it is not.
 */
final class GitHubApi {

    static final String DEFAULT_BASE_URL = "https://api.github.com";
    static final String API_VERSION = "2022-11-28";

    private GitHubApi() {}

    static ApiClient client(ConnectorHttp http, ResolvedCredentials connection) {
        String base = connection.hasBaseUrl() ? connection.baseUrl() : DEFAULT_BASE_URL;
        return http.client(base)
                .service("GitHub")
                .auth(ApiAuth.bearer(connection))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", API_VERSION)
                .rateLimit(RateLimitHint.RESET_HEADERS)
                .build();
    }
}
