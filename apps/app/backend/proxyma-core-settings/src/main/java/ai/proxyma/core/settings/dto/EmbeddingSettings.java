package ai.proxyma.core.settings.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class EmbeddingSettings {

    public static final String PREFIX = "embedding";

    @JsonProperty("provider")
    private String provider = ""; // see GraphSettings.provider - a role holds an instance id
    @JsonProperty("model")
    private String model = "";
    // How many batch-embed HTTP calls may be in flight to the provider at once, independent of
    // how many connectors are draining - the embedding provider's real capacity (e.g. Ollama's
    // NUM_PARALLEL) is a chunk/request-level limit, not a connector-level one. Restart-required,
    // like the other fields on this settings group.
    @JsonProperty("max-concurrent-requests")
    private int maxConcurrentRequests = 4;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public int getMaxConcurrentRequests() { return maxConcurrentRequests; }
    public void setMaxConcurrentRequests(int maxConcurrentRequests) { this.maxConcurrentRequests = maxConcurrentRequests; }
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
