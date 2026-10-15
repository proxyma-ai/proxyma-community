package ai.proxyma.connectors.dropbox;

import ai.proxyma.core.connector.spi.ApiAuth;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ApiResponse;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.ConnectorHttpException;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpMethod;

import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * How every request to Dropbox is made: the connection's API address ({@code https://api.dropboxapi.com},
 * or a test server under More options), the sign-in's access token as a bearer, and the two shapes
 * Dropbox's endpoints come in.
 *
 * <ul>
 *   <li><b>RPC</b> ({@code /2/files/list_folder}, {@code /2/users/get_current_account}): a POST with the
 *       arguments as a JSON body, the answer as JSON. An endpoint's own refusal is a 409 whose
 *       {@code error_summary} names it ({@code path/not_found/..}, {@code reset/..}) - matched by
 *       prefix, as Dropbox asks.
 *   <li><b>Content download</b> ({@code /2/files/download}, {@code /2/files/export}) on
 *       {@code content.dropboxapi.com}: the arguments as JSON in the {@code Dropbox-API-Arg} header,
 *       which has to be ASCII - every other character written as a {@code \\uXXXX} escape - and the
 *       file as the body. Asked with GET, which Dropbox accepts for these endpoints, so the file is
 *       streamed rather than held. Against a test server the content host is the API's own.
 * </ul>
 *
 * <p><b>Team space.</b> A Dropbox team with a team space roots a member's account at the team's
 * namespace, while the API roots every path at the member's own folder unless told otherwise - so the
 * team's folders would be invisible. The account's {@code root_info} is read once per session, and
 * where its root differs from its home every request carries {@code Dropbox-API-Path-Root} with the
 * root, so paths read as they do in Dropbox itself ("/Marketing", "/Ana Lee/Drafts"). A 422
 * {@code invalid_root} - the team moved to a team space since - is answered once with the root it
 * names.
 *
 * <p><b>Rate limits</b>: Dropbox does not publish its per-user limits; a 429 carries
 * {@code Retry-After} in seconds, which the standard hint waits out.
 */
final class DropboxApi {

    static final String DEFAULT_BASE_URL = "https://api.dropboxapi.com";
    static final String API_HOST = "api.dropboxapi.com";
    static final String CONTENT_BASE_URL = "https://content.dropboxapi.com";
    static final String PATH_ROOT = "Dropbox-API-Path-Root";
    static final String ARG = "Dropbox-API-Arg";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ApiClient api;
    private final String contentBase;
    private String pathRoot;
    private boolean rootRead;

    DropboxApi(ConnectorHttp http, ResolvedCredentials connection) {
        String base = connection.hasBaseUrl() ? connection.baseUrl().strip() : DEFAULT_BASE_URL;
        boolean dropbox = API_HOST.equalsIgnoreCase(host(base));
        ApiClient.Builder builder = http.client(base).service("Dropbox").auth(ApiAuth.bearer(connection));
        // The content endpoints live on a host of their own, which the credential may go to as well.
        if (dropbox) builder.allowHost(URI.create(CONTENT_BASE_URL).getHost());
        this.api = builder.build();
        this.contentBase = dropbox ? CONTENT_BASE_URL : base.replaceAll("/+$", "");
    }

    ApiClient client() {
        return api;
    }

    /** The account the connection reads as, which also says where its paths are rooted. */
    JsonNode account() {
        JsonNode account = call("2/users/get_current_account", null);
        JsonNode root = account.path("root_info");
        String rootId = root.path("root_namespace_id").asText("");
        String homeId = root.path("home_namespace_id").asText("");
        pathRoot = !rootId.isBlank() && !rootId.equals(homeId) ? rootHeader(rootId) : null;
        rootRead = true;
        return account;
    }

    /**
     * An RPC call; its answer as JSON. A 409 is a {@link Refusal} carrying Dropbox's
     * {@code error_summary}; anything else that is not 2xx is the framework's sentence for its status.
     */
    JsonNode rpc(String path, Map<String, ?> body) {
        if (!rootRead) account();
        return call(path, body);
    }

    private JsonNode call(String path, Map<String, ?> body) {
        ApiResponse response = api.send(HttpMethod.POST, path, Map.of(), body, headers());
        if (response.status() == 422 && retryWithRoot(response)) {
            response = api.send(HttpMethod.POST, path, Map.of(), body, headers());
        }
        if (response.status() == 409) throw refusal(response);
        if (response.status() == 401) {
            // A token without a permission the endpoint needs is a 401 too, and no new sign-in
            // cures it: the app has to be given the permission first.
            String scope = missingScope(response);
            if (scope != null) {
                throw new ConnectorHttpException("The Dropbox app does not have the " + scope + " permission. Turn it on "
                        + "on the app's Permissions tab in the Dropbox App Console, then sign in again.", 403,
                        response.uri() == null ? null : response.uri().getRawPath());
            }
        }
        return api.requireOk(response).json();
    }

    private static String missingScope(ApiResponse response) {
        try {
            JsonNode error = response.json().path("error");
            if (!"missing_scope".equals(error.path(".tag").asText())) return null;
            String scope = error.path("required_scope").asText("");
            return scope.isBlank() ? null : scope;
        } catch (ConnectorHttpException e) {
            return null;
        }
    }

    /** A content-download endpoint's body, streamed - the caller closes it. */
    InputStream download(String path, Map<String, ?> arg) {
        if (!rootRead) account();
        Map<String, String> headers = new LinkedHashMap<>(headers());
        headers.put(ARG, headerJson(arg));
        headers.put("Accept", "*/*");
        // A 409 here is the endpoint's own refusal, its summary unread (the stream's body is not
        // kept); the caller asked get_metadata first, so it is rare and the next pass asks again.
        return api.stream(contentBase + "/" + path, Map.of(), headers);
    }

    private Map<String, String> headers() {
        return pathRoot == null ? Map.of() : Map.of(PATH_ROOT, pathRoot);
    }

    // A 422 invalid_root names the account's root as it is now: use it, once.
    private boolean retryWithRoot(ApiResponse response) {
        try {
            JsonNode error = response.json().path("error");
            if (!"invalid_root".equals(error.path(".tag").asText())) return false;
            String rootId = error.path("invalid_root").path("root_namespace_id").asText("");
            if (rootId.isBlank()) return false;
            pathRoot = rootHeader(rootId);
            return true;
        } catch (ConnectorHttpException e) {
            return false;
        }
    }

    private Refusal refusal(ApiResponse response) {
        String summary = "";
        try {
            summary = response.json().path("error_summary").asText("");
        } catch (ConnectorHttpException ignored) {
            // not JSON: no summary to match
        }
        return new Refusal(api.failure(response).getMessage(), summary,
                response.uri() == null ? null : response.uri().getRawPath());
    }

    /**
     * An endpoint's own refusal (409), with Dropbox's {@code error_summary} - its tags joined by
     * slashes, matched by prefix.
     */
    static final class Refusal extends ConnectorHttpException {
        private final String summary;

        Refusal(String message, String summary, String path) {
            super(message, 409, path);
            this.summary = summary == null ? "" : summary;
        }

        boolean is(String prefix) {
            return summary.startsWith(prefix);
        }

        /** No file or folder at the path asked about, whichever endpoint said so. */
        boolean notFound() {
            return summary.startsWith("path/not_found") || summary.startsWith("path_lookup/not_found");
        }
    }

    // -- values --------------------------------------------------------------------------------

    private static String rootHeader(String namespaceId) {
        return headerJson(Map.of(".tag", "root", "root", namespaceId));
    }

    /**
     * JSON safe for an HTTP header, as Dropbox requires of {@code Dropbox-API-Arg}: every character
     * from 0x7F up as a JSON {@code \\uXXXX} escape, so a file named in Vietnamese reaches Dropbox as
     * it is.
     */
    static String headerJson(Map<String, ?> value) {
        String json;
        try {
            json = JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("The request could not be written as JSON", e);
        }
        StringBuilder out = new StringBuilder(json.length());
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c >= 0x7f) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else out.append(c);
        }
        return out.toString();
    }

    static Instant instant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String host(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? "" : h;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
