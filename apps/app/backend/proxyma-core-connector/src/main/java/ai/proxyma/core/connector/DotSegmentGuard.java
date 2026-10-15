package ai.proxyma.core.connector;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Refuses a request whose path climbs with a dot segment - on every request a connector's client
 * sends, whichever method built the path.
 *
 * <p>The typed tools put ids the model supplied into REST paths: an issue key, a page, comment or
 * link id, a repository slug. Concatenated or expanded with a non-strict encoder, a "/" and ".."
 * survive, so an approved deleteConfluenceComment("1/../../space/DOCS") was sent as DELETE
 * .../content/1/../../space/DOCS - which the server normalizes to deleting a SPACE. The approval
 * prompt and the audit record named one operation and the server performed another. RestPassthrough
 * refuses a ".." path for exactly this reason; the typed methods, about fifty of them, did not.
 * Checking the finished request here covers all of them at once, and any added later.
 *
 * <p>A legitimate Jira, Confluence or Bitbucket REST path never contains a "." or ".." segment.
 */
public final class DotSegmentGuard implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (climbs(request.getURI())) {
            throw new IllegalArgumentException("That id is not one this service uses - it holds a path of its own. "
                    + "Use the id exactly as the service shows it.");
        }
        return execution.execute(request, body);
    }

    /** Whether the path has a "." or ".." segment, written out or percent-encoded. */
    public static boolean climbs(URI uri) {
        String raw = uri.getRawPath();
        if (raw == null || raw.isEmpty()) return false;
        String decoded;
        try {
            decoded = URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            decoded = raw;
        }
        return hasDotSegment(raw) || hasDotSegment(decoded);
    }

    private static boolean hasDotSegment(String path) {
        for (String segment : path.replace('\\', '/').split("/")) {
            if (segment.equals(".") || segment.equals("..")) return true;
        }
        return false;
    }
}
