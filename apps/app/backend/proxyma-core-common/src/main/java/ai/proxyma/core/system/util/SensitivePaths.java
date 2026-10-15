package ai.proxyma.core.system.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Strips server-local filesystem paths out of anything on its way to a browser.
//
// The backend legitimately passes real absolute paths around - an offloaded tool result lives at a
// real file, and the agent has to be able to read it back. What must never leave the server is the
// path itself: it carries the deployment's directory layout and, for per-user data directories,
// the user id embedded in it. That combination is reconnaissance material, and it reached the chat
// UI verbatim through a delegated tool call's arguments.
//
// Applied at the frontend boundary, not at the source, deliberately. Scrubbing where paths are
// produced would break the features that need them (offload/read-back, conversion, indexing);
// scrubbing at the boundary means the rule is "everything crossing to the client is screened",
// which is a rule that holds no matter what a future tool decides to put in its output.
//
// The last path segment survives ("...\report.xlsx"): a bare "[redacted]" tells the user nothing
// about which file the agent was working on, and a filename on its own discloses no layout.
//
// Not a security boundary on its own - it is defence in depth over authorization, not a substitute
// for it. It is a text filter over free-form model and tool output, so it is best-effort by nature:
// it recognizes the shapes real paths take, not every string that could conceivably be one.
public final class SensitivePaths {

    private SensitivePaths() {}

    // Windows: a drive letter followed by at least one separator, then segments. Handles both raw
    // ("C:\Users\x") and JSON-escaped ("C:\\Users\\x") text, since this frequently runs over a
    // serialized tool-argument/result document rather than over prose.
    //
    // The leading lookbehind is load-bearing, not defensive: without it the "s:" of "https://host"
    // is a perfectly good drive-letter match and every URL the agent cited came out as "http.../x".
    // A real drive letter is a single character preceded by whitespace, a quote or a delimiter -
    // never by another letter or digit.
    private static final Pattern WINDOWS_PATH = Pattern.compile(
            "(?<![A-Za-z0-9])"
            + "[A-Za-z]:(?:\\\\{1,2}|/)(?:[^\\\\/:*?\"<>|\\r\\n]+(?:\\\\{1,2}|/))*[^\\\\/:*?\"<>|\\r\\n]*");

    // POSIX: rooted at a directory a server actually keeps things in, with at least one segment
    // below it - so "/data/users/<uid>/conversations/<cid>/x.txt" matches while a bare "/" or a
    // sentence containing "and/or" does not.
    //
    // The lookbehind is what keeps URLs intact: in "https://host/data/x" the "/data" is preceded by
    // a host character, and in "//host/data" by a slash - neither is a filesystem path, and
    // mangling the URLs the agent cites would be its own bug. A genuine path is preceded by
    // whitespace, a quote, or a delimiter.
    private static final Pattern POSIX_PATH = Pattern.compile(
            "(?<![A-Za-z0-9._~%\\-/])"
            + "/(?:data|opt|home|root|srv|mnt|media|var|tmp|usr|etc|proc|app)"
            + "(?:/[^\\s\"'`<>|:;,)\\]}\\\\]+)+");

    // Replaces every recognized path with an ellipsis plus its final segment. Null-safe and
    // allocation-free for the overwhelmingly common case of text containing no path at all.
    public static String scrub(String text) {
        if (text == null || text.isEmpty()) return text;
        return scrubWith(POSIX_PATH, scrubWith(WINDOWS_PATH, text));
    }

    private static String scrubWith(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) return text;
        StringBuilder out = new StringBuilder(text.length());
        do {
            m.appendReplacement(out, Matcher.quoteReplacement(shorten(m.group())));
        } while (m.find());
        m.appendTail(out);
        return out.toString();
    }

    // Keeps only the final segment. The replacement is intentionally free of backslashes and
    // quotes, so substituting it inside an already-serialized JSON document cannot produce invalid
    // JSON no matter what the original path contained.
    private static String shorten(String path) {
        int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String last = cut < 0 ? "" : path.substring(cut + 1);
        return last.isEmpty() ? "..." : ".../" + last;
    }
}
