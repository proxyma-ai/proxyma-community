package ai.proxyma.core.connector.spi;

/**
 * How a {@link FieldSpec} is entered and checked. The SPA renders each with one kit form part; the
 * backend validates a value against the same type (ConfigValidator), so the dialog and the request
 * body cannot disagree about what is acceptable.
 */
public enum FieldType {
    /** One line of text. */
    TEXT,
    /** An http(s) address. */
    URL,
    /** A credential: never sent back to the page, kept encrypted, a blank edit keeps the stored one. */
    SECRET,
    /** A whole number, within {@code min}/{@code max} when given. */
    NUMBER,
    /** A switch; "true" or "false". */
    BOOLEAN,
    /** One of {@code options}. */
    SELECT,
    /** Any of {@code options}, stored comma-separated. */
    MULTISELECT,
    /** A list of free-text entries, stored one per line. */
    LIST,
    /** Several lines of text. */
    TEXTAREA
}
