package ai.proxyma.core.tools.schema;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * One top-level key of a tool's result, beyond the shared success/error envelope.
 *
 * <p>Target is deliberately empty: this is only ever written inside a {@link ToolResult}, never on
 * a declaration of its own.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface ToolResultField {

    /** The key as it appears in the returned map. */
    String name();

    ToolFieldType type();

    /** What the value means, for whoever is binding to it. Same job as ToolParam's description. */
    String description();

    /**
     * True if the key is present on EVERY successful call, so a consumer can bind to it without a
     * null check. Emitted as a conditional {@code required} (present only when success is true),
     * because a failed call carries the error and no payload at all.
     *
     * <p>Leave false for anything conditional - a key that only appears when the upstream API
     * returned something, or only on one branch of the tool.
     */
    boolean always() default false;

    /**
     * True when the key is present but its value may be JSON null - {@code webFetch} returns
     * {@code title} on every successful fetch and sets it to null for a PDF or an image, which has
     * no title to report.
     *
     * <p>Renders as {@code "type": ["string", "null"]} rather than being left implicit, because
     * "always present" and "always has a value" are different promises and a consumer binding to
     * the field needs to know which one it is getting. Without it, a declaration would claim a
     * string where a real, correct, successful result carries null.
     */
    boolean nullable() default false;

    /**
     * True when the value's INTERIOR is an upstream system's shape rather than ours - a raw Jira
     * issue, a Confluence page, a Bitbucket pull request as that API returned it.
     *
     * <p>Marked rather than described because the distinction is the difference between a contract
     * and a snapshot. We can promise that {@code getJiraIssue} returns an {@code issue} object; we
     * cannot promise what is inside it, and writing out Atlassian's field list here would be a
     * schema that goes stale the next time they ship. A consumer may still path into a passthrough
     * value - it just gets no guarantee from us that the path exists, and should be prepared for
     * it to be missing.
     */
    boolean passthrough() default false;
}
