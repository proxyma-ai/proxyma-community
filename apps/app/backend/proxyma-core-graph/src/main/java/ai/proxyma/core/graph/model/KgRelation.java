package ai.proxyma.core.graph.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

// An edge: subject -> predicate -> object. "Alice owns payments", "auth-service depends on
// postgres".
//
// This arrives in phase 1 rather than phase 2 because the extractor changed. The old plan split
// entities and relations across two phases because they came from two different extractors - cheap
// NER first, an LLM later. One LLM pass produces both, so splitting the schema would mean writing
// half of each extraction result to a table that does not exist yet.
//
// `predicate` is a plain String for the same reason KgEntity.type is: Postgres generates a CHECK
// constraint from @Enumerated(STRING) (LESSONS_LEARNED has this under its own heading, and it has
// bitten this codebase more than once), and an LLM's predicate vocabulary is open by construction.
// Constraining it here would turn "the model phrased a relation a new way" into a failed insert on
// an existing deployment.
//
// Time is on the edge, not the entity, and it is here from the first commit even though nothing
// populates it yet. "Alice owns payments" is true until it is not, and a graph that cannot express
// that answers questions about the present with facts from two years ago - confidently, and with a
// citation that makes it look verified. That is the single most likely source of confidently wrong
// answers from this feature, and retrofitting the columns later means backfilling every edge
// already extracted with nulls that cannot be distinguished from "genuinely open-ended".
@Entity
@Table(name = "kg_relations", indexes = {
    // Traversal outward from an entity - the common direction, and what graph_neighbours walks.
    @Index(name = "idx_kg_relation_subject", columnList = "subjectId"),
    // Traversal inward: "what depends on X" is the object side, and is exactly the question the
    // whole feature is justified by. Indexing only the subject would make the motivating query the
    // slow one.
    @Index(name = "idx_kg_relation_object", columnList = "objectId"),
    // The lifecycle hook, mirroring kg_mentions: an edge belongs to the document it was extracted
    // from, and goes when that document is re-indexed or deleted.
    @Index(name = "idx_kg_relation_document", columnList = "documentId"),
    // Visibility: every traversal keeps only edges from sources the asker can read (SEC-51), and a
    // source's own view and delete select by it.
    @Index(name = "idx_kg_relation_connector", columnList = "connectorId")
})
public class KgRelation {

    // Cap on the extracted description. Long enough to carry the sentence the edge was drawn from,
    // short enough that a runaway generation cannot write an essay per edge into the row.
    public static final int MAX_DESCRIPTION_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID subjectId;

    @Column(nullable = false)
    private UUID objectId;

    @Column(nullable = false, length = 128)
    private String predicate;

    // The extractor's own sentence for this edge. Kept because the predicate alone is lossy -
    // "depends on" does not say how, and a reader judging whether the edge is right needs the
    // wording it came from.
    @Column(length = MAX_DESCRIPTION_LENGTH)
    private String description;

    // Provenance, denormalised the same way and for the same reasons as KgMention's: no foreign
    // key into another module's table, and connector-scoped visibility is on the hot path of every
    // read.
    @Column(nullable = false)
    private UUID connectorId;

    @Column(nullable = false)
    private UUID documentId;

    @Column(length = 256)
    private String chunkRef;

    // Always 1: one row is one document stating the edge, however many of its passages repeat it.
    // It counted passages, and chunks overlap by design, so one sentence lying across a chunk
    // boundary was two corroborations. Corroboration is now the number of DISTINCT DOCUMENTS stating
    // a triple, counted when a traversal groups its rows (KnowledgeGraphService.assemble).
    @Column(nullable = false)
    private int weight;

    // WHEN, where a source's metadata says (since 2026-10-13): a state holds from validFrom until
    // validTo - an assignee, until somebody else was assigned - and an event happened at
    // occurredAt - a status change. All null for what a model read from text, which states no time.
    // A traversal follows only what holds now and is not an event (KnowledgeGraphService.current);
    // the timeline shows all of it.
    @Column
    private Instant validFrom;

    @Column
    private Instant validTo;

    @Column
    private Instant occurredAt;

    @Column(nullable = false)
    private Instant createdAt;

    public KgRelation() {
        this.createdAt = Instant.now();
        this.weight = 1;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getSubjectId() { return subjectId; }
    public void setSubjectId(UUID subjectId) { this.subjectId = subjectId; }

    public UUID getObjectId() { return objectId; }
    public void setObjectId(UUID objectId) { this.objectId = objectId; }

    public String getPredicate() { return predicate; }
    public void setPredicate(String predicate) { this.predicate = predicate; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public UUID getConnectorId() { return connectorId; }
    public void setConnectorId(UUID connectorId) { this.connectorId = connectorId; }

    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID documentId) { this.documentId = documentId; }

    public String getChunkRef() { return chunkRef; }
    public void setChunkRef(String chunkRef) { this.chunkRef = chunkRef; }

    public int getWeight() { return weight; }
    public void setWeight(int weight) { this.weight = weight; }


    public Instant getValidFrom() { return validFrom; }
    public void setValidFrom(Instant validFrom) { this.validFrom = validFrom; }
    public Instant getValidTo() { return validTo; }
    public void setValidTo(Instant validTo) { this.validTo = validTo; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
