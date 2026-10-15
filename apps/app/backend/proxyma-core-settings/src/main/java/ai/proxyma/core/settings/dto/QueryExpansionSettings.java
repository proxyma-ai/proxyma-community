package ai.proxyma.core.settings.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class QueryExpansionSettings {

    public static final String PREFIX = "query-expansion";

    @JsonProperty("enabled")
    private boolean enabled = false;
    @JsonProperty("provider")
    // EMPTY, not a provider name - a role holds a provider INSTANCE id. See
    // GraphSettings.provider for what this default cost and how long it went unnoticed.
    private String provider = "";
    @JsonProperty("model")
    private String model = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
/**
     * Optional per-role endpoint override. Blank - the default, and what every existing
     * deployment has - means "use the provider's own base URL and key".
     *
     * Exists for runtimes that serve ONE MODEL PER PROCESS (vLLM, llama.cpp, TGI, LocalAI), where
     * two roles on the same provider are two containers on two ports and a single provider-level
     * base URL cannot describe both. Ignored by providers whose adapter does not implement
     * withEndpoint() - the settings UI only offers these fields when it does.
     */
    @JsonProperty("base-url")
    private String baseUrl;

    @JsonProperty("api-key")
    private String apiKey;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
}
