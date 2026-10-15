package ai.proxyma.core.settings.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class SaveFieldResponse {

    @JsonProperty("requiresRestart")
    private final boolean requiresRestart;

    public SaveFieldResponse(boolean requiresRestart) {
        this.requiresRestart = requiresRestart;
    }

    public boolean isRequiresRestart() { return requiresRestart; }
}
