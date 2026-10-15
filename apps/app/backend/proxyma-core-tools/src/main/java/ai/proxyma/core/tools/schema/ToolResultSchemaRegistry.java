package ai.proxyma.core.tools.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every tool's declared result schema, built once at startup by reflecting over the same tool
 * beans that become tool callbacks.
 *
 * <p>Built from that one list on purpose (see McpToolConfig): a second enumeration of "the tools"
 * would be a second thing to keep in step, and the surface most likely to drift out of step is
 * exactly the one nobody looks at. Whatever is registered is what gets scanned, so a tool that is
 * deliberately not registered - SharePoint and Teams today - contributes no schema either, and
 * un-commenting it into the list brings its schema along with no second edit.
 */
public class ToolResultSchemaRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolResultSchemaRegistry.class);

    private static final String SUCCESS_DESCRIPTION =
            "True if the tool completed its work. False means it did not, and 'error' says why.";
    private static final String ERROR_DESCRIPTION =
            "Present only when success is false: a human-readable description of what went wrong.";

    /**
     * What a tool declared, in the form the drift detector needs - the JSON schema string is for
     * consumers, this is for checking.
     *
     * @param envelope   the result carries success/error
     * @param rawText    the result is text, not JSON, so there is nothing to check
     * @param allKeys    every top-level key the tool may return, envelope included
     * @param alwaysKeys the subset promised on every successful call
     */
    public record Declaration(boolean envelope, boolean rawText, Set<String> allKeys, Set<String> alwaysKeys) {}

    private final Map<String, String> schemas;
    private final Map<String, Declaration> declarations;
    private final List<String> undeclared;

    private ToolResultSchemaRegistry(Map<String, String> schemas, Map<String, Declaration> declarations,
                                     List<String> undeclared) {
        this.schemas = schemas;
        this.declarations = declarations;
        this.undeclared = undeclared;
    }

    /** The JSON Schema for a tool's result, or empty if it never declared one. */
    public Optional<String> schemaFor(String toolName) {
        return Optional.ofNullable(schemas.get(toolName));
    }

    public Optional<Declaration> declarationFor(String toolName) {
        return Optional.ofNullable(declarations.get(toolName));
    }

    /** Registered tools carrying no {@link ToolResult}, in registration order. */
    public List<String> undeclaredTools() {
        return List.copyOf(undeclared);
    }

    /**
     * Scans the given tool beans for {@code @Tool} methods and reads each one's {@link ToolResult}.
     *
     * <p>A tool with no declaration is reported at ERROR and otherwise skipped, rather than failing
     * startup. Nothing consumes these schemas at runtime yet, so a missing one breaks no user-facing
     * behaviour - but it silently removes that tool from anything that later binds to results, which
     * is the kind of omission that is only ever noticed by the feature that needed it. A loud line
     * on every boot is the strongest signal available in a workspace with no test suite.
     */
    public static ToolResultSchemaRegistry scan(List<Object> toolObjects) {
        List<Class<?>> classes = new ArrayList<>();
        for (Object bean : toolObjects) classes.add(ClassUtils.getUserClass(bean));
        return scanClasses(classes);
    }

    /**
     * The class-level half of {@link #scan}, split out so the scan can be exercised without a
     * Spring context standing up 59 beans first - the tool classes are all the scan actually reads.
     */
    static ToolResultSchemaRegistry scanClasses(List<Class<?>> toolClasses) {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, String> schemas = new LinkedHashMap<>();
        Map<String, Declaration> declarations = new LinkedHashMap<>();
        List<String> undeclared = new ArrayList<>();

        for (Class<?> toolClass : toolClasses) {
            for (Method method : toolClass.getMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool == null) continue;
                String name = (tool.name() == null || tool.name().isBlank()) ? method.getName() : tool.name();

                ToolResult declared = method.getAnnotation(ToolResult.class);
                if (declared == null) {
                    undeclared.add(name);
                    continue;
                }
                schemas.put(name, render(mapper, declared));
                declarations.put(name, toDeclaration(declared));
            }
        }

        if (!undeclared.isEmpty()) {
            log.error("[ToolSchema] {} registered tool(s) declare no @ToolResult and cannot be bound to: {}",
                    undeclared.size(), undeclared);
        }
        log.info("[ToolSchema] {} tool result schemas declared", schemas.size());
        return new ToolResultSchemaRegistry(Map.copyOf(schemas), Map.copyOf(declarations), List.copyOf(undeclared));
    }

    private static Declaration toDeclaration(ToolResult declared) {
        Set<String> all = new LinkedHashSet<>();
        Set<String> always = new LinkedHashSet<>();
        if (declared.envelope()) {
            all.add("success");
            all.add("error");
            always.add("success");
        }
        for (ToolResultField f : declared.value()) {
            all.add(f.name());
            if (f.always()) always.add(f.name());
        }
        return new Declaration(declared.envelope(), declared.rawText(), Set.copyOf(all), Set.copyOf(always));
    }

    private static String render(ObjectMapper mapper, ToolResult declared) {
        ObjectNode root = mapper.createObjectNode();
        if (!declared.description().isBlank()) {
            root.put("description", declared.description());
        }
        // read/grep: the whole result is one block of human-readable text, deliberately not JSON
        // (see RawTextResultConverter). "type": "string" is the honest schema for that; there is
        // no object to describe.
        if (declared.rawText()) {
            root.put("type", "string");
            return write(mapper, root);
        }

        root.put("type", "object");
        ObjectNode properties = root.putObject("properties");
        ArrayNode required = mapper.createArrayNode();

        if (declared.envelope()) {
            properties.putObject("success").put("type", "boolean").put("description", SUCCESS_DESCRIPTION);
            properties.putObject("error").put("type", "string").put("description", ERROR_DESCRIPTION);
            required.add("success");
        }

        ArrayNode requiredOnSuccess = mapper.createArrayNode();
        for (ToolResultField field : declared.value()) {
            ObjectNode node = properties.putObject(field.name());
            String jsonType = field.type().jsonType();
            if (jsonType == null) {
                // ToolFieldType.ANY - the value's type varies per call, so no type keyword at all.
                // An absent "type" is JSON Schema for "any", which is exactly the claim being made.
                if (field.nullable()) {
                    log.warn("[ToolSchema] field '{}' is ANY and nullable; nullable adds nothing to an untyped field",
                            field.name());
                }
            } else if (field.nullable()) {
                node.putArray("type").add(jsonType).add("null");
            } else {
                node.put("type", jsonType);
            }
            if (field.type().itemType() != null) {
                node.putObject("items").put("type", field.type().itemType());
            }
            node.put("description", field.description());
            if (field.passthrough()) {
                // Not a standard keyword, and namespaced so it cannot collide with one. It says
                // "we guarantee this key, not its interior" - see ToolResultField.passthrough().
                node.put("x-proxyma-passthrough", true);
            }
            if (field.always()) {
                requiredOnSuccess.add(field.name());
            }
        }

        if (!required.isEmpty()) {
            root.set("required", required);
        }
        // A payload key is promised only when the call SUCCEEDED - a failed call carries the error
        // and nothing else. Listing those keys in a flat "required" would declare a schema that
        // every error result violates, so the promise is expressed where it is actually true.
        if (!requiredOnSuccess.isEmpty() && declared.envelope()) {
            ObjectNode when = root.putObject("if");
            when.putObject("properties").putObject("success").put("const", true);
            when.putArray("required").add("success");
            root.putObject("then").set("required", requiredOnSuccess);
        } else if (!requiredOnSuccess.isEmpty()) {
            root.set("required", requiredOnSuccess);
        }
        return write(mapper, root);
    }

    private static String write(ObjectMapper mapper, ObjectNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            // Building a schema from an annotation cannot realistically fail, and if it somehow
            // does it must not take the application down over a descriptive artifact.
            log.warn("[ToolSchema] failed to render a tool result schema: {}", e.getMessage());
            return "{}";
        }
    }
}
