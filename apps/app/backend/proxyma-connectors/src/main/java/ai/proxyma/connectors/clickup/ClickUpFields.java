package ai.proxyma.connectors.clickup;

import ai.proxyma.connectors.workitems.WorkItems;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A ClickUp custom field's value as words. ClickUp hands a value back in the shape of its type - a
 * drop-down's chosen option as its position among the options, labels as option ids, people as
 * user objects, a date as milliseconds - so each is looked up or read for what a person would see.
 * Empty for a field with no value, and for a type this does not know whose value is not plain text.
 */
final class ClickUpFields {

    private ClickUpFields() {}

    static String value(JsonNode field) {
        JsonNode value = field.path("value");
        if (value.isMissingNode() || value.isNull()) return "";
        String type = field.path("type").asText("");
        JsonNode options = field.path("type_config").path("options");
        return switch (type) {
            case "drop_down" -> option(options, value);
            case "labels" -> {
                List<String> out = new ArrayList<>();
                for (JsonNode v : value) out.add(option(options, v));
                yield String.join(", ", out);
            }
            case "users", "people" -> {
                List<String> out = new ArrayList<>();
                for (JsonNode v : value) out.add(v.path("username").asText(v.path("email").asText("")));
                yield String.join(", ", out);
            }
            case "tasks", "list_relationship" -> {
                List<String> out = new ArrayList<>();
                for (JsonNode v : value) out.add(v.path("name").asText(v.path("id").asText("")));
                yield String.join(", ", out);
            }
            case "date" -> {
                Instant at = WorkItems.epochMillis(value.asText(null));
                yield at == null ? "" : at.toString();
            }
            case "checkbox" -> value.asBoolean(false) || "true".equalsIgnoreCase(value.asText()) ? "yes" : "no";
            case "progress", "automatic_progress" -> value.has("percent_complete")
                    ? value.path("percent_complete").asText() + "%" : "";
            case "location" -> value.path("formatted_address").asText("");
            default -> value.isValueNode() ? value.asText("") : "";
        };
    }

    // A drop-down or label option, by the id it is stored as or its position among the options.
    private static String option(JsonNode options, JsonNode chosen) {
        String key = chosen.asText("");
        for (JsonNode o : options) {
            if (key.equals(o.path("id").asText()) || chosen.isNumber() && chosen.asInt() == o.path("orderindex").asInt(-1)
                    || key.equals(o.path("orderindex").asText())) {
                String name = o.path("name").asText("");
                return name.isBlank() ? o.path("label").asText(key) : name;
            }
        }
        return key;
    }
}
