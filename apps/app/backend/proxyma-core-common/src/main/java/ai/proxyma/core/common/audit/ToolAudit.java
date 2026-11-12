package ai.proxyma.core.common.audit;

import java.util.function.Supplier;

/**
 * Records tool calls for the Audit Logs page - arguments and result, both capped.
 *
 * <p>TWO PLACES RECORD, AND NEVER BOTH FOR ONE CALL. Every tool executes through
 * {@code ActivityRecordingToolCallback}, which records what nobody else does: an MCP client's calls
 * and a workflow node's. The chat's own tool path and a sub-agent's record THEMSELVES, because only
 * they know what the model was actually given - the result after the retrieval guardrail redacted
 * it - and what never ran at all: a write the user denied, a tool a sub-agent may not call. A
 * caller that records wraps its call in {@link #recordedByCaller}, and the wrapper stands aside.
 *
 * <p>The result is recorded AFTER the guardrail by design: a credential the guardrail took out of a
 * search hit before the model saw it must not be kept for ninety days in the log of that search.
 */
public final class ToolAudit {

    /** Arguments are usually short; a write's body can be long. */
    public static final int ARGS_CAP = 4_000;
    /** A tool's result can be a whole document; the log keeps its beginning. */
    public static final int RESULT_CAP = 8_000;

    private static final ThreadLocal<Boolean> CALLER_RECORDS = new ThreadLocal<>();

    private ToolAudit() {}

    /** Runs {@code body} - a tool call - telling the wrapper below it that the caller records it. */
    public static <T> T recordedByCaller(Supplier<T> body) {
        Boolean previous = CALLER_RECORDS.get();
        CALLER_RECORDS.set(Boolean.TRUE);
        try {
            return body.get();
        } finally {
            if (previous == null) CALLER_RECORDS.remove();
            else CALLER_RECORDS.set(previous);
        }
    }

    public static boolean isRecordedByCaller() {
        return Boolean.TRUE.equals(CALLER_RECORDS.get());
    }

    /**
     * One call.
     *
     * @param origin   who called it: "Agent", "Sub-agent", "Workflow", "MCP client"
     * @param agent    which sub-agent, where there is one
     * @param approval how a write was let through or stopped, where it was a write
     * @param outcome  null to read it off the result - a failure envelope is FAILED, anything else OK
     */
    public static void record(String userId, String conversationId, String tool, String origin, String agent,
                              String arguments, String result, String outcome, String approval, long durationMs) {
        String resolved = outcome != null ? outcome : outcomeOf(result);
        String args = arguments == null ? "{}" : arguments;
        AuditEntry.of(AuditCategory.TOOL, "tool.call")
                .user(userId)
                .target(tool)
                .conversation(conversationId)
                .outcome(resolved)
                .duration(durationMs)
                .summary(oneLine(args, 200))
                .detail("origin", origin)
                .detail("agent", agent)
                .detail("approval", approval)
                .detail("arguments", AuditEntry.cap(args, ARGS_CAP))
                .detail("result", AuditEntry.cap(result, RESULT_CAP))
                .detail("resultCharacters", result == null ? null : result.length())
                .record();
    }

    /**
     * FAILED for the failure envelope every JSON tool returns ({@code {"success":false,...}}) and for
     * the one the plain-text tools return ({@code read} and {@code grep} answer "Error: ..."), OK
     * otherwise.
     */
    public static String outcomeOf(String result) {
        if (result == null) return AuditEntry.FAILED;
        String head = result.length() > 64 ? result.substring(0, 64) : result;
        if (head.startsWith("Error: ")) return AuditEntry.FAILED;
        if (!head.replace(" ", "").startsWith("{\"success\":false")) return AuditEntry.OK;
        // THE APPROVALS INBOX'S ANSWERS ARE NOT FAILURES (2026-11-11 review: a team's figures showed 359 failed replies -
        // drafts parked for approval, or refused before they were kept, while YouTube's quota was spent).
        String status = statusOf(result.length() > 200 ? result.substring(0, 200) : result);
        if ("WAITING_FOR_APPROVAL".equals(status)) return AuditEntry.PENDING;
        if (status != null && INBOX_NOT_MADE.contains(status)) return AuditEntry.NOT_MADE;
        return AuditEntry.FAILED;
    }

    // The statuses the approvals inbox answers with when it neither keeps nor makes a write (ApprovalInbox).
    private static final java.util.Set<String> INBOX_NOT_MADE = java.util.Set.of("REFUSED", "NOT_MADE", "ALREADY_APPROVED",
            "BEING_EDITED");
    private static final java.util.regex.Pattern STATUS = java.util.regex.Pattern.compile("\"status\"\\s*:\\s*\"([A-Z_]+)\"");

    private static String statusOf(String head) {
        java.util.regex.Matcher m = STATUS.matcher(head);
        return m.find() ? m.group(1) : null;
    }

    static String oneLine(String text, int max) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }
}
