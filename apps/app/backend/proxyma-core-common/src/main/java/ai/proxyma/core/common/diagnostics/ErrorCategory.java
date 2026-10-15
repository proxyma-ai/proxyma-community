package ai.proxyma.core.common.diagnostics;

import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Reduces a failure to one word - timeout, network, auth, rate_limited, http_4xx, http_5xx, io,
 * cancelled, oom, other - for the reliability counters.
 *
 * <p>Decided from exception TYPES and an HTTP status, never from a message: a message is where a
 * URL, a path or a provider's echo of the request ends up. The status is read reflectively
 * ({@code getStatusCode()} on Spring's RestClient and WebClient exceptions) because this module
 * sits below spring-web and cannot name those classes.
 */
public final class ErrorCategory {

    private ErrorCategory() {}

    public static String of(Throwable error) {
        if (error == null) return "none";
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        Integer status = null;
        String byType = null;
        for (Throwable t = error; t != null && !seen.containsKey(t); t = t.getCause()) {
            seen.put(t, Boolean.TRUE);
            if (status == null) status = statusOf(t);
            if (byType == null) byType = byType(t);
        }
        if (status != null && status >= 400) {
            if (status == 401 || status == 403) return "auth";
            if (status == 404) return "not_found";
            if (status == 408 || status == 504) return "timeout";
            if (status == 429) return "rate_limited";
            return status >= 500 ? "http_5xx" : "http_4xx";
        }
        return byType != null ? byType : "other";
    }

    private static String byType(Throwable t) {
        String name = t.getClass().getName();
        String simple = t.getClass().getSimpleName();
        if (t instanceof OutOfMemoryError) return "oom";
        if (t instanceof InterruptedException || simple.equals("CancellationException")
                || simple.equals("ClosedByInterruptException")) {
            return "cancelled";
        }
        if (simple.contains("Timeout") || simple.equals("SocketTimeoutException")) return "timeout";
        if (name.startsWith("javax.net.ssl.")) return "tls";
        if (simple.equals("ConnectException") || simple.equals("UnknownHostException")
                || simple.equals("NoRouteToHostException") || simple.equals("PortUnreachableException")
                || simple.equals("SocketException") || simple.contains("ConnectTimeout")
                || simple.equals("PrematureCloseException") || simple.equals("WebClientRequestException")
                || simple.equals("ResourceAccessException")) {
            return "network";
        }
        if (name.startsWith("java.nio.file.") || t instanceof java.io.IOException) return "io";
        return null;
    }

    /** An HTTP status carried by the exception itself, or null. */
    static Integer statusOf(Throwable t) {
        for (String accessor : new String[] {"getStatusCode", "getRawStatusCode", "getStatus"}) {
            try {
                Method m = t.getClass().getMethod(accessor);
                m.trySetAccessible();
                Object value = m.invoke(t);
                if (value instanceof Integer i) return i;
                if (value != null) {
                    // Spring's HttpStatusCode for an unlisted code is a package-private class, so
                    // its public value() still needs the accessibility nudge.
                    Method v = value.getClass().getMethod("value");
                    v.trySetAccessible();
                    Object code = v.invoke(value);
                    if (code instanceof Integer i) return i;
                }
            } catch (Exception | LinkageError ignored) {
                // Not an HTTP exception, or not this shape of one.
            }
        }
        return null;
    }
}
