package ai.proxyma.core.graph.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

// How far the graph has got with one document - the graph's OWN progress, kept apart from the
// document's indexing status on purpose.
//
// Extraction used to run inline in the ingest pipeline, which made the graph a passenger on
// vectorization: a document already indexed was never extracted until it was re-embedded, and a
// graph failure failed the document. With this table the graph decides for itself what it has
// and has not read, so switching it on for a source that is already indexed extracts what that
// source holds, and nothing about extraction can change a document's indexing status.
//
// One row per document, keyed by the document id. No foreign key into tracked_documents, for the
// same reason KgMention has none: this module reaches back into nobody (see the pom).
//
// A NEW TABLE, so ddl-auto creates it whole and the NOT NULL columns below are safe. A column
// added to it LATER is not - see RegisteredConnector.graphEnabled for what that costs.
@Entity
@Table(name = "kg_document_states", indexes = {
    // Every scan and every progress count is per connector, and so is the delete when a source
    // is unplugged.
    @Index(name = "idx_kg_docstate_connector_status", columnList = "connectorId, status")
})
public class KgDocumentState {

    public enum Status {
        /** Needs extracting and has not failed - never attempted, or interrupted by the quota. */
        PENDING,
        /** Being extracted now, or was when the process stopped. */
        EXTRACTING,
        /** Extracted from the chunks whose hash is recorded below. */
        DONE,
        /** The last attempt failed; retried until attempts reaches the limit. */
        FAILED
    }

    @Id
    private UUID documentId;

    @Column(nullable = false)
    private UUID connectorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    // The document's lastIndexedAt as it stood when this row was last written. A document
    // re-indexed since then has a different value, which is how the graph notices a re-index
    // without the ingest pipeline having to tell it.
    @Column
    private Instant sourceIndexedAt;

    // SHA-256 of the chunk texts extraction read. A re-index that produced the same chunks - a
    // new EMBEDDING model, say - matches it, and costs no extraction at all.
    @Column(length = 64)
    private String chunkHash;

    /** How many chunks this document has, known as soon as extraction starts. */
    @Column
    private int chunkCount;

    /**
     * How many of them have been read so far, updated AS IT GOES rather than at the end.
     *
     * <p>Without it a source shows "0 of 20 items read" for hours and looks broken. A document
     * counts as read only when every chunk is done, and a real one here has 581 of them at about a
     * minute each - so the only honest progress signal at that scale is the chunk, not the
     * document. Reported by the maintainer, watching a source that was working perfectly and
     * showing nothing.
     *
     * <p>A column ADDED to a table that already has rows, so it carries a default: ddl-auto cannot
     * add a NOT NULL column to a populated table, PostgreSQL refuses it, Hibernate logs the refusal
     * at WARN and carries on, and the column never appears. See RegisteredConnector.graphEnabled,
     * which cost this project a production outage for exactly that.
     */
    @Column(nullable = false, columnDefinition = "integer default 0")
    private int chunksDone;

    /**
     * How many chunks of the last extraction a model actually ANSWERED - "nothing here" included,
     * a refused, reset or timed-out call not. Written with DONE.
     *
     * <p><b>The evidence that a DONE row was really read.</b> Before 2026-09-16 an extraction no
     * call reached was recorded DONE exactly like a document the model read and found empty, and
     * the stored row cannot tell the two apart: same status, same hash, no mentions either way.
     * GraphModelUnreachableException stops new ones, but the rows already written stayed DONE with
     * an empty graph until somebody toggled the source. With this recorded, "DONE, no mentions, and
     * no answer on record" is something the worker can find and re-queue on its own - see
     * KgDocumentStateRepository.requeueDoneWithoutAnswers.
     *
     * <p>NULLABLE, with no default, on purpose. It is added to a table that already has rows, so it
     * cannot be NOT NULL (see chunksDone), and null is the honest value for those rows: "written
     * before anybody counted". A default of 0 would claim every old row was an outage; a default of
     * anything else would claim it was not.
     */
    @Column
    private Integer chunksAnswered;

    @Column
    private int attempts;

    @Column
    private Instant nextAttemptAt;

    @Column
    private Instant extractedAt;

    @Column(length = 2000)
    private String failureMessage;

    // The chunk indexes a read could not get a model to answer, comma-separated, when the document
    // was otherwise read: the next offer of the same chunks reads these and only these
    // (KnowledgeGraphService.refreshDocument). Null when none are missing.
    @Column(length = 2000)
    private String missingChunks;

    // Which version of the metadata reader last wrote this document's metadata edges, and from the
    // document as indexed when. Separate from the model read on purpose: metadata needs no model,
    // so a source with no extraction model still gets its exact edges, and a reader that learns a
    // new field reaches every document already read without a single model call
    // (GraphExtractionOrchestrator.metadataPass). Null: never written.
    @Column
    private Integer metadataVersion;

    @Column
    private Instant metadataIndexedAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public KgDocumentState() {
        this.updatedAt = Instant.now();
    }

    public KgDocumentState(UUID documentId, UUID connectorId) {
        this();
        this.documentId = documentId;
        this.connectorId = connectorId;
        this.status = Status.PENDING;
    }

    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID documentId) { this.documentId = documentId; }

    public UUID getConnectorId() { return connectorId; }
    public void setConnectorId(UUID connectorId) { this.connectorId = connectorId; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public Instant getSourceIndexedAt() { return sourceIndexedAt; }
    public void setSourceIndexedAt(Instant sourceIndexedAt) { this.sourceIndexedAt = sourceIndexedAt; }

    public String getChunkHash() { return chunkHash; }
    public void setChunkHash(String chunkHash) { this.chunkHash = chunkHash; }

    public int getChunkCount() { return chunkCount; }
    public void setChunkCount(int chunkCount) { this.chunkCount = chunkCount; }

    public int getChunksDone() { return chunksDone; }
    public void setChunksDone(int chunksDone) { this.chunksDone = chunksDone; }

    public Integer getChunksAnswered() { return chunksAnswered; }
    public void setChunksAnswered(Integer chunksAnswered) { this.chunksAnswered = chunksAnswered; }

    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }

    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }

    public Instant getExtractedAt() { return extractedAt; }
    public void setExtractedAt(Instant extractedAt) { this.extractedAt = extractedAt; }

    public Integer getMetadataVersion() { return metadataVersion; }
    public void setMetadataVersion(Integer metadataVersion) { this.metadataVersion = metadataVersion; }
    public Instant getMetadataIndexedAt() { return metadataIndexedAt; }
    public void setMetadataIndexedAt(Instant metadataIndexedAt) { this.metadataIndexedAt = metadataIndexedAt; }

    public String getMissingChunks() { return missingChunks; }
    public void setMissingChunks(String missingChunks) { this.missingChunks = missingChunks; }

    public String getFailureMessage() { return failureMessage; }
    public void setFailureMessage(String failureMessage) { this.failureMessage = failureMessage; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
