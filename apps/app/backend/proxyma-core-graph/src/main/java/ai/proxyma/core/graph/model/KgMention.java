package ai.proxyma.core.graph.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

// Where an entity was actually seen. This table is not optional and is not a later refinement.
//
// An answer drawn from a graph with no provenance is an assertion the user cannot check, and this
// product's whole retrieval story is that answers cite their sources. Every claim the graph can
// ever make has to end up pointing at a chunk of a real document.
//
// It is also the lifecycle hook. Entities are never deleted directly: a document's mentions go when
// the document does, and an entity left with no mentions is then purged. That ordering is the same
// DB-before-vectors discipline the ingest pipeline already follows, and it is what makes a bad
// extraction reversible per document rather than a permanent contamination of the graph.
//
// `connectorId` is denormalised onto the mention rather than reached through the document. Two
// reasons: this module holds no foreign key into tracked_documents by design (see the pom), and
// visibility is decided per connector - an entity is visible to a user if at least one of its
// mentions belongs to a connector they can see - so that column is on the hot path of every
// scoped read and should not need a join into another module's table to answer.
@Entity
@Table(name = "kg_mentions", indexes = {
    // The purge-and-recount path: every mention of one document, deleted together when it is
    // re-indexed or removed.
    @Index(name = "idx_kg_mention_document", columnList = "documentId"),
    // "is this entity still mentioned anywhere" after such a delete, and the per-entity source list
    // behind a citation.
    @Index(name = "idx_kg_mention_entity", columnList = "entityId"),
    // The per-connector corpus view, and the visibility filter.
    @Index(name = "idx_kg_mention_connector", columnList = "connectorId")
})
public class KgMention {

    public static final int MAX_DESCRIPTION_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID entityId;

    @Column(nullable = false)
    private UUID connectorId;

    @Column(nullable = false)
    private UUID documentId;

    // Which chunk of that document. Free-form rather than an integer because the ingest pipeline's
    // notion of a chunk reference is its own (see EmbeddingService's chunk index and section
    // metadata), and pinning an integer here would couple this table to a detail that module is
    // free to change.
    @Column(length = 256)
    private String chunkRef;

    // The exact surface form as it appeared in the text - "authsvc" where the entity is "Auth
    // Service". Kept because it is the evidence: a reader checking a citation needs to find the
    // string that was actually on the page, not the name the resolver settled on.
    @Column(length = KgEntity.MAX_NAME_LENGTH)
    private String surfaceForm;

    // The type and the one-sentence description THIS passage gave the entity. Kept on the mention,
    // not the entity, for the same reason the name shown is a mention's surface form: they are what
    // a document said, and a reader is told only what documents they can read said
    // (KnowledgeGraphService.displayOf).
    @Column(length = 64)
    private String entityType;

    @Column(length = MAX_DESCRIPTION_LENGTH)
    private String description;

    @Column(nullable = false)
    private Instant createdAt;

    public KgMention() {
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getEntityId() { return entityId; }
    public void setEntityId(UUID entityId) { this.entityId = entityId; }

    public UUID getConnectorId() { return connectorId; }
    public void setConnectorId(UUID connectorId) { this.connectorId = connectorId; }

    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID documentId) { this.documentId = documentId; }

    public String getChunkRef() { return chunkRef; }
    public void setChunkRef(String chunkRef) { this.chunkRef = chunkRef; }

    public String getSurfaceForm() { return surfaceForm; }
    public void setSurfaceForm(String surfaceForm) { this.surfaceForm = surfaceForm; }

    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
