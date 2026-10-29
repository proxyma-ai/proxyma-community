package ai.proxyma.core.chat.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A file one agent handed another (AgentFiles): copied from its own conversation's outputs or
 * attachments into the recipient's attachments, so the recipient's scripts read it - a CSV a worker
 * computed, a draft for its lead. Kept so a person sees where each file in a tree came from.
 */
@Entity
@Table(name = "agent_artifacts", indexes = {
        @Index(name = "idx_agent_artifacts_root", columnList = "rootConversationId, createdAt"),
})
public class AgentArtifact {

    public static final int NOTE_MAX = 500;

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, length = 64)
    private String rootConversationId;

    @Column(nullable = false, length = 64)
    private String fromConversationId;

    @Column(nullable = false, length = 64)
    private String toConversationId;

    /** The file's name in the recipient's attachments. */
    @Column(nullable = false, length = 255)
    private String fileName;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(length = NOTE_MAX)
    private String note;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getRootConversationId() { return rootConversationId; }
    public void setRootConversationId(String rootConversationId) { this.rootConversationId = rootConversationId; }
    public String getFromConversationId() { return fromConversationId; }
    public void setFromConversationId(String fromConversationId) { this.fromConversationId = fromConversationId; }
    public String getToConversationId() { return toConversationId; }
    public void setToConversationId(String toConversationId) { this.toConversationId = toConversationId; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
