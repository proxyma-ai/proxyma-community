package ai.proxyma.core.system.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

// Turns a thrown exception into the sentence a user can act on.
//
// Provider SDKs habitually rewrap a failure with a message that names the OPERATION instead of the
// PROBLEM. Spring AI's Gemini integration turns every API error, 400s included, into
// RuntimeException("Failed to generate content", cause) - so the outermost message alone says only
// that something did not work. The reason lives on the cause:
//
//   outer:  Failed to generate content
//   cause:  404 . This model models/gemini-2.5-flash is no longer available to new users.
//           Please update your code to use a newer model.
//
// The second one names the model, the status and the fix. Reporting only getMessage() handed the
// user a sentence with nothing to do about it - observed for real when Google retired that model,
// where a main chat turn explained itself and a delegated turn did not, because the main turn went
// through ChatController's copy of this walk and the tools did not.
//
// Promoted here from ChatController/DelegateTool once the third caller appeared. Every tool result
// and every chat error now goes through the one implementation.
//
// What this returns reaches the browser. That is screened separately: ChatController's SSE
// formatter runs every event's data through SensitivePaths.scrub() on the way out, so a cause that
// quotes a filesystem path is handled there like any other event text.
public final class Failures {

    private static final int MAX_CAUSE_DEPTH = 10;
    private static final int MAX_DESCRIPTION_CHARS = 1500;

    private Failures() {}

    // Up to ten levels of the cause chain, joined - the outer message says which operation failed,
    // and the reason is often two or three levels down rather than at the root, which is what the
    // old both-ends-only walk dropped: a wrapper around a wrapper reported the operation and the
    // socket error and lost the HTTP status in between.
    //
    // Falls back to the class name when nothing in the chain carries a message
    // (NullPointerException typically does not, and "error: null" is worse than the class name).
    public static String describe(Throwable err) {
        if (err == null) return "Unknown error";
        List<String> parts = new ArrayList<>();
        Throwable current = err;
        // Bounded by BOTH a depth cap and a seen-set. The old loop only refused a cause that
        // pointed at itself, so a two-element cycle (A caused by B caused by A - rare, but
        // producible by hand-built exception chains and by some retry wrappers) spun forever
        // inside an error handler, which is the worst possible place to hang.
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH && seen.add(current); depth++) {
            String message = current instanceof java.nio.file.FileSystemException fse
                    ? describeFileSystem(fse) : current.getMessage();
            if (message != null && !message.isBlank()) {
                String trimmed = withoutHtmlBody(blockTimeout(message.trim()));
                // A rewrapped exception usually quotes its cause verbatim; printing both would say
                // the same sentence twice.
                boolean alreadySaid = parts.stream().anyMatch(p -> p.contains(trimmed));
                if (!alreadySaid) parts.add(trimmed);
            }
            current = current.getCause();
        }
        if (parts.isEmpty()) return err.getClass().getSimpleName();
        String joined = String.join(": ", parts);
        // A network fault's own words describe the socket, not the situation. "failure when
        // writing TLS control frames" was the whole of what a user behind a corporate proxy was
        // told when that proxy stopped connecting (seen 2026-09-24 in a feedback log) - accurate,
        // and useless. The detail stays, after a sentence saying what it probably means.
        String lead = networkLead(err);
        if (lead != null) joined = lead + " (" + joined + ")";
        // A provider that answers a 500 with an HTML page puts the whole page in a message. This
        // string reaches a tool result and a chat error bubble, so it is bounded here rather than
        // at each of the places that display it.
        return joined.length() > MAX_DESCRIPTION_CHARS
                ? joined.substring(0, MAX_DESCRIPTION_CHARS) + " ... [truncated]"
                : joined;
    }

    /**
     * The file and what went wrong with it, for a {@link java.nio.file.FileSystemException}.
     *
     * <p>Its getMessage() is only the PATH for the commonest subclasses - an
     * AccessDeniedException or a NoSuchFileException says "C:\Reports\q3.xlsx" and nothing
     * else, because the reason is carried by the exception's TYPE. Shown as-is, a user read a
     * file name with no hint whether it was locked, missing or forbidden. Public because the
     * connector module's SyncErrorAware.summarize words the same exceptions for a sync status.
     */
    public static String describeFileSystem(java.nio.file.FileSystemException e) {
        String file = e.getFile() != null ? e.getFile() : "A file";
        if (e instanceof java.nio.file.AccessDeniedException) return file + " - access denied";
        if (e instanceof java.nio.file.NoSuchFileException) return file + " - it no longer exists";
        String reason = e.getReason();
        if (reason != null && !reason.isBlank()) return file + " - " + reason.trim();
        // No reason recorded: the subclass name is the only thing left that says anything
        // ("FileAlreadyExistsException", "DirectoryNotEmptyException").
        return file + " - " + e.getClass().getSimpleName();
    }

    private static final java.util.regex.Pattern HTML_BODY =
            java.util.regex.Pattern.compile("\\s*:?\\s*\"?\\s*<(!doctype|html|head|body)\\b",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    // Spring's HTTP errors quote the whole response body: `404 Not Found: "<!DOCTYPE html>..."`. A
    // web server's error page is kilobytes of markup and says nothing the status did not, and it
    // reached a failed web page's row in the source dialog as a tooltip taller than the window
    // (maintainer's feedback, 2026-09-30). The status before it is kept; an HTML page that is the
    // whole message is left alone, since then there is nothing else to say.
    static String withoutHtmlBody(String message) {
        java.util.regex.Matcher m = HTML_BODY.matcher(message);
        if (!m.find() || m.start() == 0) return message;
        return message.substring(0, m.start()).trim();
    }

    private static final java.util.regex.Pattern BLOCK_TIMEOUT =
            java.util.regex.Pattern.compile("Timeout on blocking read for (\\d+) NANOSECONDS");

    // Reactor's Mono.block(Duration) words its timeout in nanoseconds: "Timeout on blocking read
    // for 600000000000 NANOSECONDS" was the whole of what a user was told when a slow model server
    // took more than ten minutes over one chat step (feedback, 2026-09-29).
    static String blockTimeout(String message) {
        java.util.regex.Matcher m = BLOCK_TIMEOUT.matcher(message);
        if (!m.find()) return message;
        long seconds;
        try {
            seconds = Long.parseLong(m.group(1)) / 1_000_000_000L;
        } catch (NumberFormatException e) {
            return message;
        }
        String wait = seconds >= 120 && seconds % 60 == 0 ? (seconds / 60) + " minutes"
                : seconds + (seconds == 1 ? " second" : " seconds");
        return "No complete answer arrived within " + wait
                + ", so this was stopped. The server may be overloaded or unreachable - try again, "
                + "or pick another model.";
    }

    /**
     * A plain sentence for the network faults a user can do something about, or null. Matched by
     * CLASS NAME for the ones from libraries this module does not depend on (Netty's proxy
     * handler), so the check costs no dependency.
     */
    static String networkLead(Throwable err) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        String lead = null;
        for (Throwable t = err; t != null && seen.add(t); t = t.getCause()) {
            String name = t.getClass().getSimpleName();
            String message = t.getMessage() == null ? "" : t.getMessage();
            if (name.equals("ProxyConnectException")) {
                return "Could not connect through the proxy. Check that it is running, or change it in Settings > Network.";
            }
            if (t instanceof java.net.UnknownHostException) {
                lead = "The server's name could not be resolved. Check the address and your network.";
            } else if (t instanceof javax.net.ssl.SSLException && lead == null) {
                lead = "The secure connection was cut off - often by a proxy or firewall. "
                        + "Check the proxy in Settings > Network.";
            } else if (t instanceof java.net.ConnectException && lead == null) {
                lead = "The server refused the connection or could not be reached.";
            } else if (message.contains("Request cancelled") && lead == null) {
                lead = "The server did not answer in time.";
            }
        }
        return lead;
    }

    // describe(), with a caller-supplied stand-in for the case where the exception explains
    // nothing. Tool results use this: "Delegation failed" reads better in a tool result than
    // "IllegalStateException", and the tool knows what it was trying to do.
    public static String describe(Throwable err, String fallback) {
        if (err == null) return fallback;
        String detail = describe(err);
        return detail.equals(err.getClass().getSimpleName()) ? fallback : detail;
    }
}
