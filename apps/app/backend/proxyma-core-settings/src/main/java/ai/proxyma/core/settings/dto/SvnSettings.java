package ai.proxyma.core.settings.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SvnSettings {

    public static final String PREFIX = "svn";

    // The server and the credential are not settings any more - they are CONNECTIONS
    // (ConnectorConnection), one per server, each with its own token. What is left here is how
    // sources of this type are read, whichever connection they go through.

    @JsonProperty("sync-interval-seconds")
    private int syncIntervalSeconds = 3600;

    // Comma-separated file extensions to index, REPLACING the connector's built-in list when set
    // (blank = the built-in one). Organizations keep domain-specific text formats in Subversion -
    // configuration, interface descriptions, calibration files - and they index well as text/plain;
    // with a hard-coded list they were skipped and the sync reported itself clean anyway.
    @JsonProperty("indexed-extensions")
    private String indexedExtensions;

    public int getSyncIntervalSeconds() { return syncIntervalSeconds; }
    public void setSyncIntervalSeconds(int s) { this.syncIntervalSeconds = s; }

    public String getIndexedExtensions() { return indexedExtensions; }
    public void setIndexedExtensions(String e) { this.indexedExtensions = e; }
}
