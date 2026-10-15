package ai.proxyma.core.settings.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Knowledge-graph extraction: its own model role, toggled and configured exactly like
 * query-expansion and reranking.
 *
 * <p>A separate role rather than reusing the chat model is the point. Extraction is
 * input-heavy and output-light - chunks in, a structured object out - and it runs once per
 * chunk at ingest across an entire corpus, so it is the classic case for pointing at a cheaper
 * tier than the one answering the user. Sharing the chat model would bill graph construction at
 * conversation prices.
 *
 * <p><b>Disabled by default, and that is not a formality.</b> Enabling this makes an LLM call per
 * chunk on every document ingested, which can double or triple ingest cost - see
 * proxyma-docs/architecture/knowledge-graph.md. It is a decision an operator makes with the
 * number in front of them, never a default they discover on an invoice.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GraphSettings {

    public static final String PREFIX = "graph";

    /**
     * The master switch. Off means no extraction runs at all, whatever any per-connector opt-in
     * says - the two are ANDed, and this one is the deployment-wide answer to "may this cost
     * money at all".
     */
    @JsonProperty("enabled")
    private boolean enabled = false;

    /**
     * EMPTY, not a provider name. A role holds a provider INSTANCE id - a uuid - and this
     * defaulted to the pre-instance protocol string "ollama", which resolves to nothing on every
     * deployment that has ever existed. The cost was not theoretical: a fresh install logged
     * "is not a configured provider" for each role at every start, and the fix applied at the time
     * was to downgrade that line from ERROR to WARN rather than to stop shipping a default that
     * cannot work. Found again on 2026-09-06 by configuring the knowledge graph on a live install,
     * where the same stale default made extraction silently unavailable.
     *
     * Blank means "nothing selected", which is what a fresh install actually is, and which
     * requireProvider already reports as "No provider selected for this role".
     */
    @JsonProperty("provider")
    private String provider = "";

    @JsonProperty("model")
    private String model = "";

    /**
     * GraphRAG's gleaning rounds: after the first extraction pass, the model is asked whether it
     * missed anything, up to this many times.
     *
     * <p>Exposed rather than hard-coded because it is the most direct cost lever in the whole
     * feature - each round is another full call over the same chunk, so 1 gleaning is roughly
     * double the extraction bill and 2 is triple, for diminishing recall. 0 disables gleaning
     * and is a legitimate choice on a large corpus.
     */
    @JsonProperty("gleanings")
    private int gleanings = 1;

    /**
     * Output ceiling per extraction call. Extraction returns a structured list of entities and
     * relations, so this bounds how much one chunk can produce - a runaway generation on one
     * pathological chunk should cost one capped call, not an open-ended one.
     */
    @JsonProperty("max-output-tokens")
    private int maxOutputTokens = 4096;

    /**
     * Seconds one extraction call may take before it is abandoned and the chunk contributes
     * nothing.
     *
     * <p>Was a hardcoded 120 with no way to change it, which is fine for a hosted model and not
     * fine for a local one. Measured on 2026-09-06 against a 9B REASONING model on a laptop: the
     * model returned exactly the right JSON every time, and spent about ninety percent of its
     * output budget on thinking tokens first - 6,655 characters of reasoning for 667 characters of
     * answer, 82 seconds for two sentences. Documents landed either side of the fixed ceiling, so
     * one in four produced nothing while the other three succeeded, with no setting to raise.
     *
     * <p>Raise it for a slow or reasoning model; lower it on a large corpus where a chunk that has
     * not answered in twenty seconds is not going to.
     */
    @JsonProperty("call-timeout-seconds")
    private int callTimeoutSeconds = 120;

    /**
     * Optional per-role endpoint override. Blank - the default, and what every existing
     * deployment has - means "use the provider's own base URL and key". Same purpose and same
     * caveat as the identical fields on QueryExpansionSettings: it exists for runtimes that
     * serve one model per process, and is ignored by adapters that do not implement
     * withEndpoint().
     */
    /**
     * Whether hybrid search also ranks passages by the knowledge graph - the graph channel, fused
     * beside the semantic and keyword lists (KnowledgeGraphService.graphPassages). OFF by default
     * and kept so until it is shown to help on this deployment's own questions: the research found
     * graph retrieval helps connection questions and can hurt plain lookups, and every search pays
     * for the walk. Independent of {@link #enabled}, which is about extracting the graph at all.
     */
    @JsonProperty("search-channel")
    private boolean searchChannel = false;

    @JsonProperty("base-url")
    private String baseUrl;

    @JsonProperty("api-key")
    private String apiKey;

    public boolean isEnabled() { return enabled; }
    public boolean isSearchChannel() { return searchChannel; }
    public void setSearchChannel(boolean searchChannel) { this.searchChannel = searchChannel; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getCallTimeoutSeconds() { return callTimeoutSeconds; }
    public void setCallTimeoutSeconds(int callTimeoutSeconds) { this.callTimeoutSeconds = callTimeoutSeconds; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public int getGleanings() { return gleanings; }
    public void setGleanings(int gleanings) { this.gleanings = gleanings; }

    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
}
