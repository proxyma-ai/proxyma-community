package ai.proxyma.core.registry.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One document PUSHED to a custom source (ConnectorType.CUSTOM) through the ingestion API or the
 * agent's saveToSource - what the source holds, as its sender described it.
 *
 * <p>This table is the custom source's SOURCE: its session lists these rows the way a GitHub session
 * lists a repository's issues, and fetches the body the row names. The indexing state of each one -
 * queued, indexed, failed and why - is its TrackedDocument, exactly as for an item of any other type;
 * nothing here duplicates it.
 *
 * <p>The body is not in the database: it is a file under the data root (IngestStore), up to the
 * upload limit for a file, which no row should carry.
 *
 * <p>In {@code ai.proxyma.core.registry.model} deliberately: both editions' datasource configs
 * already map this package, so a new table here cannot be missing from one of them.
 */
@Entity
@Table(name = "ingested_documents",
        uniqueConstraints = @UniqueConstraint(name = "uk_ingested_source_external",
                columnNames = {"connectorId", "externalId"}),
        indexes = @Index(name = "idx_ingested_source_received", columnList = "connectorId, receivedAt"))
public class IngestedDocument {

    public static final int EXTERNAL_ID_MAX = 200;
    public static final int TITLE_MAX = 500;
    public static final int URL_MAX = 2048;
    public static final int AUTHOR_MAX = 256;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID connectorId;

    /** The sender's own id for the document - what an upsert replaces and a delete names. */
    @Column(nullable = false, length = EXTERNAL_ID_MAX)
    private String externalId;

    @Column(nullable = false, length = TITLE_MAX)
    private String title;

    /** Where a search result links to - an http(s) address, or null for none. */
    @Column(length = URL_MAX)
    private String url;

    @Column(length = AUTHOR_MAX)
    private String author;

    /** When the document was created and last changed where it came from - the sender's word. */
    @Column
    private Instant sourceCreatedAt;

    @Column
    private Instant sourceUpdatedAt;

    /** text/markdown or text/plain for a text document; a file's own type for a file. */
    @Column(nullable = false)
    private String contentType;

    /** A file's name as it was uploaded; null for a text document. */
    @Column(length = 255)
    private String fileName;

    @Column(nullable = false)
    private long sizeBytes;

    /**
     * SHA-256 of everything that reaches the index - the body and the fields its header carries. The
     * listing hands it to the sync engine as ItemMetadata.CONTENT_HASH, so pushing the same document
     * again changes nothing and re-embeds nothing.
     */
    @Column(nullable = false, length = 64)
    private String contentHash;

    /** The sender's metadata map as JSON, or null. TEXT, never a large object - see SubAgentRunColumnsTest. */
    @Column(columnDefinition = "TEXT")
    private String metadataJson;

    /** The body's file name inside the source's directory (IngestStore) - one per version. */
    @Column(nullable = false, length = 100)
    private String storedAs;

    /** When this version arrived - what the listing reports as the item's last modification. */
    @Column(nullable = false)
    private Instant receivedAt;

    @Column(nullable = false)
    private Instant createdAt;

    public IngestedDocument() {
        this.createdAt = Instant.now();
        this.receivedAt = this.createdAt;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getConnectorId() { return connectorId; }
    public void setConnectorId(UUID connectorId) { this.connectorId = connectorId; }

    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public Instant getSourceCreatedAt() { return sourceCreatedAt; }
    public void setSourceCreatedAt(Instant sourceCreatedAt) { this.sourceCreatedAt = sourceCreatedAt; }

    public Instant getSourceUpdatedAt() { return sourceUpdatedAt; }
    public void setSourceUpdatedAt(Instant sourceUpdatedAt) { this.sourceUpdatedAt = sourceUpdatedAt; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }

    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }

    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }

    public String getStoredAs() { return storedAs; }
    public void setStoredAs(String storedAs) { this.storedAs = storedAs; }

    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
