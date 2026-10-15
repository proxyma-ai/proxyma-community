package ai.proxyma.connectors.monday;

import ai.proxyma.core.connector.spi.ApiAuth;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ApiResponse;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.ConnectorHttpException;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * How every request to monday.com is made: one GraphQL endpoint ({@code https://api.monday.com/v2},
 * or the connection's address with {@code /v2} after it), the API version this connector was written
 * against in the {@code API-Version} header, and the connection's credential on every request and on
 * no other host - a personal API token as the bare {@code Authorization} value, as monday.com asks,
 * or a sign-in's access token as a bearer.
 *
 * <p><b>Limits.</b> monday.com answers a spent budget - complexity points per minute, requests per
 * minute, concurrency, requests per IP - with a 429 and {@code Retry-After}, which the framework waits
 * out. Its DAILY call limit (1,000 calls a day on the lower plans) is a 429 too, but waiting a minute
 * cannot cure it, so it gets a sentence of its own. An older answer of the same kind - a 200 whose
 * only content is a complexity or rate-limit error - reads as the rate limit it is.
 */
final class MondayApi {

    static final String DEFAULT_BASE_URL = "https://api.monday.com";
    static final String GRAPHQL = "v2";
    // Pinned, as monday.com asks: a dated version, current from 2026-07-01 and supported after.
    static final String API_VERSION = "2026-07";

    private static final Set<String> RATE_LIMITED = Set.of("ComplexityException", "COMPLEXITY_BUDGET_EXHAUSTED",
            "RATE_LIMIT_EXCEEDED", "Rate Limit Exceeded", "maxConcurrencyExceeded", "IP_RATE_LIMIT_EXCEEDED");
    private static final String DAILY = "DAILY_LIMIT_EXCEEDED";
    private static final Set<String> UNAUTHORIZED = Set.of("Unauthorized", "UserUnauthorizedException", "USER_UNAUTHORIZED");

    private MondayApi() {}

    static ApiClient client(ConnectorHttp http, ResolvedCredentials connection) {
        String base = connection.hasBaseUrl() ? connection.baseUrl().strip() : DEFAULT_BASE_URL;
        base = base.replaceAll("/+$", "").replaceFirst("(?i)/v2$", "");
        ApiAuth auth = connection.signsIn()
                ? ApiAuth.bearer(connection)
                : ApiAuth.header(HttpHeaders.AUTHORIZATION, connection::accessToken);
        return http.client(base)
                .service("monday.com")
                .auth(auth)
                .header("API-Version", API_VERSION)
                .build();
    }

    /**
     * One GraphQL query, its {@code data} back. A failed answer throws the framework's sentence for
     * its status; a GraphQL error that left no data throws monday.com's own message, except those
     * that mean a limit or a refused credential, which read as the 429 or the 401 they are.
     */
    static JsonNode query(ApiClient api, String operation, String query, Map<String, ?> variables) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operationName", operation);
        body.put("query", query);
        body.put("variables", variables == null ? Map.of() : variables);
        ApiResponse response = api.send(HttpMethod.POST, GRAPHQL, Map.of(), body, Map.of());
        JsonNode json = parse(response);
        String code = code(json);
        String path = response.uri() == null ? null : response.uri().getRawPath();
        if (DAILY.equals(code)) {
            throw new ConnectorHttpException("monday.com's daily limit of API calls for this account is used up. "
                    + "The sync continues after midnight UTC.", 429, path);
        }
        if (RATE_LIMITED.contains(code)) throw api.failure(withStatus(response, 429));
        if (UNAUTHORIZED.contains(code) && response.ok()) throw api.failure(withStatus(response, 401));
        if (!response.ok()) throw api.failure(response);
        JsonNode data = json == null ? null : json.path("data");
        if (data == null || data.isMissingNode() || data.isNull()) {
            String message = message(json);
            throw new ConnectorHttpException("monday.com refused the query: " + (message.isBlank() ? "no data" : message),
                    response.status(), path);
        }
        return data;
    }

    // The first error's code: extensions.code in today's answers, error_code in older ones.
    private static String code(JsonNode json) {
        if (json == null) return "";
        String code = json.path("errors").path(0).path("extensions").path("code").asText("");
        return code.isEmpty() ? json.path("error_code").asText("") : code;
    }

    private static String message(JsonNode json) {
        if (json == null) return "";
        String message = json.path("errors").path(0).path("message").asText("");
        return message.isEmpty() ? json.path("error_message").asText("") : message;
    }

    private static JsonNode parse(ApiResponse response) {
        try {
            return response.json();
        } catch (ConnectorHttpException e) {
            return null;
        }
    }

    private static ApiResponse withStatus(ApiResponse response, int status) {
        return new ApiResponse(response.uri(), status, response.headers(), response.bytes());
    }
}
