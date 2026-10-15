package ai.proxyma.core.settings.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class JiraSettings {

    public static final String PREFIX = "jira";

    // The server and the credential are not settings any more - they are CONNECTIONS
    // (ConnectorConnection), one per server, each with its own token. What is left here is how
    // sources of this type are read, whichever connection they go through.

    @JsonProperty("sync-interval-seconds")
    private int syncIntervalSeconds = 3600;


    // Comma-separated custom field DISPLAY NAMES to leave out of an indexed issue, matched
    // case-insensitively. Every Jira instance carries its own internal-bookkeeping custom fields,
    // and they land in every document this connector writes; the ones generic to all of them (Rank,
    // Epic Link, the development panel) are built into JiraConnector and need no entry here.
    //
    // Names and not customfield_NNNNN ids: an id identifies a DIFFERENT field on every server, so a
    // list of ids configured against one Jira hides an arbitrary field on the next.
    @JsonProperty("skip-custom-fields")
    private String skipCustomFields;

    public int getSyncIntervalSeconds() { return syncIntervalSeconds; }
    public void setSyncIntervalSeconds(int s) { this.syncIntervalSeconds = s; }


    public String getSkipCustomFields() { return skipCustomFields; }
    public void setSkipCustomFields(String s) { this.skipCustomFields = s; }
}
