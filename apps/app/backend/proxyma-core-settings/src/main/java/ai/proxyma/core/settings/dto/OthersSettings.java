package ai.proxyma.core.settings.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class OthersSettings {

    public static final String PREFIX = "others";

    // Note: the server port is no longer a setting - it is fixed via server.port in
    // application-{desktop,server}.properties.

    @JsonProperty("save-markdown")
    private boolean saveMarkdown = false;

    public boolean isSaveMarkdown() { return saveMarkdown; }
    public void setSaveMarkdown(boolean v) { this.saveMarkdown = v; }
}
