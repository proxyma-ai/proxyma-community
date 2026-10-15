package ai.proxyma.core.connector.svn;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Where an SVN URL lies relative to a connection's server root - the one rule that decides whether
 * a saved SVN credential may be sent to it.
 *
 * <p>The live tools refused a URL on another server, because following it "would send that
 * credential wherever the URL pointed" - but registration had no such rule, so a repository URL on
 * any host could be registered, and its probe and every sync sent the connection's password (Basic,
 * or an NTLM exchange) there. One rule now, used by the tools, by registration and by the connector
 * before each command it runs with a credential.
 */
public final class SvnUrls {

    private SvnUrls() {}

    /** Whether the text is a URL (scheme://...) rather than a path below a root. */
    public static boolean isUrl(String text) {
        return text != null && text.trim().matches("(?i)^[a-z][a-z0-9+.-]*://.*");
    }

    /**
     * The path of {@code url} below {@code rootUrl}, "" for the root itself, or null when it does
     * not lie under that root - another scheme, host or port, or a path outside the root's.
     */
    public static String pathUnder(String rootUrl, String url) {
        if (rootUrl == null || rootUrl.isBlank() || url == null || url.isBlank()) return null;
        String root = canonical(rootUrl);
        String candidate = canonical(url);
        if (candidate.equals(root)) return "";
        if (candidate.startsWith(root + "/")) return candidate.substring(root.length() + 1);
        return null;
    }

    /** Whether {@code url} is the root or lies below it. */
    public static boolean isUnder(String rootUrl, String url) {
        return pathUnder(rootUrl, url) != null;
    }

    /**
     * A URL compared as a server sees it: percent-escapes decoded (the connector encodes each segment
     * itself, so an encoded remainder would be encoded twice), trailing slashes dropped, and the
     * scheme and host lower-cased - the only parts of a URL that are case-insensitive. A '..'
     * segment is resolved, so it cannot climb out of a root it appears to lie under.
     */
    public static String canonical(String url) {
        String u = url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        try {
            u = URLDecoder.decode(u.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            // A lone '%' is taken literally, as the server would.
        }
        int schemeEnd = u.indexOf("://");
        if (schemeEnd < 0) return u;
        int hostStart = schemeEnd + 3;
        int pathStart = u.indexOf('/', hostStart);
        if (pathStart < 0) return u.toLowerCase(Locale.ROOT);
        return u.substring(0, pathStart).toLowerCase(Locale.ROOT) + resolveDots(u.substring(pathStart));
    }

    private static String resolveDots(String path) {
        if (!path.contains("/.")) return path;
        java.util.ArrayDeque<String> kept = new java.util.ArrayDeque<>();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) {
                if (!kept.isEmpty()) kept.removeLast();
                continue;
            }
            kept.addLast(segment);
        }
        return kept.isEmpty() ? "" : "/" + String.join("/", kept);
    }
}
