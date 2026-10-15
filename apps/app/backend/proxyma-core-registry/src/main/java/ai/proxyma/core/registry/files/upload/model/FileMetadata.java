package ai.proxyma.core.registry.files.upload.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "FILE_METADATA", indexes = {
    // Every lookup this table serves is by one of these two - a re-upload's row, a source's files,
    // a user's uploads and quota - and without them each was a full scan.
    @Index(name = "idx_file_storage_path", columnList = "storagePath"),
    @Index(name = "idx_file_uploader", columnList = "uploadedByUserId")
})
public class FileMetadata {

    /**
     * The longest stored path, and the longest name or folder. They had no length, so the column
     * was 255 characters while the path it holds is {root}/users/{uuid}/uploads/{folder}/{subfolders}/{name}
     * - a folder dropped a few levels deep failed its row's INSERT after the bytes were already on
     * disk, leaving a file nothing tracked or counted. TrackedDocument.sourceId, the same value, is
     * 2048.
     */
    public static final int STORAGE_PATH_MAX = 2048;
    public static final int NAME_MAX = 1024;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = NAME_MAX)
    private String originalName;

    @Column(nullable = false, length = STORAGE_PATH_MAX)
    private String storagePath;

    @Column(nullable = false)
    private String mimeType;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(nullable = false)
    private Instant uploadedAt;

    /**
     * Who uploaded it, or NULL for the single local user.
     *
     * <p><b>NULLABLE SINCE 0.8.0, and it had to be.</b> Uploads were server-only, where every
     * uploader is an account and this is never null; the home server uploads too and has no
     * accounts at all, representing its one user as a null owner exactly as
     * {@code RegisteredConnector.userId} already does for every connector in that edition.
     *
     * <p>The {@code nullable = false} it used to carry did not fail a build, a test or a review -
     * it failed at the first actual upload, with a 500 and a constraint violation naming a column
     * no user has heard of. Every unit test around this passed, both editions compiled, and the
     * feature was completely broken. It is the reason the upload path is now exercised against a
     * running instance rather than reasoned about.
     *
     * <p><b>An upgrade does not relax this.</b> {@code ddl-auto=update} adds columns and never
     * alters or drops a constraint, so a home server that ran a pre-0.8.0 build has the NOT NULL
     * already on disk and keeps it - uploads there 500 until the install is replaced, which is the
     * documented path for this project (see CLAUDE.md: every install is a first install). Server
     * deployments are unaffected either way, since they never write a null here.
     */
    @Column
    private String uploadedByUserId;

    @Column(length = NAME_MAX)
    private String folder;

    // Subfolder path within `folder`, empty/null for root-level files. Preserves the
    // structure of a recursively dropped folder (e.g. "subdir/nested").
    @Column(length = NAME_MAX)
    private String relativePath;

    // True once the physical bytes at storagePath have been deliberately deleted after successful
    // embedding (see SyncService's post-COMPLETED purge step) - the row itself is kept for
    // duplicate-detection and display purposes. findDuplicates() must NOT filter these out just
    // because storageService.fileExists(storagePath) is now false.
    @Column(nullable = false)
    private boolean bytesPurged;

    public UUID getId() { return id; }
    public String getOriginalName() { return originalName; }
    public void setOriginalName(String n) { this.originalName = n; }
    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String p) { this.storagePath = p; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String m) { this.mimeType = m; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long s) { this.sizeBytes = s; }
    public Instant getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(Instant t) { this.uploadedAt = t; }
    public String getUploadedByUserId() { return uploadedByUserId; }
    public void setUploadedByUserId(String u) { this.uploadedByUserId = u; }
    public String getFolder() { return folder; }
    public void setFolder(String f) { this.folder = f; }
    public String getRelativePath() { return relativePath; }
    public void setRelativePath(String p) { this.relativePath = p; }
    public boolean isBytesPurged() { return bytesPurged; }
    public void setBytesPurged(boolean b) { this.bytesPurged = b; }
}
