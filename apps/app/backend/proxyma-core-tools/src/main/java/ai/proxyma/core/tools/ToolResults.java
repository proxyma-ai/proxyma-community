package ai.proxyma.core.tools;

import java.util.LinkedHashMap;
import java.util.Map;

/** The success envelope of a write that has already happened on the remote server. */
public final class ToolResults {

    private ToolResults() {}

    /**
     * {@code success: true} and the given name/value pairs, in order. A value the server did not
     * return is left out, with a note, rather than failing: built with Map.of, a null id - an empty
     * 2xx from a proxy or a plugin - threw AFTER the issue, comment or worklog had been created,
     * the model was told the write failed, and it made it again.
     */
    public static Map<String, Object> written(Object... namesAndValues) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        boolean missing = false;
        for (int i = 0; i + 1 < namesAndValues.length; i += 2) {
            Object value = namesAndValues[i + 1];
            if (value == null) {
                missing = true;
            } else {
                out.put(String.valueOf(namesAndValues[i]), value);
            }
        }
        if (missing) {
            out.put("note", "The server accepted the change but did not return every identifier. "
                    + "It was made - do not make it again.");
        }
        return out;
    }
}
