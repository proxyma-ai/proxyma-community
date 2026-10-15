package ai.proxyma.core.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.proxyma.core.common.net.HttpVerb;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The generic REST escape hatch behind callJiraApi / callConfluenceApi / callBitbucketApi.
 *
 * <p>Atlassian's three products expose several hundred endpoints between them, and hand-writing a
 * tool per endpoint neither finishes nor stays finished - every Atlassian release adds more. So the
 * hand-written tools cover what an agent reaches for constantly, and this covers everything else:
 * whatever the configured credential is allowed to do, the agent can do.
 *
 * <p><b>The path is validated, not just concatenated.</b> The whole security property of this class
 * is that the request goes to the CONFIGURED host with the CONFIGURED credential and nowhere else.
 * A model-supplied path is untrusted input, and `baseUrl + path` is a textbook way to send a
 * privileged bearer token to an attacker's host: "https://evil.test/x" concatenates into something
 * a URI parser happily treats as absolute, and "/../.." can climb out of the API prefix. Both are
 * rejected below rather than sanitized, because a path this tool cannot understand is a path it
 * should not send.
 *
 * <p>Non-2xx responses are RETURNED, not thrown. An agent that gets Jira's own 400 body back can
 * correct the request itself; an agent that gets "request failed" can only guess. The status code
 * travels with it so a caller can tell 404-wrong-key from 403-no-permission.
 */
public final class RestPassthrough {

    private static final Logger log = LoggerFactory.getLogger(RestPassthrough.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Bounds one response, so a request for a large export cannot exhaust the caller's context. */
    private static final int MAX_BODY_CHARS = 100_000;

    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH");

    private RestPassthrough() {}

    /**
     * @param auth   the owning connector's own header customizer, so this shares one auth scheme
     *               with every hand-written call rather than reimplementing Bearer-vs-Basic
     * @param path   API path relative to the instance root, e.g. "/rest/api/2/issue/PROJ-1"
     * @param body   request body, already-serialized JSON text, or null
     */
    public static Map<String, Object> call(RestClient client, Consumer<HttpHeaders> auth,
                                           String baseUrl, String method, String path, String body) {
        // Missing or blank is GET - the one rule the tools and the chat gate follow (HttpVerb).
        String verb = HttpVerb.normalize(method);
        if (!ALLOWED_METHODS.contains(verb)) {
            throw new IllegalArgumentException(
                    "method must be one of " + ALLOWED_METHODS + ", got: " + method);
        }
        String uri = resolve(baseUrl, path);

        RestClient.RequestBodySpec spec = client.method(HttpMethod.valueOf(verb))
                .uri(URI.create(uri))
                .headers(auth::accept);
        if (body != null && !body.isBlank()) {
            spec = spec.contentType(MediaType.APPLICATION_JSON);
            spec.body(body);
        }

        // exchange(), not retrieve(): an error status is not thrown - the upstream error body is the
        // most useful thing this tool can return - and the body is READ NO FURTHER THAN THE BOUND.
        // It was read whole into a String and cut afterwards, so a GET of a repository archive or an
        // attachment - a read, with no approval - put the whole download in the server's memory.
        return spec.exchange((request, response) -> {
            int status = response.getStatusCode().value();
            MediaType type = response.getHeaders().getContentType();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", status);
            result.put("ok", status >= 200 && status < 300);
            if (type != null && !isText(type)) {
                // A download is not something to read into a reply; say what it was instead. Its
                // body is closed unread - on close Spring drains what is left, the whole download.
                try {
                    response.getBody().close();
                } catch (RuntimeException | java.io.IOException notOpened) {
                    // A client that had not opened the body has nothing to drain either.
                }
                result.put("body", "The response is " + type + " content, which is not returned here"
                        + (response.getHeaders().getContentLength() >= 0
                                ? " (" + response.getHeaders().getContentLength() + " bytes)." : "."));
                log.debug("[Passthrough] {} {} -> {} ({}, not read)", verb, path, status, type);
                return result;
            }
            byte[] bytes;
            boolean truncated;
            try (java.io.InputStream in = response.getBody()) {
                bytes = in.readNBytes(MAX_BODY_BYTES + 1);
                truncated = bytes.length > MAX_BODY_BYTES;
            }
            String raw = new String(bytes, 0, Math.min(bytes.length, MAX_BODY_BYTES), StandardCharsets.UTF_8);
            if (raw.length() > MAX_BODY_CHARS) {
                raw = raw.substring(0, MAX_BODY_CHARS);
                truncated = true;
            }
            result.put("body", truncated ? raw : parseIfJson(raw));
            if (truncated) result.put("truncated", true);
            log.debug("[Passthrough] {} {} -> {}", verb, path, status);
            return result;
        });
    }

    // The most read of a body: enough for MAX_BODY_CHARS of any UTF-8 text.
    private static final int MAX_BODY_BYTES = MAX_BODY_CHARS * 4;

    private static boolean isText(MediaType type) {
        String t = type.getType().toLowerCase(Locale.ROOT);
        String sub = type.getSubtype().toLowerCase(Locale.ROOT);
        return t.equals("text") || sub.contains("json") || sub.contains("xml") || sub.contains("javascript")
                || sub.equals("x-www-form-urlencoded");
    }

    /**
     * Joins base and path, refusing anything that could leave the configured host.
     *
     * Deliberately strict about what it accepts rather than clever about what it repairs. A query
     * string is fine and common (?expand=..., ?limit=100); a scheme, an authority, a protocol
     * -relative "//host" prefix, a backslash (which some URI parsers fold to "/") or any ".."
     * segment is refused outright.
     */
    private static String resolve(String baseUrl, String path) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("No base URL is configured for this connector");
        }
        if (path == null || path.isBlank()) {
            // No example path: this serves Jira, Confluence and Bitbucket, and a Jira path in an
            // error from callBitbucketApi sent the agent to an endpoint that server does not have.
            throw new IllegalArgumentException("path is required - the REST path on the configured server");
        }
        String p = path.trim();
        if (!p.startsWith("/")) p = "/" + p;
        if (p.startsWith("//")
                || p.contains("://")
                || p.contains(BACKSLASH)
                || hasDotDotSegment(p)) {
            throw new IllegalArgumentException(
                    "path must be a plain REST path on the configured server, starting with '/rest/' - "
                            + "it may not contain a host, a scheme or '..'");
        }
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String joined = base + p;
        // A percent-encoded climb ("%2e%2e") too, which a server decodes before it routes.
        if (DotSegmentGuard.climbs(URI.create(joined))) {
            throw new IllegalArgumentException(
                    "path must be a plain REST path on the configured server - it may not contain '..'");
        }
        return joined;
    }

    /** {@link #resolve}, for the test of what it refuses. */
    static String resolveForTest(String baseUrl, String path) {
        return resolve(baseUrl, path);
    }

    private static final String BACKSLASH = String.valueOf((char) 92);

    private static boolean hasDotDotSegment(String path) {
        for (String segment : path.split("/")) {
            if ("..".equals(segment)) return true;
        }
        return false;
    }

    /** Structured when the server sent JSON, the raw text when it did not (HTML error pages, 204s). */
    private static Object parseIfJson(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String trimmed = raw.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return raw;
        try {
            JsonNode node = MAPPER.readTree(trimmed);
            return MAPPER.convertValue(node, Object.class);
        } catch (Exception e) {
            return raw;
        }
    }
}
