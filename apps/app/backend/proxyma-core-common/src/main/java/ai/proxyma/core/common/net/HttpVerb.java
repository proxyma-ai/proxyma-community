package ai.proxyma.core.common.net;

import java.util.Locale;

/**
 * The verb of a REST passthrough call (callJiraApi, callConfluenceApi, callBitbucketApi), and the one
 * rule for whether it reads.
 *
 * <p>The rule "only GET is a read" was written eight times with three meanings of a blank verb: the
 * tools ran the WRITE pre-flight for it, the services resolved the READ token, the chat's gate asked
 * for approval and the passthrough refused it. Each copy could drift from the token actually used.
 * Missing or blank is GET, everywhere.
 */
public final class HttpVerb {

    private HttpVerb() {}

    /** The verb in upper case; GET when none was given. */
    public static String normalize(String method) {
        return method == null || method.isBlank() ? "GET" : method.strip().toUpperCase(Locale.ROOT);
    }

    /** Whether the call only reads - GET, and nothing else. */
    public static boolean isRead(String method) {
        return "GET".equals(normalize(method));
    }
}
