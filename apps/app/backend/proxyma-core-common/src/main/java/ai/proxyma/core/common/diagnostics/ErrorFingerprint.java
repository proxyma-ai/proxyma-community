package ai.proxyma.core.common.diagnostics;

import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * What identifies one KIND of warning or error, with nothing in it that came from a user.
 *
 * <p>Built from four things, all of them code: the logger that reported it, the level, the
 * exception's class (and its innermost cause's), and up to {@link #MAX_FRAMES} stack frames from
 * Proxyma's own packages as {@code Class#method}. Deliberately NOT read for the IDENTITY: the log
 * message, the exception message, line numbers, and every frame outside {@code ai.proxyma}. (Line
 * numbers are carried beside the identity, in {@code trace}, since 2026-09-24 - never messages.) A message is where
 * paths, URLs and titles end up ("Could not read C:\Users\...\contract.pdf"); a line number changes
 * with every build and would split one kind of error into one per release; a third-party frame
 * says which library call was running, which the exception class already says.
 *
 * <p>A warning logged without an exception has no stack of its own, so the frame is the log call
 * site - which is also what tells two different warnings from the same class apart.
 */
public record ErrorFingerprint(String fingerprint, String level, String logger, String exception,
                               String rootCause, List<String> frames, List<String> trace,
                               List<String> causes) {

    public static final int MAX_FRAMES = 5;
    /** Frames in the readable trace - see DiagnosticsError.trace. Not part of the identity. */
    public static final int MAX_TRACE = 25;
    private static final String OWN_PACKAGE = "ai.proxyma.";
    private static final int MAX_CAUSE_DEPTH = 20;

    public static ErrorFingerprint of(String loggerName, String level, IThrowableProxy error,
                                      StackTraceElement[] callerData) {
        String logger = loggerName != null && loggerName.startsWith(OWN_PACKAGE) ? loggerName : "other";
        String exception = null;
        String rootCause = null;
        List<String> frames = List.of();
        List<String> trace = List.of();
        List<String> causes = List.of();
        if (error != null) {
            exception = error.getClassName();
            IThrowableProxy root = innermost(error);
            if (root != error) {
                rootCause = root.getClassName();
                if (rootCause != null && rootCause.equals(exception)) rootCause = null;
            }
            // The innermost cause is where it went wrong; the outer exception is where somebody
            // wrapped it. Prefer the first, fall back to the second when the root is all JDK.
            frames = ownFrames(root);
            if (frames.isEmpty()) frames = ownFrames(error);
            // The same choice for the readable trace, with line numbers and to a greater depth.
            trace = ownTrace(root);
            if (trace.isEmpty()) trace = ownTrace(error);
            causes = causeChain(error);
        }
        if (frames.isEmpty() && callerData != null) {
            for (StackTraceElement e : callerData) {
                if (e != null && e.getClassName().startsWith(OWN_PACKAGE)) {
                    frames = List.of(frame(e.getClassName(), e.getMethodName()));
                    trace = List.of(frames.get(0) + ":" + e.getLineNumber());
                    break;
                }
            }
        }
        String lvl = level == null ? "WARN" : level;
        String id = hash(logger + "|" + lvl + "|" + exception + "|" + rootCause + "|" + String.join(",", frames));
        return new ErrorFingerprint(id, lvl, logger, exception, rootCause, frames, trace, causes);
    }

    /** Proxyma's own frames as {@code Class#method:line}, consecutive repeats collapsed. */
    private static List<String> ownTrace(IThrowableProxy error) {
        StackTraceElementProxy[] stack = error.getStackTraceElementProxyArray();
        if (stack == null) return List.of();
        List<String> out = new ArrayList<>(MAX_TRACE);
        for (StackTraceElementProxy p : stack) {
            StackTraceElement e = p == null ? null : p.getStackTraceElement();
            if (e == null || !e.getClassName().startsWith(OWN_PACKAGE)) continue;
            String f = frame(e.getClassName(), e.getMethodName()) + ":" + e.getLineNumber();
            if (!out.isEmpty() && out.get(out.size() - 1).equals(f)) continue;
            out.add(f);
            if (out.size() >= MAX_TRACE) break;
        }
        return out;
    }

    /** Class names down the cause chain, outermost first. Names only - never a message. */
    private static List<String> causeChain(IThrowableProxy error) {
        List<String> out = new ArrayList<>();
        Map<IThrowableProxy, Boolean> seen = new IdentityHashMap<>();
        for (IThrowableProxy t = error; t != null && out.size() < MAX_CAUSE_DEPTH && !seen.containsKey(t);
             t = t.getCause()) {
            seen.put(t, Boolean.TRUE);
            out.add(t.getClassName());
        }
        return out;
    }

    private static IThrowableProxy innermost(IThrowableProxy error) {
        Map<IThrowableProxy, Boolean> seen = new IdentityHashMap<>();
        IThrowableProxy current = error;
        int depth = 0;
        while (current.getCause() != null && depth++ < MAX_CAUSE_DEPTH && !seen.containsKey(current)) {
            seen.put(current, Boolean.TRUE);
            current = current.getCause();
        }
        return current;
    }

    private static List<String> ownFrames(IThrowableProxy error) {
        StackTraceElementProxy[] stack = error.getStackTraceElementProxyArray();
        if (stack == null) return List.of();
        List<String> out = new ArrayList<>(MAX_FRAMES);
        for (StackTraceElementProxy p : stack) {
            StackTraceElement e = p == null ? null : p.getStackTraceElement();
            if (e == null || !e.getClassName().startsWith(OWN_PACKAGE)) continue;
            String f = frame(e.getClassName(), e.getMethodName());
            // Recursion and lambdas repeat a frame; the second copy says nothing new.
            if (!out.isEmpty() && out.get(out.size() - 1).equals(f)) continue;
            out.add(f);
            if (out.size() >= MAX_FRAMES) break;
        }
        return out;
    }

    /**
     * {@code Class#method}, with the generated parts stripped so that one piece of code produces one
     * frame: a CGLIB proxy suffix, a lambda's synthetic class, and a lambda method's counter.
     */
    static String frame(String className, String methodName) {
        String cls = className;
        int generated = cls.indexOf("$$");
        if (generated > 0) cls = cls.substring(0, generated);
        String method = methodName == null ? "?" : methodName;
        if (method.startsWith("lambda$")) {
            String[] parts = method.split("\\$");
            method = parts.length > 1 ? parts[1] + "(lambda)" : "lambda";
        }
        return cls + "#" + method;
    }

    private static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
