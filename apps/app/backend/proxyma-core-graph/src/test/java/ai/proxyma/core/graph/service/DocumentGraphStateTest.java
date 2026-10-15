package ai.proxyma.core.graph.service;

import ai.proxyma.core.graph.model.KgDocumentState.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static ai.proxyma.core.graph.service.DocumentGraphState.Need.LATER;
import static ai.proxyma.core.graph.service.DocumentGraphState.Need.NONE;
import static ai.proxyma.core.graph.service.DocumentGraphState.Need.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The one rule deciding whether the extraction worker reads a document.
 *
 * <p>Both directions are expensive when it is wrong. Too eager, and a settled corpus is re-extracted
 * - and billed - on every scan. Too lazy, and the bug this whole worker exists to fix comes back: a
 * source switched on after it was indexed never gets a graph.
 */
class DocumentGraphStateTest {

    private static final UUID DOC = UUID.randomUUID();
    private static final Instant INDEXED = Instant.parse("2026-09-15T10:00:00.123456Z");
    private static final Instant NOW_T = Instant.parse("2026-09-15T12:00:00Z");

    private static DocumentGraphState state(Status status, Instant indexedAt, int attempts, Instant nextAttemptAt) {
        return new DocumentGraphState(DOC, status, indexedAt, attempts, nextAttemptAt);
    }

    @Test
    @DisplayName("a document the graph has never seen is extracted - the already-indexed backfill")
    void neverSeenIsExtracted() {
        assertEquals(NOW, DocumentGraphState.need(null, INDEXED, NOW_T));
    }

    @Test
    @DisplayName("a document extracted from its current version is left alone")
    void doneIsLeftAlone() {
        assertEquals(NONE, DocumentGraphState.need(state(Status.DONE, INDEXED, 0, null), INDEXED, NOW_T));
    }

    @Test
    @DisplayName("a timestamp differing below the millisecond is the same version, not a re-index")
    void subMillisecondDifferenceIsNotAReindex() {
        Instant truncated = Instant.parse("2026-09-15T10:00:00.123Z");
        assertEquals(NONE, DocumentGraphState.need(state(Status.DONE, truncated, 0, null), INDEXED, NOW_T));
    }

    @Test
    @DisplayName("a document re-indexed since it was extracted is read again")
    void reindexedIsReadAgain() {
        Instant later = INDEXED.plus(Duration.ofHours(1));
        assertEquals(NOW, DocumentGraphState.need(state(Status.DONE, INDEXED, 0, null), later, NOW_T));
    }

    @Test
    @DisplayName("an extraction interrupted by a restart, or put back by the quota, is resumed")
    void interruptedIsResumed() {
        assertEquals(NOW, DocumentGraphState.need(state(Status.EXTRACTING, INDEXED, 0, null), INDEXED, NOW_T));
        assertEquals(NOW, DocumentGraphState.need(state(Status.PENDING, INDEXED, 0, null), INDEXED, NOW_T));
    }

    @Test
    @DisplayName("a failure waits out its retry delay, then is tried again")
    void failureWaitsThenRetries() {
        Instant due = NOW_T.plus(Duration.ofMinutes(5));
        assertEquals(LATER, DocumentGraphState.need(state(Status.FAILED, INDEXED, 1, due), INDEXED, NOW_T));
        assertEquals(NOW, DocumentGraphState.need(state(Status.FAILED, INDEXED, 1, NOW_T.minusSeconds(1)), INDEXED, NOW_T));
    }

    @Test
    @DisplayName("a document out of retries stops being offered - until it is re-indexed")
    void exhaustedStopsUntilReindexed() {
        DocumentGraphState exhausted = state(Status.FAILED, INDEXED, DocumentGraphState.MAX_ATTEMPTS, null);
        assertEquals(NONE, DocumentGraphState.need(exhausted, INDEXED, NOW_T));
        assertEquals(NOW, DocumentGraphState.need(exhausted, INDEXED.plus(Duration.ofMinutes(1)), NOW_T));
    }
}
