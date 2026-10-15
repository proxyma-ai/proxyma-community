package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.dto.WorkflowRunSnapshot;
import ai.proxyma.core.system.util.Failures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Drives a workflow's steps, one scripted turn each, on the main ReAct loop.
 *
 * <p>THE CHOICE THIS CLASS EXISTS TO MAKE. A step could have been a sub-agent
 * ({@code SubAgentService}) - that class exists, is hardened, and its status vocabulary maps
 * exactly onto the three icons a step row needs. When this was written it was wrong for a
 * structural reason: a sub-agent was read-only, having nowhere to surface an approval prompt, and
 * a workflow that cannot write cannot file the issue, post the comment or open the pull request.
 * That reason went on 2026-10-02 - a sub-agent's writes now take the conversation's own approval -
 * and the other one stands: a sub-agent sees none of the conversation, and a workflow's steps are
 * a procedure carried out IN it, each reading what the one before established.
 *
 * <p>So a step is a scripted USER turn on the loop that already exists. Nothing about approval,
 * abort, condensation, offloading or spend accounting is reimplemented here, because none of it is
 * bypassed.
 *
 * <p>WHY THE LOOP IS INJECTED RATHER THAN CALLED. Running a step means running
 * {@code ChatService.react()}, and ChatService is what decides a turn is a workflow in the first
 * place. Holding a reference back to it would be a bean cycle for no gain, so the caller passes the
 * one operation this class needs as a {@link StepExecutor}. What is left here - the order, the
 * transitions, and what a failure does to the steps after it - is the whole of the orchestration
 * and is independent of how a turn is run.
 *
 * <p>See proxyma-docs/proposals/workflows.md, which records the decisions above rather than
 * leaving them to be rediscovered.
 */
@Service
public class WorkflowRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunner.class);

    private final WorkflowRunService workflowRunService;
    private final SmartChatMemory chatMemory;

    public WorkflowRunner(WorkflowRunService workflowRunService, SmartChatMemory chatMemory) {
        this.workflowRunService = workflowRunService;
        this.chatMemory = chatMemory;
    }

    /** What one step's turn produced: its assistant text, and how many ReAct iterations it took. */
    public record StepResult(String text, int iterations) {}

    /**
     * Runs one step as a scripted user turn.
     *
     * @param brief      the step's instruction, already carrying its workflow marker header
     * @param stepCallId the step's synthetic call id - every tool event fired inside the step
     *                   carries it as {@code parentCallId}, which is what nests tool calls under
     *                   their step row with no new frontend code
     * @param visible    true only for the LAST step. An intermediate step's text is not streamed to
     *                   the client and does not become its own assistant bubble; it belongs in that
     *                   step's row. Without that rule a five-step workflow produces five walls of
     *                   text and the step table decorates the mess it was meant to replace.
     */
    public interface StepExecutor {
        StepResult runStep(String brief, String stepCallId, boolean visible);
    }

    /** The turn's answer (the last step's text) and the iterations every step took together. */
    public record Outcome(String answer, int iterations) {}

    /**
     * Runs every step in order, stopping at the first failure.
     *
     * <p>The opening plan is written into the conversation before step 1 and the steps then run on
     * one thread. Context carries forward for free: this is a single conversation, so step N sees
     * every earlier step's findings with no explicit state passing.
     *
     * @param triggerMessageId the USER message the step table will be drawn under - see
     *                         WorkflowRun.triggerMessageId. Null is tolerated (the table then only
     *                         shows live, and does not reappear on reload) rather than fatal.
     */
    public Outcome run(String conversationId, WorkflowService.WorkflowDetail workflow,
                       Long triggerMessageId, AtomicBoolean abort, StepExecutor executor) {
        List<WorkflowService.WorkflowStep> steps = workflow.steps();
        WorkflowRunSnapshot snapshot = workflowRunService.start(
                conversationId, workflow.id(), workflow.name(), steps, triggerMessageId);
        UUID runId = UUID.fromString(snapshot.runId());

        // Written as messages, not into the system prompt: the system prompt is frozen at
        // conversation start (see ChatService.resolveSystemPrompt) and a run invoked on turn 7
        // would never reach it.
        chatMemory.add(conversationId, SmartChatMemory.workflowOpeningMessages(
                workflow.name(), workflow.preamble(), steps.stream().map(WorkflowService.WorkflowStep::title).toList()));

        String lastText = "";
        int iterations = 0;
        for (int i = 0; i < steps.size(); i++) {
            WorkflowService.WorkflowStep step = steps.get(i);
            boolean last = i == steps.size() - 1;

            // Checked BEFORE the step rather than only after it, so a Stop that lands between two
            // steps does not pay for one more LLM call before being noticed.
            if (abort.get()) {
                workflowRunService.stepFailed(runId, i, "Stopped");
                return new Outcome("Chat was stopped.", iterations);
            }

            workflowRunService.stepStarted(runId, i);
            String brief = SmartChatMemory.workflowStepBrief(i + 1, steps.size(), step.title(), step.instruction());
            String stepCallId = snapshot.steps().get(i).callId();

            StepResult result;
            try {
                result = executor.runStep(brief, stepCallId, last);
            } catch (RuntimeException e) {
                // Recorded, then rethrown. Rethrowing is what keeps a workflow turn failing the
                // same way an ordinary turn does - the caller's error path publishes the ERROR
                // event with the root cause it can describe properly, and a provider 401 or a
                // quota rejection is not quietly turned into an assistant bubble that reads like
                // an answer. The run row is already FAILED by then, so the table says which step
                // died alongside whatever the error path reports.
                log.warn("[Workflow] run {} step {} threw: {}", runId, i + 1, Failures.describe(e), e);
                workflowRunService.stepFailed(runId, i, Failures.describe(e, "The step failed."));
                throw e;
            }

            iterations += result.iterations();

            // react() answers an abort by RETURNING (with "Chat was stopped."), not by throwing, so
            // this is the only place a stopped step surfaces. Marked FAILED with a plain reason,
            // which drops every later step to SKIPPED in the same transition - they were never
            // attempted, and drawing them as failures is the same class of lie as narrating a
            // refused tool call in the past tense.
            if (abort.get()) {
                workflowRunService.stepFailed(runId, i, "Stopped");
                return new Outcome("Chat was stopped.", iterations);
            }

            lastText = result.text() == null ? "" : result.text();
            workflowRunService.stepCompleted(runId, i, lastText);
        }

        workflowRunService.finish(runId);
        // A run whose last step produced no text at all still has to answer something - the same
        // fallback an ordinary turn uses, worded for the case the reader is actually in.
        return new Outcome(lastText.isBlank()
                ? "The workflow finished without a final answer. Expand the steps above to see what each one found."
                : lastText, iterations);
    }
}
