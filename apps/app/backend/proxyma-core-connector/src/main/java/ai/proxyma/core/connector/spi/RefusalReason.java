package ai.proxyma.core.connector.spi;

import ai.proxyma.core.system.util.SensitivePaths;

import java.util.regex.Pattern;

/**
 * Why a service refused a request, as the body of its answer says it - read by a connector that
 * knows its service's error shape and handed to {@link ApiClient.Builder#refusalReason}, so the
 * sentence a refusal becomes ends with the service's own reason (2026-11-03: a YouTube channel whose
 * comments could not be read said only "refused access", while Google's 403 named
 * {@code commentsDisabled}, and nobody could tell why).
 *
 * <p>Without one a refusal stays the plain sentence and the body is never shown - a body may be a
 * whole sign-in page. With one, what is shown is ONE short line: bounded to {@link #MAX_CHARS}, on a
 * single line, with anything shaped like a credential or a server path taken out ({@link #clean}).
 */
@FunctionalInterface
public interface RefusalReason {

    /** The most of a reason a sentence carries. */
    int MAX_CHARS = 200;

    /** The reason as one short line, or null when the answer states none. */
    String of(ApiResponse response);

    /**
     * Google's error shape, which every Google API answers with: {@code error.errors[].reason} (or
     * {@code error.details[].reason} on the newer APIs) and {@code error.message} - "commentsDisabled
     * (The video identified by the videoId parameter has disabled comments.)".
     */
    RefusalReason GOOGLE = GoogleError::describe;

    Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+\\S+");
    Pattern SECRET_PARAM = Pattern.compile(
            "(?i)\\b(access_token|refresh_token|id_token|client_secret|api_key|key|token|code)=[^&\\s)]+");
    Pattern GOOGLE_TOKEN = Pattern.compile("(?:\\bya29\\.|\\b1//)[\\w./-]+");
    Pattern JWT = Pattern.compile("\\beyJ[\\w-]*\\.[\\w-]*\\.[\\w-]*");
    Pattern PXM = Pattern.compile("\\bpxm_[0-9A-Fa-f]{8,}");
    // An opaque run of letters AND digits, 32 or more: a key or a token. A reason code (all letters,
    // ACCESS_TOKEN_SCOPE_INSUFFICIENT) is not one.
    Pattern LONG_RUN = Pattern.compile("(?<![A-Za-z0-9_-])(?=[A-Za-z0-9_-]*\\d)(?=[A-Za-z0-9_-]*[A-Za-z])[A-Za-z0-9_-]{32,}");

    /**
     * {@code raw} as a reason a person may read: a bearer, a token, a key in a query, a long opaque run
     * of characters and a server path are replaced; line breaks and runs of spaces become one space; at
     * most {@link #MAX_CHARS} characters. Null for nothing left.
     */
    static String clean(String raw) {
        if (raw == null) return null;
        String s = BEARER.matcher(raw).replaceAll("Bearer [hidden]");
        s = SECRET_PARAM.matcher(s).replaceAll("$1=[hidden]");
        s = GOOGLE_TOKEN.matcher(s).replaceAll("[hidden]");
        s = JWT.matcher(s).replaceAll("[hidden]");
        s = PXM.matcher(s).replaceAll("[hidden]");
        s = LONG_RUN.matcher(s).replaceAll("[hidden]");
        s = SensitivePaths.scrub(s);
        s = s.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
        if (s.isEmpty()) return null;
        if (s.length() > MAX_CHARS) {
            int end = MAX_CHARS - 3;
            if (Character.isHighSurrogate(s.charAt(end - 1))) end--;
            s = s.substring(0, end).stripTrailing() + "...";
        }
        return s;
    }
}
