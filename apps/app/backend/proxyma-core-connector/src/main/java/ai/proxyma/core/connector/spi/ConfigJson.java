package ai.proxyma.core.connector.spi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A flat map of text values as a JSON object - the stored form of a native source's configuration
 * (its config column) and of a connection's settings and multi-field secret. Flat on purpose: every
 * value is what a {@link FieldSpec} validated, and a nested document would be a second schema
 * nobody checks.
 */
public final class ConfigJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ConfigJson() {}

    /** The object's text values; an absent, blank or unreadable value is an empty map. */
    public static Map<String, String> parse(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        if (json == null || json.isBlank() || !json.trim().startsWith("{")) return out;
        try {
            JsonNode node = JSON.readTree(json);
            node.fields().forEachRemaining(e -> {
                JsonNode v = e.getValue();
                if (v != null && !v.isNull()) out.put(e.getKey(), v.isTextual() ? v.asText() : v.toString());
            });
        } catch (JsonProcessingException e) {
            return new LinkedHashMap<>();
        }
        return out;
    }

    /** The map as a JSON object; null for an empty or absent one. */
    public static String write(Map<String, String> values) {
        if (values == null || values.isEmpty()) return null;
        try {
            return JSON.writeValueAsString(new LinkedHashMap<>(values));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A configuration could not be written", e);
        }
    }
}
