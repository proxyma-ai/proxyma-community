package ai.proxyma.core.settings.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class PrivacySettings {

    public static final String PREFIX = "privacy";

    @JsonProperty("data-collection-allowed")
    private boolean dataCollectionAllowed = false;

    public boolean isDataCollectionAllowed() { return dataCollectionAllowed; }
    public void setDataCollectionAllowed(boolean v) { this.dataCollectionAllowed = v; }
}
