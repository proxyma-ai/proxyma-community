package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.model.WorkflowRun;
import ai.proxyma.core.chat.repository.WorkflowRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Fails, once at startup, every run still marked RUNNING.
 *
 * A run is driven by a thread in this process. If the row says RUNNING at startup, that thread
 * died with the previous process - the run is not going to continue, and nothing else will ever
 * update the row. Left alone it renders as a spinner that never resolves: the same bug the
 * tool-call rows already had (LESSONS_LEARNED, "Chat Tool Call UI Stuck In Progress After Final
 * Response"), and one that cannot appear in testing, because nothing restarts mid-run there.
 *
 * <p>Runs at startup rather than on read, deliberately. A read-time check would have to guess
 * whether a RUNNING row belongs to THIS process or a dead one, and the only honest way to know
 * that is to have swept the table when the process began.
 *
 * <p><b>This is why a single-process assumption is written down rather than assumed.</b> Two app
 * instances sharing one database would have each one's startup fail the other's live runs. Server
 * mode has one container per environment (see CLAUDE.md's "one environment per host"), so that
 * does not arise today - but scaling out horizontally would need this replaced by a heartbeat or
 * an owner column, and finding that out from a user's stopped workflow would be a bad way to
 * learn it.
 */
@Component
class WorkflowRunReconciler {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunReconciler.class);

    private static final String MESSAGE = "Interrupted by a restart.";

    private final WorkflowRunRepository repository;

    WorkflowRunReconciler(WorkflowRunRepository repository) {
        this.repository = repository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional("chatTransactionManager")
    public void failInterruptedRuns() {
        try {
            List<WorkflowRun> stale = repository.findByStatus(WorkflowRun.Status.RUNNING);
            if (stale.isEmpty()) return;
            for (WorkflowRun run : stale) {
                run.setStatus(WorkflowRun.Status.FAILED);
                run.setFinishedAt(Instant.now());
                run.setFailureMessage(MESSAGE);
                // Steps are deliberately NOT rewritten here. Whichever step was RUNNING keeps that
                // status inside the blob, and the run's own FAILED status plus this message is what
                // the table reads. Rewriting every step to SKIPPED would erase the record of how
                // far the run actually got, which is the one useful thing left after a crash.
            }
            repository.saveAll(stale);
            log.warn("[Workflow] failed {} run(s) left RUNNING by a previous process", stale.size());
        } catch (Exception e) {
            // A boot listener that throws takes startup with it - see DesktopIntegrationService.
            log.warn("[Workflow] could not reconcile interrupted runs: {}", e.getMessage());
        }
    }
}
