package ai.proxyma.core.settings.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * How long each kind of accumulated data is kept before it is swept.
 *
 * WHY THIS EXISTS. Several tables here are append-only and had no expiry at all: one
 * ChatTurnMetric per chat turn, one SearchStageMetric per search, one AnonymizedConversationTurn
 * per turn under Evaluation Mode, one row per platform LLM call, and every piece of feedback ever
 * submitted. On a busy deployment those are the fastest-growing tables in the database and nothing
 * ever removed a row.
 *
 * It also puts a number that was previously HARDCODED in front of an administrator. Conversations
 * idle for seven days were deleted automatically, with no setting, nothing in the UI saying so, and
 * no way to lengthen it - a user coming back from a fortnight's leave found their chat history
 * gone. Seven days remains the default, so nothing changes for anyone on upgrade, but it is now
 * visible and adjustable.
 *
 * ZERO MEANS KEEP FOREVER for every field.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RetentionSettings {

    public static final String PREFIX = "retention";

    /**
     * Idle conversations, in days. Pinned conversations are never swept regardless.
     *
     * Defaults to 7 to match the behaviour this replaced exactly - raising it is an administrator's
     * decision, not a silent change made on their behalf by an upgrade.
     */
    @JsonProperty("conversation-days")
    private int conversationDays = 7;

    /**
     * Evaluation Mode's per-turn and per-search records: ChatTurnMetric, SearchStageMetric and
     * AnonymizedConversationTurn. The highest-volume rows in the system - one per turn and one per
     * search - and the most sensitive, since anonymised turns carry conversation content.
     */
    @JsonProperty("evaluation-days")
    private int evaluationDays = 90;

    /**
     * Platform LLM spend records. Kept longer than evaluation data because they are what
     * year-on-year cost comparisons are drawn from, and each row is tiny.
     */
    @JsonProperty("usage-days")
    private int usageDays = 365;

    /**
     * The Audit Logs page's entries - tool calls, guardrail checks, diagnostics sends, sign-ins,
     * settings saved and the rest (audit_events). Not the LLM call log, which is usage-days above.
     */
    @JsonProperty("audit-days")
    private int auditDays = 90;

    /**
     * Submitted feedback and its attachments - a copy of what was sent to Proxyma, or of what
     * stayed here. 90 days, as for audit entries, rather than forever: a submission can carry 20 MB
     * of attachments, and the copy that matters is the one its reader acts on (swx-05).
     */
    @JsonProperty("feedback-days")
    private int feedbackDays = 90;

    public int getConversationDays() { return conversationDays; }
    public void setConversationDays(int conversationDays) { this.conversationDays = conversationDays; }

    public int getEvaluationDays() { return evaluationDays; }
    public void setEvaluationDays(int evaluationDays) { this.evaluationDays = evaluationDays; }

    public int getUsageDays() { return usageDays; }
    public void setUsageDays(int usageDays) { this.usageDays = usageDays; }

    public int getAuditDays() { return auditDays; }
    public void setAuditDays(int auditDays) { this.auditDays = auditDays; }

    public int getFeedbackDays() { return feedbackDays; }
    public void setFeedbackDays(int feedbackDays) { this.feedbackDays = feedbackDays; }
}
