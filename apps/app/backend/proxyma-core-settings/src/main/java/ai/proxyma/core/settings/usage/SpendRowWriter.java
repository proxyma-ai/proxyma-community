package ai.proxyma.core.settings.usage;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes one monthly spend row in a transaction of its own, on the manager the spend tables
 * actually live under, and takes a second pass when a parallel call created the row first.
 *
 * <p>Found 2026-10-04, two sub-agents' first calls of the month on the same model: both found no
 * row, both inserted one, and the second insert broke the unique key. The services' own
 * {@code @Transactional(REQUIRES_NEW)} named no manager, so on Desktop it opened its transaction on
 * the PRIMARY datasource while the spend tables live on the chat one - the insert joined the
 * CALLER's chat transaction, the violation marked that rollback-only, and the sub-agent failed with
 * it, despite the try/catch promising that bookkeeping never breaks a call. Here the write commits
 * or fails on its own, and the second pass finds the row the other call just committed and adds to it.
 *
 * <p>AND ONE AT A TIME. Each row is read, added to and saved, so two calls updating the same row at
 * once both read the old figures and the later save erased the earlier call - seen the same day,
 * three parallel sub-agents filed as two calls. A deployment is one process, so a lock here, held
 * until the write has committed, is what makes the read-add-save whole. Each write is one small
 * statement pair; nothing waits on it for long.
 *
 * <p>{@code chatTransactionManager} is the spend tables' manager in both editions: the chat
 * datasource on Desktop and Home Server, the single one on Enterprise Server.
 */
@Component
public class SpendRowWriter {

    private final TransactionTemplate tx;

    public SpendRowWriter(@Qualifier("chatTransactionManager") PlatformTransactionManager manager) {
        this.tx = new TransactionTemplate(manager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Runs {@code write} in its own transaction; once more if a parallel call made the row first. */
    public synchronized void write(Runnable write) {
        try {
            tx.executeWithoutResult(s -> write.run());
        } catch (DataIntegrityViolationException raced) {
            tx.executeWithoutResult(s -> write.run());
        }
    }
}
