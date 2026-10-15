package ai.proxyma.core.connector;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class ItemMetadata {

    private String id;
    private String title;
    private ConnectorType sourceType;
    private Instant lastModified;
    private String contentType;
    // Raw byte size of the source item, when the connector deals in real files (Files, SVN,
    // Bitbucket, SharePoint). Null for connectors with no such concept (Jira, Confluence, ...).
    private Long sizeBytes;
    private Map<String, String> extraProperties = new HashMap<>();

    public ItemMetadata() {}

    public ItemMetadata(String id, String title, ConnectorType sourceType,
                        Instant lastModified, String contentType) {
        this.id = id;
        this.title = sanitize(title);
        this.sourceType = sourceType;
        this.lastModified = lastModified;
        this.contentType = contentType;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    // Postgres text columns reject an embedded NUL byte outright (and other control
    // characters are junk in a title anyway) - strip them here since every connector's
    // title flows through this single setter before reaching TrackedDocument.title.
    public void setTitle(String title) { this.title = sanitize(title); }

    private static String sanitize(String value) {
        if (value == null) return null;
        StringBuilder sb = new StringBuilder(value.length());
        value.codePoints().forEach(cp -> {
            boolean isControlChar = cp < 0x20 || cp == 0x7F || (cp >= 0x80 && cp <= 0x9F);
            if (!isControlChar) sb.appendCodePoint(cp);
        });
        return sb.toString();
    }

    public ConnectorType getSourceType() { return sourceType; }
    public void setSourceType(ConnectorType sourceType) { this.sourceType = sourceType; }

    public Instant getLastModified() { return lastModified; }
    public void setLastModified(Instant lastModified) { this.lastModified = lastModified; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }

    public Map<String, String> getExtraProperties() { return extraProperties; }
    public void setExtraProperties(Map<String, String> extraProperties) { this.extraProperties = extraProperties; }
    /**
     * Extra key: a hash of what the item SAYS, for a source whose timestamps cannot be trusted to
     * move only when the content does (a web page with no Last-Modified is stamped "now"). An item
     * whose hash matches the indexed one is up to date whatever its timestamp says.
     */
    /** Who last changed the item, where the source says so in its listing (SVN). */
    public static final String AUTHOR = "author";

    public static final String CONTENT_HASH = "contentHash";

    public String getExtra(String key) { return extraProperties.get(key); }
    public void putExtra(String key, String value) { extraProperties.put(key, value); }
}
