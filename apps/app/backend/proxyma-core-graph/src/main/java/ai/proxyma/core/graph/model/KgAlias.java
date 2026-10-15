package ai.proxyma.core.graph.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

// The other names one entity goes by: "Auth Service", "authsvc", "AUTH".
//
// This is resolution step 1 - exact and normalised match - and it is the cheapest and most reliable
// of the three steps, which is why it gets a table of its own rather than being inferred each time.
// Get resolution wrong and the graph is worse than nothing: it looks authoritative and answers
// confidently from a fragment of what it should have connected.
//
// ONE ALIAS, ONE ENTITY - PER SOURCE (since 2026-10-13). The unique constraint was on the
// normalized alias alone, so an alias coined in one person's private document resolved everybody's
// later mention of that string to it: a name somebody else could not read decided what their own
// documents' graph called a thing. An alias is now the source's own, consulted only when writing
// that source's documents, and offered to a query only from sources the asker can read. Within one
// source an alias still names one entity, so step 1 stays unambiguous.
@Entity
@Table(name = "kg_aliases",
    uniqueConstraints = @UniqueConstraint(name = "uk_kg_alias_norm_connector",
        columnNames = {"normalizedAlias", "connectorId"}),
    indexes = {
        @Index(name = "idx_kg_alias_entity", columnList = "entityId"),
        @Index(name = "idx_kg_alias_connector", columnList = "connectorId")
    })
public class KgAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID entityId;

    // The source whose document named it. Nullable only so an older row does not stop the table
    // being updated; such a row matches no source and so resolves nothing.
    @Column
    private UUID connectorId;

    @Column(nullable = false, length = KgEntity.MAX_NAME_LENGTH)
    private String alias;

    @Column(nullable = false, length = KgEntity.MAX_NAME_LENGTH)
    private String normalizedAlias;

    @Column(nullable = false)
    private Instant createdAt;

    public KgAlias() {
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getEntityId() { return entityId; }
    public void setEntityId(UUID entityId) { this.entityId = entityId; }

    public UUID getConnectorId() { return connectorId; }
    public void setConnectorId(UUID connectorId) { this.connectorId = connectorId; }

    public String getAlias() { return alias; }
    public void setAlias(String alias) { this.alias = alias; }

    public String getNormalizedAlias() { return normalizedAlias; }
    public void setNormalizedAlias(String normalizedAlias) { this.normalizedAlias = normalizedAlias; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
