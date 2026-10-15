package ai.proxyma.core.chat.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

// A file the user attached when submitting Feedback (screenshot, log excerpt, ...). Stored in the
// database (not on local disk) since Feedback itself is used from both desktop (H2, single
// instance) and server (Postgres, possibly multiple replicas) - a filesystem path would break the
// server case. See `data` for why the bytes are in the row. Unidirectional FK only
// (no back-collection on Feedback) - nothing reads "this feedback's attachments" by loading the
// Feedback entity itself, only via FeedbackAttachmentRepository, so there's no need to pay for a
// lazy collection on every Feedback read. @OnDelete CASCADE (DB-level, baked into the FK at
// creation since this table is brand new) means deleting a Feedback row - via AdminFeedbackController
// or ChatService's conversation-deletion cascade - automatically drops its attachments too,
// without needing to touch either of those call sites.
@Entity
@Table(name = "feedback_attachment")
public class FeedbackAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "feedback_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Feedback feedback;

    @Column(nullable = false)
    private String fileName;

    private String contentType;

    private long sizeBytes;

    // IN THE ROW, not @Lob. On PostgreSQL @Lob byte[] is an `oid` - a reference into
    // pg_largeobject that deleting the row does not delete - so every screenshot and log tail ever
    // attached outlived its feedback for good, ON DELETE CASCADE included. bytea on PostgreSQL,
    // binary varying on H2; the length is FeedbackController's 20 MB per feedback, which no one
    // file can exceed. The telemetry service's own FeedbackAttachment stores BYTEA for the same reason.
    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(nullable = false, length = 20971520)
    private byte[] data;

    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Feedback getFeedback() { return feedback; }
    public void setFeedback(Feedback feedback) { this.feedback = feedback; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }
    public byte[] getData() { return data; }
    public void setData(byte[] data) { this.data = data; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
