package ai.proxyma.core.indexstore;

import ai.proxyma.core.settings.dto.EmbeddingSettings;
import ai.proxyma.core.settings.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.function.IntSupplier;

/**
 * How many batch-embed calls may be in flight to the embedding provider at once - Settings >
 * Retrieval > "Max concurrent embedding requests" - shared by every caller of either vector store.
 *
 * <p>LIVE, and in every edition, since 2026-10-01. It was a Semaphore sized once at startup inside
 * PgVectorStoreService alone, so the setting said "Restart required" on the server edition and did
 * nothing whatever on Desktop and Home Server, whose Chroma store never read it (found from the
 * maintainer's question why no restart prompt appeared). The limit is re-read at most every
 * {@link #REFRESH_MS} ms and applies to the next call to start; calls already running finish.
 */
@Component
public class EmbeddingConcurrency {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConcurrency.class);
    static final long REFRESH_MS = 5_000;

    private final IntSupplier configured;
    private int limit;
    private int inFlight;
    private long readAt;

    // @Autowired because there are two constructors - the second is the test's - and Spring only
    // picks one by itself when a class declares exactly one. Without it Desktop did not boot.
    @Autowired
    public EmbeddingConcurrency(SettingsService settingsService) {
        this(() -> settingsService.getSettings(EmbeddingSettings.PREFIX, EmbeddingSettings.class)
                .getMaxConcurrentRequests());
    }

    EmbeddingConcurrency(IntSupplier configured) {
        this.configured = configured;
        this.limit = 1;
        this.readAt = Long.MIN_VALUE;
    }

    /** Blocks until a slot is free under the current limit. */
    public synchronized void acquire() {
        refresh();
        boolean interrupted = false;
        while (inFlight >= limit) {
            try {
                wait(REFRESH_MS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
            refresh();
        }
        inFlight++;
        if (interrupted) Thread.currentThread().interrupt();
    }

    public synchronized void release() {
        if (inFlight > 0) inFlight--;
        notifyAll();
    }

    synchronized int limit() {
        return limit;
    }

    private void refresh() {
        long now = System.currentTimeMillis();
        if (readAt != Long.MIN_VALUE && now - readAt < REFRESH_MS) return;
        readAt = now;
        try {
            int next = Math.max(1, configured.getAsInt());
            if (next != limit) {
                log.info("[Embedding] at most {} concurrent embedding request(s)", next);
                limit = next;
                notifyAll();
            }
        } catch (Exception e) {
            // Unreadable settings keep the limit in force rather than lifting it.
            log.debug("[Embedding] could not read the concurrency setting: {}", e.getMessage());
        }
    }
}
