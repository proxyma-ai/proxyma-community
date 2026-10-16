package ai.proxyma.core.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A tool call whose argument NAMES were guessed: it names arguments the tool does not take and
 * leaves out one it needs.
 *
 * <p>WHY, from the 0.36.0 feedback: the agent called {@code remember} without having read the
 * Memory &amp; Knowledge skill, so the tool's schema had never been offered to it. A tool the model
 * was not offered is still run (the tool list is a visibility gate, never the control - see
 * ChatService), and the model wrote its fact under a name of its own. The tool saw no {@code fact},
 * answered "fact must not be blank", and the agent told the user the save had failed - with nothing
 * in its hands to do it right. The call is now answered with the tool's real arguments instead, and
 * the next call gets them right.
 *
 * <p>BOTH conditions, deliberately. A call that only leaves out a required argument still runs: many
 * tools declare every parameter required (Spring AI's default) and treat a missing one as absent,
 * and refusing those would break calls that work today. An unknown name alone is ignored by the tool
 * as it always was. Only the two together are the signature of a guessed schema.
 */
final class ToolArgumentCheck {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolArgumentCheck() {
    }

    /**
     * Why this call should not run, naming the tool's real arguments - or null when it may run.
     * Null too whenever either side cannot be read: this is a hint for the model, never a gate.
     */
    static String misnamedArguments(String toolName, String inputSchema, String arguments) {
        JsonNode schema = read(inputSchema);
        JsonNode args = read(arguments == null || arguments.isBlank() ? "{}" : arguments);
        if (schema == null || args == null || !args.isObject()) return null;
        JsonNode properties = schema.path("properties");
        if (!properties.isObject() || properties.isEmpty()) return null;

        Set<String> unknown = new LinkedHashSet<>();
        for (Iterator<String> names = args.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!properties.has(name)) unknown.add(name);
        }
        Set<String> required = new LinkedHashSet<>();
        for (JsonNode r : schema.path("required")) required.add(r.asText());
        List<String> missing = new ArrayList<>();
        for (String name : required) {
            JsonNode value = args.get(name);
            if (value == null || value.isNull()) missing.add(name);
        }
        if (unknown.isEmpty() || missing.isEmpty()) return null;

        List<String> described = new ArrayList<>();
        for (Iterator<String> names = properties.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            String description = properties.path(name).path("description").asText("");
            described.add(name + (required.contains(name) ? " (required)" : "")
                    + (description.isBlank() ? "" : ": " + description));
        }
        return "Not run: " + toolName + " was called with " + String.join(", ", unknown)
                + ", which it does not take, and without " + String.join(", ", missing)
                + ", which it needs. Its arguments are - " + String.join("; ", described)
                + ". Call it again with these names.";
    }

    private static JsonNode read(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
