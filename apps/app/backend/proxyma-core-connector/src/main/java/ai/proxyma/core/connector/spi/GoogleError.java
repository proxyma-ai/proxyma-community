package ai.proxyma.core.connector.spi;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Google's error answer, as every Google API writes it:
 * <pre>{@code
 * {"error": {"code": 403, "message": "The video identified by ...",
 *            "errors": [{"reason": "commentsDisabled", "message": "..."}],
 *            "details": [{"reason": "ACCESS_TOKEN_SCOPE_INSUFFICIENT"}]}}
 * }</pre>
 * The {@code errors[].reason} is the older APIs' code (YouTube Data, Drive, Gmail), the
 * {@code details[].reason} the newer ones' (and Google's front end, for a scope or a disabled API).
 * Read by the Google and YouTube connectors and the YouTube tools alike, so they agree on what a
 * refusal says.
 */
public final class GoogleError {

    private GoogleError() {}

    /** Every reason the answer gives, from both places Google writes one; empty for none or no JSON. */
    public static Set<String> reasons(ApiResponse response) {
        Set<String> out = new LinkedHashSet<>();
        JsonNode error = error(response);
        if (error == null) return out;
        for (JsonNode e : error.path("errors")) {
            String r = e.path("reason").asText("").strip();
            if (!r.isEmpty()) out.add(r);
        }
        for (JsonNode d : error.path("details")) {
            String r = d.path("reason").asText("").strip();
            if (!r.isEmpty()) out.add(r);
        }
        return out;
    }

    /** True when the answer gives any of these reasons. */
    public static boolean has(ApiResponse response, String... reasons) {
        Set<String> given = reasons(response);
        for (String r : reasons) if (given.contains(r)) return true;
        return false;
    }

    /**
     * The first reason and Google's own message, as one short line for a person -
     * "commentsDisabled (The video identified by the videoId parameter has disabled comments.)" -
     * cleaned and bounded ({@link RefusalReason#clean}); null when the answer states neither.
     */
    public static String describe(ApiResponse response) {
        JsonNode error = error(response);
        if (error == null) return null;
        Set<String> reasons = reasons(response);
        String reason = reasons.isEmpty() ? "" : reasons.iterator().next();
        String message = error.path("message").asText("").strip();
        if (message.isEmpty()) message = error.path("errors").path(0).path("message").asText("").strip();
        if (reason.isEmpty() && message.isEmpty()) return null;
        String line = reason.isEmpty() ? message : message.isEmpty() ? reason : reason + " (" + message + ")";
        return RefusalReason.clean(line);
    }

    private static JsonNode error(ApiResponse response) {
        if (response == null || response.bytes() == null || response.bytes().length == 0) return null;
        try {
            JsonNode error = response.json().path("error");
            return error.isObject() ? error : null;
        } catch (RuntimeException notJson) {
            return null;
        }
    }
}
