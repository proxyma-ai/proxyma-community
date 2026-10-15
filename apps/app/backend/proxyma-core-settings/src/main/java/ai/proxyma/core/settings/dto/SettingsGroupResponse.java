package ai.proxyma.core.settings.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;

import java.util.Map;

public class SettingsGroupResponse {

    private final Map<String, Object> fields;

    public SettingsGroupResponse(Map<String, Object> fields) {
        this.fields = fields;
    }

    @JsonAnyGetter
    public Map<String, Object> getFields() { return fields; }
}
