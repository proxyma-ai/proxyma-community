package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.dto.WorkflowRunSnapshot;
import ai.proxyma.core.chat.event.ChatEvent;
import ai.proxyma.core.chat.model.WorkflowRun;
import ai.proxyma.core.chat.repository.WorkflowRunRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns run state and is the ONLY place a run transitions.
 *
 * Every mutator persists the row and publishes the same snapshot it returns, so the SSE stream and
 * a later REST read cannot describe different states - they are literally the same object, built
 * once. Splitting "save" from "announce" across call sites is how a stream and a database drift.
 */
@Service
public class WorkflowRunService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunService.class);

    private final WorkflowRunRepository repository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final ChatEventSink chatEventSink;

    public WorkflowRunService(WorkflowRunRepository repository,
                              ObjectMapper objectMapper,
                              ApplicationEventPublisher eventPublisher,
                              ChatEventSink chatEventSink) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.chatEventSink = chatEventSink;
    }

    /** The persisted per-step state. Kept separate from the wire record so the stored shape can
     *  gain a field without changing what clients parse, and vice versa. */
    private record StoredStep(int index, String title, String instruction, String status,
                              String callId, String output, String failureMessage) {}

    /**
     * Writes the run row with every step PENDING and announces it, before any work starts. That
     * first snapshot is what draws the whole table up front - the property the entire feature
     * rests on, since a plan you can read ahead in is the thing a workflow has that a skill does
     * not.
     */
    @Transactional("chatTransactionManager")
    public WorkflowRunSnapshot start(String conversationId, String workflowId, String workflowName,
                                      List<WorkflowService.WorkflowStep> steps, Long triggerMessageId) {
        WorkflowRun run = new WorkflowRun();
        run.setConversationId(conversationId);
        run.setWorkflowId(workflowId);
        run.setWorkflowName(workflowName);
        run.setTriggerMessageId(triggerMessageId);
        run.setStatus(WorkflowRun.Status.RUNNING);
        run.setStartedAt(Instant.now());

        UUID runId = UUID.randomUUID();
        run.setId(runId);
        List<StoredStep> stored = new ArrayList<>(steps.size());
        for (WorkflowService.WorkflowStep s : steps) {
            // The step's call id is derived, not random: every tool event fired inside the step
            // carries it as parentCallId, and a deterministic id means a reconnecting client can
            // match tool calls to steps from the snapshot alone.
            stored.add(new StoredStep(s.index(), s.title(), s.instruction(),
                    WorkflowRunSnapshot.StepStatus.PENDING.name(),
                    runId + ":" + s.index(), null, null));
        }
        run.setStepsJson(writeSteps(stored));
        repository.save(run);
        log.info("[Workflow] run {} started: '{}' ({} steps) in conversation {}",
                runId, workflowName, steps.size(), conversationId);
        return publish(run);
    }

    @Transactional("chatTransactionManager")
    public WorkflowRunSnapshot stepStarted(UUID runId, int index) {
        return mutate(runId, steps -> replace(steps, index, s ->
                new StoredStep(s.index(), s.title(), s.instruction(),
                        WorkflowRunSnapshot.StepStatus.RUNNING.name(), s.callId(), null, null)));
    }

    @Transactional("chatTransactionManager")
    public WorkflowRunSnapshot stepCompleted(UUID runId, int index, String output) {
        return mutate(runId, steps -> replace(steps, index, s ->
                new StoredStep(s.index(), s.title(), s.instruction(),
                        WorkflowRunSnapshot.StepStatus.COMPLETED.name(), s.callId(), output, null)));
    }

    /**
     * Marks the step failed, every later step SKIPPED, and the run FAILED - in one transition, so
     * no client ever sees a run whose failed step has not yet stopped the run.
     *
     * Later steps are SKIPPED rather than FAILED because they were never attempted. Drawing them
     * as failures is the same class of lie as narrating a tool call in the past tense when it was
     * refused.
     */
    @Transactional("chatTransactionManager")
    public WorkflowRunSnapshot stepFailed(UUID runId, int index, String failureMessage) {
        WorkflowRun run = repository.findById(runId).orElse(null);
        if (run == null) return null;
        List<StoredStep> steps = readSteps(run.getStepsJson());
        List<StoredStep> next = new ArrayList<>(steps.size());
        for (StoredStep s : steps) {
            if (s.index() == index) {
                next.add(new StoredStep(s.index(), s.title(), s.instruction(),
                        WorkflowRunSnapshot.StepStatus.FAILED.name(), s.callId(), s.output(), failureMessage));
            } else if (s.index() > index) {
                next.add(new StoredStep(s.index(), s.title(), s.instruction(),
                        WorkflowRunSnapshot.StepStatus.SKIPPED.name(), s.callId(), null, null));
            } else {
                next.add(s);
            }
        }
        run.setStepsJson(writeSteps(next));
        run.setStatus(WorkflowRun.Status.FAILED);
        run.setFinishedAt(Instant.now());
        run.setFailureMessage(truncate(failureMessage));
        repository.save(run);
        log.info("[Workflow] run {} failed at step {}: {}", runId, index, failureMessage);
        return publish(run);
    }

    @Transactional("chatTransactionManager")
    public WorkflowRunSnapshot finish(UUID runId) {
        WorkflowRun run = repository.findById(runId).orElse(null);
        if (run == null) return null;
        run.setStatus(WorkflowRun.Status.COMPLETED);
        run.setFinishedAt(Instant.now());
        repository.save(run);
        log.info("[Workflow] run {} completed", runId);
        return publish(run);
    }

    @Transactional(value = "chatTransactionManager", readOnly = true)
    public List<WorkflowRunSnapshot> forConversation(String conversationId) {
        return repository.findByConversationIdOrderByStartedAtAsc(conversationId).stream()
                .map(this::toSnapshot)
                .toList();
    }

    @Transactional(value = "chatTransactionManager", readOnly = true)
    public Optional<WorkflowRunSnapshot> find(UUID runId) {
        return repository.findById(runId).map(this::toSnapshot);
    }

    /**
     * messageId -> runId, for every run in this conversation that has an anchor.
     *
     * <p>Read by the transcript builder so a reloaded conversation can say WHICH message a run
     * hangs under, without it also having to load the run's steps - the client fetches those from
     * the run endpoint as one shape, live or reloaded. A message with two runs under it cannot
     * happen (one run per turn, one turn per message), so last-wins on a duplicate key is a
     * formality rather than a policy.
     */
    @Transactional(value = "chatTransactionManager", readOnly = true)
    public Map<Long, String> runIdsByTriggerMessage(String conversationId) {
        Map<Long, String> byMessage = new LinkedHashMap<>();
        for (WorkflowRun run : repository.findByConversationIdOrderByStartedAtAsc(conversationId)) {
            if (run.getTriggerMessageId() != null) {
                byMessage.put(run.getTriggerMessageId(), run.getId().toString());
            }
        }
        return byMessage;
    }

    /** See WorkflowRun's note on deletion - this is the half a separate table does not get free. */
    @Transactional("chatTransactionManager")
    public void deleteForConversation(String conversationId) {
        repository.deleteByConversationId(conversationId);
    }

    // - internals -

    private WorkflowRunSnapshot mutate(UUID runId, java.util.function.UnaryOperator<List<StoredStep>> op) {
        WorkflowRun run = repository.findById(runId).orElse(null);
        if (run == null) return null;
        run.setStepsJson(writeSteps(op.apply(readSteps(run.getStepsJson()))));
        repository.save(run);
        return publish(run);
    }

    private static List<StoredStep> replace(List<StoredStep> steps, int index,
                                             java.util.function.UnaryOperator<StoredStep> op) {
        List<StoredStep> next = new ArrayList<>(steps.size());
        for (StoredStep s : steps) next.add(s.index() == index ? op.apply(s) : s);
        return next;
    }

    /**
     * Persist-then-announce, with the SAME snapshot object going to both. Publishing is
     * best-effort: a failed publish must not roll back a transition that really happened, because
     * the REST read is what a refresh uses and it would then disagree with reality.
     */
    private WorkflowRunSnapshot publish(WorkflowRun run) {
        WorkflowRunSnapshot snapshot = toSnapshot(run);
        try {
            eventPublisher.publishEvent(new ChatEvent(
                    run.getConversationId(),
                    chatEventSink.nextSequence(run.getConversationId()),
                    ChatEvent.Type.WORKFLOW_RUN.value(),
                    run.getId().toString(),
                    run.getWorkflowName(),
                    objectMapper.writeValueAsString(snapshot)));
        } catch (Exception e) {
            log.warn("[Workflow] could not publish run {} snapshot: {}", run.getId(), e.getMessage());
        }
        return snapshot;
    }

    private WorkflowRunSnapshot toSnapshot(WorkflowRun run) {
        List<WorkflowRunSnapshot.Step> steps = readSteps(run.getStepsJson()).stream()
                .map(s -> new WorkflowRunSnapshot.Step(s.index(), s.title(), s.instruction(),
                        s.status(), s.callId(), s.output(), s.failureMessage()))
                .toList();
        return new WorkflowRunSnapshot(
                run.getId().toString(), run.getWorkflowId(), run.getWorkflowName(),
                run.getStatus().name(), steps,
                run.getStartedAt().toEpochMilli(),
                run.getFinishedAt() == null ? null : run.getFinishedAt().toEpochMilli(),
                run.getFailureMessage(),
                run.getTriggerMessageId());
    }

    private List<StoredStep> readSteps(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<StoredStep>>() {});
        } catch (Exception e) {
            // An unreadable steps blob must not take the conversation down with it - the run
            // reports as having no steps, which renders as an empty table rather than an error.
            log.warn("[Workflow] unreadable steps json, treating run as empty: {}", e.getMessage());
            return List.of();
        }
    }

    private String writeSteps(List<StoredStep> steps) {
        try {
            return objectMapper.writeValueAsString(steps);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize workflow steps", e);
        }
    }

    private static String truncate(String message) {
        if (message == null || message.length() <= 2000) return message;
        return message.substring(0, 1997) + "...";
    }
}
