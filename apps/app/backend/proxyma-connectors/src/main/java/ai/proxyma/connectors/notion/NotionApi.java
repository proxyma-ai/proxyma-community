package ai.proxyma.connectors.notion;

import ai.proxyma.core.common.net.RetryAfter;
import ai.proxyma.core.connector.spi.ApiAuth;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ApiResponse;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.Paginator;
import ai.proxyma.core.connector.spi.RateLimitHint;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpMethod;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * How every request to Notion is made: the connection's API address ({@code https://api.notion.com/v1},
 * or a test's loopback server), its token as a bearer on every request and on no other host, and the
 * {@code Notion-Version} this connector was written against.
 *
 * <p><b>The version is pinned, and it matters.</b> 2025-09-03 moved a database's rows under its
 * <i>data sources</i> (a database may hold several, and rows are queried through
 * {@code /data_sources/{id}/query}); 2026-03-11 dropped the {@code archived} flag for
 * {@code in_trash} and added the page-as-markdown endpoint this connector reads pages with. A
 * request without the header is answered under an older version, in shapes this connector does not
 * read.
 *
 * <p><b>Rate limits</b>: 180 requests a minute per connection on most plans (600 on Business and
 * Enterprise), and a per-workspace budget on top. Requests are paced to the lower figure, so a pass
 * does not run into the limit to begin with; a 429 that comes anyway carries an integer
 * {@code Retry-After} (at most 60 seconds for the connection's own limit), which the standard hint
 * waits out. Notion's own 529 {@code service_overload} ("temporarily overloaded, respect
 * Retry-After") is retried the same way ({@link #RATE_LIMIT}); a 429 whose code is
 * {@code public_api_request_blocked} is a block on the integration rather than a budget, so it is
 * not asked again in this pass but reported as the limit it is.
 */
final class NotionApi {

    static final String DEFAULT_BASE_URL = "https://api.notion.com/v1";
    static final String VERSION = "2026-03-11";
    // 180 a minute is one request every 333 ms; a little slower, for the workspace's own budget.
    static final Duration PACE = Duration.ofMillis(350);
    // The most a list endpoint answers with at once.
    static final int PAGE = 100;
    static final int SERVICE_OVERLOAD = 529;
    static final String BLOCKED = "public_api_request_blocked";

    /** A 529 waited out like a 429; a blocked integration not waited for at all. */
    static final RateLimitHint RATE_LIMIT = ((RateLimitHint) (response, now) -> {
        if (response.status() == SERVICE_OVERLOAD) {
            return Optional.of(RetryAfter.parse(response.header("Retry-After"), now).orElse(Duration.ZERO));
        }
        if (response.status() == 429 && BLOCKED.equals(code(response))) return Optional.of(RateLimitHint.NEXT_PASS);
        return Optional.empty();
    }).or(RateLimitHint.STANDARD);

    private NotionApi() {}

    static ApiClient client(ConnectorHttp http, ResolvedCredentials connection) {
        String base = connection.hasBaseUrl() ? connection.baseUrl() : DEFAULT_BASE_URL;
        return http.client(base)
                .service("Notion")
                .auth(ApiAuth.bearer(connection))
                .header("Notion-Version", VERSION)
                .minInterval(PACE)
                .rateLimit(RATE_LIMIT)
                .retryStatus(SERVICE_OVERLOAD)
                .build();
    }

    // The error's code - Notion's answers name it in "code" beside the message.
    private static String code(ApiResponse response) {
        byte[] body = response.bytes();
        if (body.length == 0 || !new String(body, StandardCharsets.UTF_8).contains("\"code\"")) return "";
        try {
            return response.json().path("code").asText("");
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** A POST with a JSON body, its answer as JSON; anything but 2xx throws the sentence for its status. */
    static JsonNode post(ApiClient api, String path, Map<String, ?> body) {
        return api.requireOk(api.send(HttpMethod.POST, path, Map.of(), body, Map.of())).json();
    }

    /**
     * Every result of a POST list endpoint ({@code /search}, {@code /data_sources/{id}/query}),
     * fetched a page at a time: Notion's cursor is {@code start_cursor} in the body, and
     * {@code next_cursor} in the answer while {@code has_more}. {@code onPage} sees each page whole,
     * for its {@code request_status}.
     */
    static Iterator<JsonNode> postPages(ApiClient api, String path, Map<String, ?> body,
                                        java.util.function.Consumer<JsonNode> onPage) {
        return Paginator.iterate(next -> {
            Map<String, Object> b = new LinkedHashMap<>(body);
            b.put("page_size", PAGE);
            if (next != null) b.put("start_cursor", next);
            JsonNode page = post(api, path, b);
            if (onPage != null) onPage.accept(page);
            return new Paginator.Page<>(Paginator.elements(page.path("results")), nextCursor(page));
        });
    }

    /** Every result of a GET list endpoint ({@code /blocks/{id}/children}), paged the same way. */
    static Iterator<JsonNode> getPages(ApiClient api, String path) {
        return Paginator.iterate(next -> {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("page_size", PAGE);
            if (next != null) q.put("start_cursor", next);
            JsonNode page = api.getJson(path, q);
            return new Paginator.Page<>(Paginator.elements(page.path("results")), nextCursor(page));
        });
    }

    private static String nextCursor(JsonNode page) {
        if (!page.path("has_more").asBoolean(false)) return null;
        String next = page.path("next_cursor").asText("");
        return next.isBlank() ? null : next;
    }
}
