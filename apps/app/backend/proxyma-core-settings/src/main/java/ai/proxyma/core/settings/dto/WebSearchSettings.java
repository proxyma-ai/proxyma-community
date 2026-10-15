package ai.proxyma.core.settings.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class WebSearchSettings {

    public static final String PREFIX = "web-search";

    @EncryptedField
    @JsonProperty("api-key")
    private String apiKey;

    @JsonProperty("search-engine-id")
    private String searchEngineId;

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getSearchEngineId() { return searchEngineId; }
    public void setSearchEngineId(String searchEngineId) { this.searchEngineId = searchEngineId; }

    @JsonIgnore
    public boolean isApiKeyConfigured() { return apiKey != null && !apiKey.isBlank(); }

    @JsonIgnore
    public boolean isConfigured() {
        return isApiKeyConfigured() && searchEngineId != null && !searchEngineId.isBlank();
    }
}
