package ai.proxyma.connectors.google;

import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.spi.ApiAuth;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ApiResponse;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.ConnectorHttpException;
import ai.proxyma.core.connector.spi.RateLimitHint;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How every request to Google is made, for all three sources of a Google connection: the
 * connection's API address ({@code https://www.googleapis.com}, which serves Drive, Gmail and
 * Calendar alike under {@code /drive/v3}, {@code /gmail/v1} and {@code /calendar/v3}; a test server
 * when More options says so), the sign-in's access token as a bearer on every request and on no
 * other host, and the service's name as the sentences say it.
 *
 * <p>The access token comes from the framework (OAuth2ConnectionService): a user's sign-in,
 * refreshed when it expires, or a service account's signed assertion. A connector never sees a
 * refresh token or a private key.
 *
 * <p><b>Limits.</b> Google answers a per-user or per-project rate limit with a 403 - or a 429 - whose
 * reason is in the body ({@code error.errors[].reason}: {@code rateLimitExceeded},
 * {@code userRateLimitExceeded}), and asks to be retried with backoff; {@link #RATE_LIMIT} reads it,
 * so it is waited out rather than reported as access refused. A spent DAILY quota
 * ({@code dailyLimitExceeded}, {@code quotaExceeded}) is not cured by a wait and is reported for the
 * next pass.
 */
final class GoogleApi {

    static final String DEFAULT_BASE_URL = "https://www.googleapis.com";
    static final String MARKDOWN = "text/markdown";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> RATE_REASONS = Set.of("rateLimitExceeded", "userRateLimitExceeded");
    private static final Set<String> DAILY_REASONS = Set.of("dailyLimitExceeded", "quotaExceeded");

    /** Google's limits as its body states them; else the standard 429. */
    static final RateLimitHint RATE_LIMIT = ((RateLimitHint) (response, now) -> {
        if (response.status() != 403 && response.status() != 429) return Optional.empty();
        String reason = reason(response);
        if (DAILY_REASONS.contains(reason)) return Optional.of(RateLimitHint.NEXT_PASS);
        if (RATE_REASONS.contains(reason)) return RateLimitHint.STANDARD.waitFor(
                new ApiResponse(response.uri(), 429, response.headers(), response.bytes()), now)
                .or(() -> Optional.of(Duration.ZERO));
        return Optional.empty();
    }).or(RateLimitHint.STANDARD);

    private GoogleApi() {}

    /** A client for one Google API, named {@code service} in what it says ("Gmail refused ..."). */
    static ApiClient client(ConnectorHttp http, ResolvedCredentials connection, String service) {
        String base = connection.hasBaseUrl() ? connection.baseUrl() : DEFAULT_BASE_URL;
        return http.client(base)
                .service(service)
                .auth(ApiAuth.bearer(connection))
                .rateLimit(RATE_LIMIT)
                .build();
    }

    // The first reason an error answer gives, or "".
    private static String reason(ApiResponse response) {
        if (response.bytes().length == 0) return "";
        try {
            JsonNode error = response.json().path("error");
            return error.path("errors").path(0).path("reason").asText("");
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * What a registration's probe says when Google refused it: a sign-in it does not accept, or an
     * API the Google Cloud project has not switched on (Google's 403 for that, and for an account
     * that may not read the thing asked for).
     */
    static String refusal(ConnectorHttpException e, String apiName) {
        if (e.status() == 401) {
            return "Google did not accept the connection's sign-in. Sign in again, or check the service account, "
                    + "in Settings > Connections.";
        }
        if (e.status() == 403) {
            return "Google refused access. Check that the " + apiName + " is enabled in the Google Cloud project, "
                    + "and that the connection's account may read this.";
        }
        return e.getMessage();
    }

    // -- cursors -------------------------------------------------------------------------------

    /**
     * A cursor that resumes several feeds at once - a page token per Drive corpus, a sync token per
     * calendar - stored as one JSON object. Anything that does not read as one is no cursor at all,
     * and the pass that gets it lists in full.
     */
    static Map<String, String> readCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return new LinkedHashMap<>();
        try {
            Map<String, String> map = JSON.readValue(cursor, new TypeReference<LinkedHashMap<String, String>>() {});
            return map == null ? new LinkedHashMap<>() : new LinkedHashMap<>(map);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /** The cursor of several feeds, or null when there is none to resume from. */
    static String writeCursor(Map<String, String> tokens) {
        if (tokens == null || tokens.isEmpty()) return null;
        try {
            return JSON.writeValueAsString(tokens);
        } catch (Exception e) {
            return null;
        }
    }

    // -- values --------------------------------------------------------------------------------

    static Instant instant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso);
        } catch (RuntimeException e) {
            try {
                return java.time.OffsetDateTime.parse(iso).toInstant();
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }

    static Instant epochMillis(String millis) {
        if (millis == null || millis.isBlank()) return null;
        try {
            return Instant.ofEpochMilli(Long.parseLong(millis.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String text(JsonNode node, String field) {
        String v = node.path(field).asText("");
        return v == null ? "" : v.strip();
    }

    static void putExtra(ItemMetadata item, String key, String value) {
        if (value != null && !value.isBlank()) item.putExtra(key, value);
    }

    static InputStream markdown(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    // -- HTML to text --------------------------------------------------------------------------

    private static final Pattern DROPPED = Pattern.compile("(?is)<(script|style|head)[^>]*>.*?</\\1\\s*>");
    private static final Pattern BREAKS = Pattern.compile("(?i)<br\\s*/?>|</(p|div|li|tr|h[1-6]|blockquote)\\s*>");
    private static final Pattern TAGS = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern NUMERIC = Pattern.compile("&#(x?)([0-9a-fA-F]+);");

    /**
     * An HTML mail body or event description as plain text: block ends become line ends, tags go,
     * the common entities are decoded. Enough for text to search; the converters do the real work
     * on files.
     */
    static String htmlToText(String html) {
        if (html == null || html.isBlank()) return "";
        String s = DROPPED.matcher(html).replaceAll("");
        s = BREAKS.matcher(s).replaceAll("\n");
        s = TAGS.matcher(s).replaceAll("");
        Matcher m = NUMERIC.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String decoded;
            try {
                int code = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
                decoded = new String(Character.toChars(code));
            } catch (RuntimeException e) {
                decoded = m.group();
            }
            m.appendReplacement(out, Matcher.quoteReplacement(decoded));
        }
        m.appendTail(out);
        s = out.toString().replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&");
        s = s.replace("\r\n", "\n").replaceAll("[ \\t\\x0B\\f]+\\n", "\n").replaceAll("\\n{3,}", "\n\n");
        return s.strip();
    }

    /** The plain-text description of an event or a body: HTML when it looks like HTML, as it is otherwise. */
    static String plain(String maybeHtml) {
        if (maybeHtml == null) return "";
        return maybeHtml.matches("(?s).*<[a-zA-Z/][^>]*>.*") ? htmlToText(maybeHtml) : maybeHtml.strip();
    }
}
