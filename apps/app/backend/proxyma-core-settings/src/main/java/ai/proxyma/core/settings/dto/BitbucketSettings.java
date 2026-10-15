package ai.proxyma.core.settings.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class BitbucketSettings {

    public static final String PREFIX = "bitbucket";

    // The server and the credential are not settings any more - they are CONNECTIONS
    // (ConnectorConnection), one per server, each with its own token. What is left here is how
    // sources of this type are read, whichever connection they go through.

    @JsonProperty("sync-interval-seconds")
    private int syncIntervalSeconds = 3600;


    public int getSyncIntervalSeconds() { return syncIntervalSeconds; }
    public void setSyncIntervalSeconds(int s) { this.syncIntervalSeconds = s; }


}
