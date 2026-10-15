package ai.proxyma.core.chat.dto;

import java.util.List;

/**
 * The COMPLETE state of a run - what the SSE event carries and what the REST read returns, as one
 * type.
 *
 * <p>This is the shape the design settled on after starting with four delta events
 * (workflow-start / step-start / step-end / end). Three things fall out of a snapshot that are
 * worth the extra bytes:
 *
 * <ul>
 *   <li><b>One client parser.</b> The wire shape and the REST shape are the same object, so a
 *       refresh mid-run and a live event produce identical state by construction rather than by
 *       two implementations agreeing.
 *   <li><b>A dropped or out-of-order event self-heals on the next one.</b> A delta stream cannot
 *       recover a lost step-end, and the row stays "running" forever - exactly the failure the
 *       tool-call rows already had once (LESSONS_LEARNED, "Chat Tool Call UI Stuck In Progress
 *       After Final Response").
 *   <li><b>The first snapshot already contains every step</b>, so the table draws complete with
 *       all rows pending before any work happens. That is what makes it a plan the reader can look
 *       ahead in, rather than a log that accretes.
 * </ul>
 *
 * A snapshot is on the order of twenty rows of title and status - nothing against the message text
 * already streaming through this channel.
 */
public record WorkflowRunSnapshot(
        String runId,
        String workflowId,
        String workflowName,
        String status,
        List<Step> steps,
        long startedAt,
        Long finishedAt,
        String failureMessage,
        /**
         * The USER message this run hangs under - see WorkflowRun.triggerMessageId. Carried on the
         * wire as well as over REST so a client that reloads mid-run and one that watched the run
         * live end up with the table in the same place; without it the live table would have to be
         * positioned by a rule the reloaded one does not share.
         */
        Long triggerMessageId) {

    /**
     * @param callId  the step's synthetic tool-call id. Every tool event fired inside this step
     *                carries it as {@code parentCallId}, and the frontend already nests on that
     *                because sub-agent calls needed the same thing - so tool calls appear under
     *                their step with no new nesting code.
     * @param output  the step's own assistant text, rendered inside the expanded row rather than
     *                as its own bubble in the thread. Without that rule a five-step workflow
     *                produces five walls of text and the step table decorates the mess it was
     *                meant to replace. Null until the step finishes.
     */
    public record Step(
            int index,
            String title,
            String instruction,
            String status,
            String callId,
            String output,
            String failureMessage) {}

    /**
     * The snapshot as a browser may read it: every step's output and failure, and the run's, with
     * server paths taken out (SensitivePaths). The event stream screened it on the way out; the
     * REST read of the same snapshot did not (chat-api-data-14).
     */
    public WorkflowRunSnapshot scrubbed() {
        List<Step> clean = steps == null ? null : steps.stream()
                .map(s -> new Step(s.index(), s.title(), s.instruction(), s.status(), s.callId(),
                        scrub(s.output()), scrub(s.failureMessage())))
                .toList();
        return new WorkflowRunSnapshot(runId, workflowId, workflowName, status, clean, startedAt, finishedAt,
                scrub(failureMessage), triggerMessageId);
    }

    private static String scrub(String s) {
        return s == null ? null : ai.proxyma.core.system.util.SensitivePaths.scrub(s);
    }

    /** Mirrors WorkflowRun.Status plus the two states only a STEP can be in. */
    public enum StepStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        /** Never attempted, because an earlier step failed or the run was stopped. Drawn as the
         *  same empty circle as a step that has not started, greyed - not as a failure. */
        SKIPPED,
    }
}
