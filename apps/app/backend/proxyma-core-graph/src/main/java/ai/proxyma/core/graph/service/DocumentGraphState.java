package ai.proxyma.core.graph.service;

import ai.proxyma.core.graph.model.KgDocumentState;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * One document's graph progress, as the extraction worker reads it - and the single rule for
 * whether that document needs extracting.
 *
 * <p>The rule lives here rather than in the worker so there is exactly one of it, and so it can
 * be tested without a scheduler, a database or a model.
 */
public record DocumentGraphState(UUID documentId, KgDocumentState.Status status,
                                 Instant sourceIndexedAt, int attempts, Instant nextAttemptAt,
                                 Integer metadataVersion, Instant metadataIndexedAt) {

    public DocumentGraphState(UUID documentId, KgDocumentState.Status status, Instant sourceIndexedAt,
                              int attempts, Instant nextAttemptAt) {
        this(documentId, status, sourceIndexedAt, attempts, nextAttemptAt, null, null);
    }

    /** A failed extraction is tried this many times in all, then left for an operator. */
    public static final int MAX_ATTEMPTS = 3;

    /** How long a failed extraction waits before its next attempt. */
    public static final Duration RETRY_DELAY = Duration.ofMinutes(10);

    public enum Need {
        /** Extract it now. */
        NOW,
        /** A failure waiting out its retry delay - work exists, just not yet. */
        LATER,
        /** Nothing to do. */
        NONE
    }

    static DocumentGraphState of(KgDocumentState s) {
        return new DocumentGraphState(s.getDocumentId(), s.getStatus(), s.getSourceIndexedAt(),
                s.getAttempts(), s.getNextAttemptAt(), s.getMetadataVersion(), s.getMetadataIndexedAt());
    }

    /**
     * Whether a COMPLETED document indexed at {@code indexedAt} needs extracting.
     *
     * @param state the document's progress row, or null when the graph has never seen it
     */
    public static Need need(DocumentGraphState state, Instant indexedAt, Instant now) {
        if (state == null) return Need.NOW;
        // Re-indexed since the graph last looked: a new version, with a fresh retry budget.
        if (!sameInstant(state.sourceIndexedAt(), indexedAt)) return Need.NOW;
        return switch (state.status()) {
            case DONE -> Need.NONE;
            // EXTRACTING here means the process stopped mid-document - the worker never offers a
            // document it is extracting right now - and PENDING is exactly "not finished yet".
            case PENDING, EXTRACTING -> Need.NOW;
            case FAILED -> {
                if (state.attempts() >= MAX_ATTEMPTS) yield Need.NONE;
                if (state.nextAttemptAt() != null && state.nextAttemptAt().isAfter(now)) yield Need.LATER;
                yield Need.NOW;
            }
        };
    }

    /**
     * Whether a document's METADATA edges are due: never written, written from an older index of
     * the document, or by an older version of the reader. Independent of {@link #need}, which is
     * about the model's read - metadata needs no model.
     */
    public static boolean needsMetadata(DocumentGraphState state, Instant indexedAt, int readerVersion) {
        if (state == null) return true;
        if (!sameInstant(state.metadataIndexedAt(), indexedAt)) return true;
        return state.metadataVersion() == null || state.metadataVersion() < readerVersion;
    }

    /**
     * Instants compared to the millisecond. Both sides come out of a timestamp column, but H2 and
     * PostgreSQL do not keep the same precision, and a nanosecond of difference must not read as
     * a re-index - that would re-extract a whole corpus on every scan.
     */
    public static boolean sameInstant(Instant a, Instant b) {
        if (a == null || b == null) return a == b;
        return a.truncatedTo(ChronoUnit.MILLIS).equals(b.truncatedTo(ChronoUnit.MILLIS));
    }
}
