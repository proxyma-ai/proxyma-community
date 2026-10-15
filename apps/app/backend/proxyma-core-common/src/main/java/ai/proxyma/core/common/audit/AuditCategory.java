package ai.proxyma.core.common.audit;

import java.util.List;

/**
 * What an audit entry is about - the tab or the filter it is read under.
 *
 * <p>STRINGS, NOT AN ENUM, deliberately. The column outlives releases, and a persisted enum is the
 * one shape this project has already been caught by twice: a table created before a constant
 * existed refuses it (H2's native ENUM, PostgreSQL's generated CHECK). A category added next
 * release is a new string in an existing VARCHAR column and needs nothing else.
 *
 * <p>The LLM call log is NOT one of these. It is its own table ({@code llm_calls}), with numeric
 * columns the spending limit totals, and the Audit Logs page reads it from there.
 */
public final class AuditCategory {

    /** A tool the agent, a sub-agent, a workflow or an MCP client called. */
    public static final String TOOL = "TOOL";
    /** One guardrail check, whatever it decided. */
    public static final String GUARDRAIL = "GUARDRAIL";
    /** Diagnostics collected, sent, refused, or deleted - Desktop and Home Server only. */
    public static final String DIAGNOSTICS = "DIAGNOSTICS";
    /** Sign-in, sign-out, failures and lockouts - Enterprise Server only. */
    public static final String SIGN_IN = "SIGN_IN";
    /** Accounts created, changed or removed by an administrator - Enterprise Server only. */
    public static final String USERS = "USERS";
    /** Settings saved, providers, license, configuration imported or exported. */
    public static final String CONFIG = "CONFIG";
    /** Something recorded was cleared or deleted. */
    public static final String DATA = "DATA";
    /** Sources and connections added or removed, and every sync's outcome. */
    public static final String SOURCES = "SOURCES";
    /** API and MCP tokens, and conversations shared. */
    public static final String ACCESS = "ACCESS";
    /** A spending limit, a quota or a provider's rate limit refused work. */
    public static final String LIMITS = "LIMITS";
    /** Updates found, downloaded, applied, failed. */
    public static final String UPDATES = "UPDATES";
    /** Backups, restores and maintenance - Enterprise Server only. */
    public static final String SYSTEM = "SYSTEM";

    /** Every category, in the order the Activity filter lists them. */
    public static final List<String> ALL = List.of(TOOL, GUARDRAIL, DIAGNOSTICS, SIGN_IN, USERS,
            CONFIG, DATA, SOURCES, ACCESS, LIMITS, UPDATES, SYSTEM);

    /** The categories the Activity tab shows together; the first three have tabs of their own. */
    public static final List<String> ACTIVITY = List.of(SIGN_IN, USERS, CONFIG, DATA, SOURCES,
            ACCESS, LIMITS, UPDATES, SYSTEM);

    private AuditCategory() {}
}
