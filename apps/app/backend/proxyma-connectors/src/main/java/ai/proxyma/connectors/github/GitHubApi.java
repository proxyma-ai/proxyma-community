package ai.proxyma.connectors.github;

import ai.proxyma.core.connector.spi.ApiAuth;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.RateLimitHint;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import ai.proxyma.core.connector.spi.ServiceAddress;

import java.util.Optional;

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
    static final String ENTERPRISE_API_PATH = "/api/v3";

    private GitHubApi() {}

    static ApiClient client(ConnectorHttp http, ResolvedCredentials connection) {
        return http.client(apiBase(connection.hasBaseUrl() ? connection.baseUrl() : null))
                .service("GitHub")
                .auth(ApiAuth.bearer(connection))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", API_VERSION)
                .rateLimit(RateLimitHint.RESET_HEADERS)
                .build();
    }

    /**
     * The REST API's address for whatever was typed as the API URL - often a page copied from the
     * browser, whose path joined to every call answers with the web app:
     * <ul>
     *   <li>github.com, any page of it, and api.github.com are {@code https://api.github.com};</li>
     *   <li>GitHub Enterprise Cloud with data residency ({@code SUBDOMAIN.ghe.com}) has its API at
     *       {@code api.SUBDOMAIN.ghe.com}, with no path;</li>
     *   <li>any other host is a GitHub Enterprise Server, always at its host's root, whose API is
     *       {@code /api/v3}: kept where it was typed, and put after the host when it was not (a repository
     *       or organization page pasted instead).</li>
     * </ul>
     */
    static String apiBase(String address) {
        if (address == null || address.isBlank()) return DEFAULT_BASE_URL;
        String host = ServiceAddress.host(address);
        if (host.isEmpty()) return address.strip(); // not a URL: the client refuses it in words
        if (host.equals("github.com") || host.equals("www.github.com") || host.equals("api.github.com")) {
            return DEFAULT_BASE_URL;
        }
        if (host.endsWith(".ghe.com")) {
            return "https://" + (host.startsWith("api.") ? host : "api." + host);
        }
        Optional<String> typed = ServiceAddress.before(address, ENTERPRISE_API_PATH);
        return typed.orElseGet(() -> ServiceAddress.origin(address)) + ENTERPRISE_API_PATH;
    }
}
