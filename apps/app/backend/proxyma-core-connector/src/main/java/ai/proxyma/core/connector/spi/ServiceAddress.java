package ai.proxyma.core.connector.spi;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A service address as a person typed it - very often whatever their browser showed, a page of the
 * service rather than its root - taken apart so a connector can keep what names the service and drop
 * what names a page. Each connector knows which parts are which; this only does the cutting, the same
 * way everywhere: the query and fragment are dropped, and so are trailing slashes.
 *
 * <p>Anything that is not an http(s) URL with a host comes back trimmed and otherwise as typed, so the
 * client refuses it with its own sentence.
 */
public final class ServiceAddress {

    private ServiceAddress() {}

    /** The address's lower-case host, or "" when it has none. */
    public static String host(String address) {
        URI uri = parse(address);
        return uri == null || uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    }

    /** {@code scheme://host[:port]} in lower case, nothing after it. */
    public static String origin(String address) {
        URI uri = parse(address);
        if (uri == null) return trim(address);
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                + (uri.getPort() >= 0 ? ":" + uri.getPort() : "");
    }

    /** The address with its path's segments, query and fragment dropped. */
    public static List<String> segments(String address) {
        URI uri = parse(address);
        List<String> out = new ArrayList<>();
        if (uri == null || uri.getRawPath() == null) return out;
        for (String s : uri.getRawPath().split("/")) {
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /** The origin and the first {@code count} path segments. */
    public static String prefix(String address, int count) {
        URI uri = parse(address);
        if (uri == null) return trim(address);
        List<String> segments = segments(address);
        StringBuilder out = new StringBuilder(origin(address));
        for (int i = 0; i < Math.min(count, segments.size()); i++) out.append('/').append(segments.get(i));
        return out.toString();
    }

    /**
     * The address up to (not including) the first place its path has {@code marker}'s segments in a
     * row - {@code "api/v4"}, {@code "-"} - compared without case; empty when the path has none.
     */
    public static Optional<String> before(String address, String marker) {
        if (parse(address) == null) return Optional.empty();
        List<String> segments = segments(address);
        String[] m = marker.replaceAll("^/+|/+$", "").split("/");
        for (int i = 0; i + m.length <= segments.size(); i++) {
            boolean match = true;
            for (int j = 0; j < m.length && match; j++) match = segments.get(i + j).equalsIgnoreCase(m[j]);
            if (match) return Optional.of(prefix(address, i));
        }
        return Optional.empty();
    }

    /**
     * The address cut before the earliest of {@code markers} its path has; without one, the whole
     * path. Query, fragment and trailing slashes go either way.
     */
    public static String cutAt(String address, String... markers) {
        URI uri = parse(address);
        if (uri == null) return trim(address);
        String best = prefix(address, Integer.MAX_VALUE);
        for (String marker : markers) {
            Optional<String> cut = before(address, marker);
            if (cut.isPresent() && cut.get().length() < best.length()) best = cut.get();
        }
        return best;
    }

    private static URI parse(String address) {
        if (address == null || address.isBlank()) return null;
        try {
            URI uri = URI.create(address.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) return null;
            return uri;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String trim(String address) {
        return address == null ? null : address.strip().replaceAll("/+$", "");
    }
}
