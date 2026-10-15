package ai.proxyma.core.tools.schema;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the shape of what a {@code @Tool} method returns.
 *
 * <p>Spring AI schemas a tool's INPUT for free - {@code @ToolParam} plus the Java parameter types
 * are a real JSON Schema, and {@code ToolDefinition.inputSchema()} exposes it. There is no
 * counterpart for the output: {@code ToolCallback.call()} returns {@code String}, and
 * {@code ToolDefinition} (Spring AI 1.1.7) has no {@code outputSchema()} to populate. Every tool
 * here returns {@code Map<String, Object>} with a success/error convention that is followed
 * everywhere and declared nowhere.
 *
 * <p>That is fine for a model, which reads the JSON and copes. It is not fine for anything that
 * has to bind to a FIELD of a result without a model in the loop - which is the whole premise of
 * the typed workflow engine (see proxyma-docs/proposals/workflow-engine.md): a decision predicate
 * over {@code collect.output.issueCount}, or a tool input bound to {@code search.output.issues}.
 * This annotation is that missing contract.
 *
 * <h2>The envelope is declared once, here</h2>
 *
 * Almost every tool returns {@code success} plus, on failure, {@code error}. Repeating those two
 * on 55 declarations would be 110 chances to write them slightly differently, so {@link #envelope}
 * defaults to true and adds them. Declare only the payload keys the tool adds on top.
 *
 * <h2>What this does NOT promise</h2>
 *
 * Only TOP-LEVEL keys are declared. Where a value is an upstream API's object, say so with
 * {@link ToolResultField#passthrough()} rather than transcribing that API's fields - see its
 * javadoc for why. So a binding to {@code result.issue} is checked, and a binding to
 * {@code result.issue.fields.status.name} is not.
 *
 * <p>The declaration is written by hand next to the method, so it can drift from what the method
 * actually returns. {@link ToolResultDriftCheck} exists because of that: it compares declared
 * keys against real ones on the first calls of each tool and logs the difference. It cannot make
 * drift impossible - it makes drift say so instead of being discovered by a workflow binding to a
 * key that stopped existing.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(java.lang.annotation.ElementType.METHOD)
public @interface ToolResult {

    /** The payload keys this tool adds on top of the envelope. */
    ToolResultField[] value() default {};

    /**
     * True (the default) when the result carries the shared {@code success} boolean and, on
     * failure, an {@code error} string. Set false for the handful of tools that return a bare
     * object with no success flag at all - see GetStatusTool and GetPlatformInfoTool, which cannot
     * fail in a way they report this way.
     */
    boolean envelope() default true;

    /**
     * True for a tool whose result is human-readable TEXT rather than JSON - {@code read} and
     * {@code grep}, which use {@link ai.proxyma.core.tools.RawTextResultConverter} precisely so
     * their output is not JSON-escaped. Their schema is {@code {"type":"string"}} and
     * {@link #value} must be empty: there are no fields to bind to, and a workflow wanting
     * structure out of them has to parse the text itself.
     */
    boolean rawText() default false;

    /** What the result represents as a whole. Optional; the field descriptions carry most of it. */
    String description() default "";
}
