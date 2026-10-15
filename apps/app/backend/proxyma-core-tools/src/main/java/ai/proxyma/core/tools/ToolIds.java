package ai.proxyma.core.tools;

import java.util.regex.Pattern;

/**
 * The identifiers a model hands a connector tool, checked before they are put into a REST path.
 *
 * <p>Nothing checked them, and the connectors build their paths by concatenation: a comment id of
 * {@code 1/../../space/DOCS} made an approved deleteConfluenceComment send DELETE /rest/api/space/DOCS,
 * and a link id of {@code 10501/../../issue/PROJ-1} deleted an issue. The person approved one
 * operation and another was sent; over MCP nobody approves at all. Each id is refused unless it has
 * the shape its server gives it.
 */
public final class ToolIds {

    private ToolIds() {}

    private static final Pattern ISSUE_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*-[0-9]+");
    private static final Pattern PROJECT_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");
    // A Confluence space key, or a personal space (~username); a Bitbucket project key likewise.
    private static final Pattern KEY = Pattern.compile("~?[A-Za-z0-9_.-]+");
    private static final Pattern SLUG = Pattern.compile("[A-Za-z0-9_.~-]+");
    private static final Pattern NUMBER = Pattern.compile("[0-9]{1,19}");

    /** A Jira issue key such as PROJ-123. */
    public static String issueKey(String value) {
        return check("issue key", value, ISSUE_KEY, "such as PROJ-123");
    }

    /** A Jira project key such as PROJ. Null and blank pass, for the calls where it is optional. */
    public static String projectKey(String value) {
        return value == null || value.isBlank() ? value : check("project key", value, PROJECT_KEY, "such as PROJ");
    }

    /** A Confluence space key or a Bitbucket project key. Null and blank pass. */
    public static String key(String what, String value) {
        if (value == null || value.isBlank()) return value;
        String v = check(what, value, KEY, "made of letters, digits, '_', '-' and '.'");
        if (isDots(v)) throw invalid(what, value, "made of letters, digits, '_', '-' and '.'");
        return v;
    }

    /** A Bitbucket repository slug. */
    public static String slug(String value) {
        String v = check("repository slug", value, SLUG, "made of letters, digits, '_', '-', '.' and '~'");
        if (isDots(v)) throw invalid("repository slug", value, "made of letters, digits, '_', '-', '.' and '~'");
        return v;
    }

    /** A numeric id: a page, comment, link, version or pull request. */
    public static String number(String what, String value) {
        return check(what, value, NUMBER, "a number");
    }

    private static boolean isDots(String v) {
        return v.equals(".") || v.equals("..");
    }

    private static String check(String what, String value, Pattern shape, String expected) {
        String v = value == null ? "" : value.trim();
        if (!shape.matcher(v).matches()) throw invalid(what, value, expected);
        return v;
    }

    private static IllegalArgumentException invalid(String what, String value, String expected) {
        String shown = value == null ? "nothing" : "'" + (value.length() > 60 ? value.substring(0, 60) + "..." : value) + "'";
        return new IllegalArgumentException("The " + what + " must be " + expected + "; got " + shown
                + ". Use the identifier exactly as the server shows it.");
    }
}
