package ai.proxyma.core.tools.schema;

/**
 * The JSON type of one declared field of a tool's result.
 *
 * <p>Deliberately a short closed list rather than arbitrary JSON Schema. A tool result is a flat
 * envelope with a handful of payload keys (see {@link ToolResult}); the shapes below cover every
 * one of them, and anything they cannot express is a signal that the value is an upstream API's
 * object rather than ours - which is what {@link ToolResultField#passthrough()} is for.
 */
public enum ToolFieldType {

    STRING("string", null),
    INTEGER("integer", null),
    NUMBER("number", null),
    BOOLEAN("boolean", null),
    OBJECT("object", null),
    STRING_ARRAY("array", "string"),
    OBJECT_ARRAY("array", "object"),

    /**
     * The value's JSON type genuinely varies between calls, so no type is emitted at all.
     *
     * <p>One real case: {@code transitionJiraIssue} returns the applied transition as an object, or
     * - when no target status was given - the LIST of available transitions instead. Declaring
     * either one would be a schema that half of all calls violate, and a schema that is wrong half
     * the time is worse than one that admits it does not know.
     *
     * <p>Not a default and not a convenience. Reach for it only when the type really is
     * call-dependent; a value that is merely complex is an {@link #OBJECT}.
     */
    ANY(null, null);

    private final String jsonType;
    private final String itemType;

    ToolFieldType(String jsonType, String itemType) {
        this.jsonType = jsonType;
        this.itemType = itemType;
    }

    /** The JSON Schema {@code type}, or null for {@link #ANY} - which emits no type keyword. */
    public String jsonType() {
        return jsonType;
    }

    /** The {@code items.type} for an array, or null for a scalar/object. */
    public String itemType() {
        return itemType;
    }
}
