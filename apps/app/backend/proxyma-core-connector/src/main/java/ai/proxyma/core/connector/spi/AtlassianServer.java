package ai.proxyma.core.connector.spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The root of a Jira, Confluence or Bitbucket Data Center server, from whatever page of it a person
 * copied from the browser - {@code https://host/browse/PROJ-1}, {@code https://host/display/SPACE/Page},
 * {@code https://bb/projects/P/repos/r/browse}. Every API path joined to such an address reaches the
 * web app, so a registration said "Project X was not found on https://host/browse/PROJ-1".
 *
 * <p>A Data Center server is often installed under a context path ({@code /jira}, {@code /confluence},
 * {@code /wiki}, {@code /bitbucket}) and that path IS part of its address, so nothing is stripped
 * blindly: the address is cut at the FIRST path segment that only the product's web UI (or its REST
 * API) starts with, and everything before it is kept. The query and fragment always go. A segment
 * is matched whole and in any case, so {@code /jiraweb} or {@code /projectsite} survive.
 *
 * <p>For Data Center and Server only - a Cloud address is {@link AtlassianCloud#site}'s.
 */
public final class AtlassianServer {

    private static final List<String> JIRA = List.of(
            "browse", "secure", "projects", "issues", "plugins/servlet", "servicedesk", "rest", "login.jsp");

    private static final List<String> CONFLUENCE = List.of(
            "display", "spaces", "pages", "dashboard.action", "x", "rest", "login.action");

    // "scm" is the clone address Bitbucket shows on every repository page, and is pasted as often.
    private static final List<String> BITBUCKET = List.of(
            "projects", "users", "dashboard", "plugins", "repos", "rest", "account", "admin", "scm", "login");

    private AtlassianServer() {}

    /** A Jira Data Center server's root. */
    public static String jiraRoot(String url) {
        return root(url, JIRA);
    }

    /** A Confluence Data Center server's root. */
    public static String confluenceRoot(String url) {
        return root(url, CONFLUENCE);
    }

    /** A Bitbucket Data Center server's root. */
    public static String bitbucketRoot(String url) {
        return root(url, BITBUCKET);
    }

    /**
     * {@code url} without its query and fragment, cut before the first segment that starts one of
     * {@code markers} (each one or more segments, {@code /}-separated), with no trailing slash.
     */
    static String root(String url, List<String> markers) {
        if (url == null) return null;
        String s = url.strip();
        int scheme = s.indexOf("://");
        int authorityStart = scheme < 0 ? 0 : scheme + 3;
        int end = s.length();
        for (int i = authorityStart; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '?' || ch == '#') {
                end = i;
                break;
            }
        }
        s = s.substring(0, end);
        int pathStart = s.indexOf('/', authorityStart);
        if (pathStart < 0) return s;

        String origin = s.substring(0, pathStart);
        List<String> segments = new ArrayList<>();
        for (String segment : s.substring(pathStart + 1).split("/")) {
            if (!segment.isEmpty()) segments.add(segment);
        }
        List<List<String>> cuts = markers.stream().map(m -> List.of(m.split("/"))).toList();
        int keep = segments.size();
        search:
        for (int i = 0; i < segments.size(); i++) {
            for (List<String> marker : cuts) {
                if (startsAt(segments, i, marker)) {
                    keep = i;
                    break search;
                }
            }
        }
        List<String> kept = segments.subList(0, keep);
        return kept.isEmpty() ? origin : origin + "/" + String.join("/", kept);
    }

    private static boolean startsAt(List<String> segments, int at, List<String> marker) {
        if (at + marker.size() > segments.size()) return false;
        for (int j = 0; j < marker.size(); j++) {
            if (!segments.get(at + j).toLowerCase(Locale.ROOT).equals(marker.get(j))) return false;
        }
        return true;
    }
}
