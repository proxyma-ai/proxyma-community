package ai.proxyma.connectors.asana;

import ai.proxyma.core.connector.spi.ApiAuth;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.Paginator;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How every request to Asana is made: its REST API ({@code https://app.asana.com/api/1.0}, or the
 * connection's address), and the connection's credential as a bearer on every request and on no
 * other host - a personal access token as it is, a sign-in's access token refreshed once when Asana
 * refuses it (they last an hour).
 *
 * <p>Asana's rate limit is a plain 429 with {@code Retry-After} (150 requests a minute on a free
 * domain, 1,500 on a paid one), which the framework waits out. Lists are paged by an {@code offset}
 * token, at most {@value #PAGE} to a page, and only when a {@code limit} is sent.
 */
final class AsanaApi {

    static final String DEFAULT_BASE_URL = "https://app.asana.com/api/1.0";
    static final int PAGE = 100;

    private AsanaApi() {}

    static ApiClient client(ConnectorHttp http, ResolvedCredentials connection) {
        String base = connection.hasBaseUrl() ? connection.baseUrl().strip() : DEFAULT_BASE_URL;
        return http.client(base.replaceAll("/+$", ""))
                .service("Asana")
                .auth(ApiAuth.bearer(connection))
                .build();
    }

    /** Every element of a paged list - each page's {@code data}, the next one named by {@code next_page.offset}. */
    static Iterator<JsonNode> paged(ApiClient api, String path, Map<String, ?> query) {
        Map<String, Object> q = new LinkedHashMap<>(query);
        q.put("limit", PAGE);
        return Paginator.cursor(api, path, q, "offset", page -> page.path("data"),
                page -> page.path("next_page").path("offset").asText(null));
    }
}
