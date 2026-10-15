package ai.proxyma.core.graph.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

// One thing the corpus talks about: a person, a system, a team, a product.
//
// `type` is a plain String, NOT an @Enumerated(STRING) enum, and that is deliberate. PostgreSQL
// generates a CHECK constraint from @Enumerated(STRING) (see LESSONS_LEARNED, "Postgres Also
// Generates CHECK Constraints From @Enumerated(STRING)"), which would turn "the NER model emitted
// a label we had not seen" into a schema migration and a failed insert on an existing deployment.
// The label set is whatever the extractor produces - spaCy's PERSON/ORG/GPE/PRODUCT today, an LLM's
// looser vocabulary in phase 2 - so it is an open set by nature and must be stored as one.
//
// `canonicalName` is what gets shown. `normalizedName` is what gets matched on, and exists as its
// own column rather than being computed per query so the unique index below can do the work: it is
// the whole of resolution step 1 (exact and normalised match), and doing it in SQL is what keeps
// ingest from re-reading every entity in scope for each candidate.
//
// Indexes follow the two queries phase 1 actually makes:
//  - uk_kg_entity_norm_type: the resolution lookup, and the constraint that makes "same normalized
//    name, same type" mean one row. Unique so a race between two documents mentioning the same
//    thing fails one insert rather than quietly creating a duplicate entity - a duplicate is not a
//    visible error, it is a graph that answers from half of what it should have connected.
//  - idx_kg_entity_last_seen: the per-connector "what is in this corpus" view, newest first.
@Entity
@Table(name = "kg_entities",
    uniqueConstraints = @UniqueConstraint(name = "uk_kg_entity_norm_type",
        columnNames = {"normalizedName", "type"}),
    indexes = {
        @Index(name = "idx_kg_entity_last_seen", columnList = "lastSeenAt"),
        // A query typed without diacritics ("da nang") finds the entity by this. See GraphNames.fold.
        @Index(name = "idx_kg_entity_folded", columnList = "foldedName"),
        @Index(name = "idx_kg_entity_squashed", columnList = "squashedName"),
        @Index(name = "idx_kg_entity_initials", columnList = "initials")
    })
public class KgEntity {

    // Cap on a stored name. Long enough for a real organization or product name, short enough that
    // a runaway extraction cannot write a paragraph into this column. Anything longer is truncated
    // at the extractor rather than rejected - a clipped name is still a usable entity, and failing
    // a whole document's ingest over one bad span is not.
    public static final int MAX_NAME_LENGTH = 512;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = MAX_NAME_LENGTH)
    private String canonicalName;

    @Column(nullable = false, length = MAX_NAME_LENGTH)
    private String normalizedName;

    // GraphNames.UNTYPED ("") when the extractor could not tell, never NULL: two NULLs are distinct
    // to a unique index on H2 and PostgreSQL alike, so the constraint above did not stop two
    // workers creating the same untyped name twice - after which every lookup of that name threw.
    // Not "UNKNOWN" either: a word there would read as a classification. Lower case (GraphNames.type).
    // NULL is still read as untyped, for a row written before this.
    @Column(length = 64)
    private String type;

    // The normalized name with its marks taken off (GraphNames.fold). Matched by queries only -
    // resolution never merges on it.
    @Column(length = MAX_NAME_LENGTH)
    private String foldedName;

    // GraphNames.squash and GraphNames.initials of the name: the keys a query matches spelling
    // variants and acronyms by, and the traversal finds synonyms by. Never merged on.
    @Column(length = MAX_NAME_LENGTH)
    private String squashedName;

    @Column(length = 16)
    private String initials;

    // Which embedding model this entity's name vector was made with (EntityVectorService in the
    // registry module) - a model change re-embeds every entity, a batch per pass. Null: not yet.
    @Column(length = 64)
    private String vectorKey;

    @Column(nullable = false)
    private Instant firstSeenAt;

    @Column(nullable = false)
    private Instant lastSeenAt;

    public KgEntity() {
        Instant now = Instant.now();
        this.firstSeenAt = now;
        this.lastSeenAt = now;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getCanonicalName() { return canonicalName; }
    public void setCanonicalName(String canonicalName) { this.canonicalName = canonicalName; }

    public String getNormalizedName() { return normalizedName; }
    public void setNormalizedName(String normalizedName) { this.normalizedName = normalizedName; }

    public String getType() { return type; }

    /** The type, or null when there is none - what a reader is shown. */
    public String typeOrNull() { return type == null || type.isEmpty() ? null : type; }

    public String getSquashedName() { return squashedName; }
    public void setSquashedName(String squashedName) { this.squashedName = squashedName; }
    public String getVectorKey() { return vectorKey; }
    public void setVectorKey(String vectorKey) { this.vectorKey = vectorKey; }
    public String getInitials() { return initials; }
    public void setInitials(String initials) { this.initials = initials; }

    /** Every key derived from the normalized name, set together so none is ever stale. */
    public void setDerivedKeys(String normalizedName) {
        this.foldedName = ai.proxyma.core.graph.service.GraphNames.fold(normalizedName);
        this.squashedName = ai.proxyma.core.graph.service.GraphNames.squash(normalizedName);
        this.initials = ai.proxyma.core.graph.service.GraphNames.initials(normalizedName);
    }

    public String getFoldedName() { return foldedName; }
    public void setFoldedName(String foldedName) { this.foldedName = foldedName; }
    public void setType(String type) { this.type = type; }

    public Instant getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(Instant firstSeenAt) { this.firstSeenAt = firstSeenAt; }

    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
}
