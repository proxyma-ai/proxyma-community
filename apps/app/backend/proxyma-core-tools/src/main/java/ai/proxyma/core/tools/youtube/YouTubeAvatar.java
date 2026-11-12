package ai.proxyma.core.tools.youtube;

import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ApiResponse;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/**
 * A CHANNEL'S PICTURE AS AN INLINE IMAGE (2026-11-09, the maintainer: "can we get avatar of the channel? ... add it into
 * the card"). YouTube names the picture by an address on its image hosts; the app's pages load no image from another host
 * (SecurityHeadersConfig: img-src is the app's own origin, data: and blob:), and a reader's browser asks Google for
 * nothing. So the server downloads it - without the connection's credential, from YouTube's image hosts alone - once a day
 * with the channel's numbers, and the channel's dashboard carries it as {@code data:image/...;base64,...}.
 */
final class YouTubeAvatar {

    /** A YouTube channel id ("UC" and 22 characters): only such ids are asked about. */
    static final java.util.regex.Pattern CHANNEL_ID = java.util.regex.Pattern.compile("^UC[A-Za-z0-9_-]{22}$");

    /** The hosts YouTube serves channel pictures from; any other address is not fetched. */
    static final Set<String> HOSTS = Set.of("yt3.ggpht.com", "yt3.googleusercontent.com");
    /** The largest picture kept: a 240px avatar is some ten kilobytes. */
    static final int MAX_BYTES = 150_000;
    private static final Set<String> TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private YouTubeAvatar() {}

    /** The picture's address from a channels.list snippet - its 240px one, else its 88px one; null when it has none. */
    static String url(JsonNode snippet) {
        if (snippet == null) return null;
        JsonNode thumbs = snippet.path("thumbnails");
        for (String size : new String[] {"medium", "default"}) {
            String u = thumbs.path(size).path("url").asText("");
            if (!u.isBlank()) return u.strip();
        }
        return null;
    }

    /** Whether an address is one of YouTube's picture hosts, over https. */
    static boolean allowed(String url) {
        try {
            URI u = URI.create(url);
            return "https".equalsIgnoreCase(u.getScheme()) && u.getHost() != null
                    && HOSTS.contains(u.getHost().toLowerCase(Locale.ROOT)) && u.getUserInfo() == null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The picture as an inline image, or null: not one of YouTube's hosts, not an image it serves, too large, or failed. */
    static String inline(ConnectorHttp http, String url) {
        if (http == null || url == null || !allowed(url)) return null;
        try {
            URI u = URI.create(url);
            ApiClient client = http.client("https://" + u.getHost()).service("YouTube").maxAttempts(1).build();
            ApiResponse r = client.get(url);
            return r.ok() ? encode(r.header("Content-Type"), r.bytes()) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Bytes of one of the allowed types, within the limit, as a data: address; null otherwise. */
    static String encode(String contentType, byte[] bytes) {
        if (contentType == null || bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) return null;
        String type = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (!TYPES.contains(type)) return null;
        return "data:" + type + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }
}
