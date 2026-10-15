package ai.proxyma.core.registry.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A credential that does ONE thing: push documents to, delete them from and list them in ONE custom
 * source, through /api/v1/ingest/sources/{that source}/documents.
 *
 * <p>Shaped like an MCP access token on purpose - {@code pxm_} and 64 hex characters, kept only as
 * its SHA-256, its first twelve characters shown, the token itself returned once - so the guardrail
 * rule and the log redaction that already recognize that shape cover this one too. It is NOT one:
 * it lives in its own table, which nothing that authenticates a bearer for any other endpoint reads,
 * so presented anywhere else it opens nothing (JwtAuthFilter on Enterprise Server, AccessGate on
 * Desktop and Home Server, both fall through to a 401).
 *
 * <p>Its owner is the source's owner when it was made ({@code userId}, null on the single-user
 * editions), and every use checks that the source still belongs to that person.
 */
@Entity
@Table(name = "ingest_tokens", indexes = {
        @Index(name = "idx_ingest_token_hash", columnList = "tokenHash", unique = true),
        @Index(name = "idx_ingest_token_source", columnList = "connectorId")
})
public class IngestToken {

    public static final int LABEL_MAX = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID connectorId;

    @Column
    private String userId;

    @Column(nullable = false, length = LABEL_MAX)
    private String label;

    @Column(nullable = false, length = 64)
    private String tokenHash;

    @Column(nullable = false, length = 12)
    private String tokenPrefix;

    @Column(nullable = false)
    private Instant createdAt;

    @Column
    private Instant lastUsedAt;

    public IngestToken() {
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getConnectorId() { return connectorId; }
    public void setConnectorId(UUID connectorId) { this.connectorId = connectorId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }

    public String getTokenPrefix() { return tokenPrefix; }
    public void setTokenPrefix(String tokenPrefix) { this.tokenPrefix = tokenPrefix; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
}
