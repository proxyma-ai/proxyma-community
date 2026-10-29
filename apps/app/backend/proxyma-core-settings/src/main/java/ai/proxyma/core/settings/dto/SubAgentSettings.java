package ai.proxyma.core.settings.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

// Settings for delegated sub-agents: the cheap, short-lived workers the main chat agent hands a
// self-contained task to via the `delegate` tool, so bulk reading (a large diff, a long issue
// history, a sweep of search hits) does not consume the main agent's context or run at its
// per-token price.
//
// Its own PREFIX rather than fields on ChatSettings: SettingsService round-trips scalars through
// a properties file and does not handle nested objects, so a `subAgent` sub-object on ChatSettings
// would not persist. A sibling settings class is how every other grouped-settings block here is
// already modeled.
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SubAgentSettings {

    public static final String PREFIX = "sub-agent";

    // THERE IS NO SWITCH, since 2026-10-03 (the maintainer's request): sub-agents are part of how
    // the agent works, not an option beside it. `enabled` was a field here; a value stored under
    // it by an earlier version is read past (see @JsonIgnoreProperties on the class).
    //
    // EVERYTHING BELOW FOLLOWS THE AGENT UNTIL IT IS SET. An unset field - blank, or null - means
    // "the same as the agent's" (ChatSettings), read at the moment of use, so changing the agent's
    // model or its limits changes the sub-agents' with it. Setting one here detaches that one
    // field; clearing it follows again. Read the three numbers through the methods that take the
    // agent's settings, never through the getters, which say only what was stored.
    //
    // A sub-agent's writes go through the same permission mode and the same approval prompt as
    // the main agent's, so always-on cannot cause a write nobody agreed to.
    @JsonProperty("provider")
    // EMPTY, not a provider name - a role holds a provider INSTANCE id. See
    // GraphSettings.provider for what this default cost and how long it went unnoticed.
    private String provider = "";
    // Blank: the agent's own model. DefaultLlmGateway.bindingFor falls back to it whenever no
    // sub-agent model has been built.
    @JsonProperty("model")
    private String model = "";
    // Running out is not a wasted delegation - SubAgentService forces a resumable partial report
    // at the ceiling instead of discarding the work. Null follows the agent; 0 is no limit
    // (ChatSettings.NO_ITERATION_LIMIT), as it is for the agent.
    @JsonProperty("max-iterations")
    private Integer maxIterations;
    @JsonProperty("max-output-tokens")
    private Integer maxOutputTokens;
    // SubAgentService trims (elides the oldest tool results) once a call's real prompt size crosses
    // 80% of this - see its CONTEXT_TRIM_THRESHOLD. Not a memory setting despite the similar name:
    // a sub-agent's context is a throwaway in-memory list, never persisted and never condensed by
    // a summarizer call. Worth setting when the sub-agent's model has a smaller window than the
    // agent's.
    @JsonProperty("context-max-tokens")
    private Integer contextMaxTokens;
    // How long one sub-agent may run, in minutes - the ceiling, and what a `delegate` call gets
    // when it names no limit of its own (it may ask for less, never more). Both ways of running
    // have it: a sub-agent the main agent waits for holds the turn, and one in the background
    // spends with nobody watching. Time it spends waiting for the user's answer to an approval
    // or a question is not counted. Out of time, it is asked once for what it had found.
    // The one setting here the agent has no counterpart of, and since 2026-10-03 what bounds a
    // sub-agent whose steps are not: 30 minutes, the maintainer's default (it was 15).
    @JsonProperty("timeout-minutes")
    private int timeoutMinutes = 30;
    // How long the provider keeps a sub-agent's prompt cached (2026-10-07, maintainer's feedback:
    // "the sub agents must also support caching ... it doesn't have the option"). Blank follows the
    // agent's Prompt cache. AUTO means five minutes here - see ChatSettings.PromptCache.forSubAgent.
    @JsonProperty("prompt-cache")
    private String promptCache = "";

    // THE AGENT TREE (2026-10-28, the maintainer's design): every agent may deploy child agents, and
    // they theirs, down to this many levels below the conversation the user opened - 2 by default,
    // at most 5. A child at the limit is not offered deployAgent and is refused it at the call.
    @JsonProperty("max-depth")
    private Integer maxDepth;
    // Whether children of the same parent may message each other (tellAgent to a sibling). Off by
    // default: a parent hears from each child, and the children work apart.
    @JsonProperty("peer-messaging")
    private Boolean peerMessaging;

    public static final int DEFAULT_MAX_DEPTH = 2;
    public static final int MAX_DEPTH_CEILING = 5;

    public Integer getMaxDepth() { return maxDepth; }
    public void setMaxDepth(Integer maxDepth) { this.maxDepth = maxDepth; }
    public Boolean getPeerMessaging() { return peerMessaging; }
    public void setPeerMessaging(Boolean peerMessaging) { this.peerMessaging = peerMessaging; }

    /** How many levels of agents may sit below the conversation the user opened, 1 to 5. */
    public int maxDepth() {
        if (maxDepth == null) return DEFAULT_MAX_DEPTH;
        return Math.max(1, Math.min(MAX_DEPTH_CEILING, maxDepth));
    }

    /** Whether siblings may message each other. */
    public boolean peerMessaging() {
        return Boolean.TRUE.equals(peerMessaging);
    }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    /**
     * The provider a sub-agent runs on: its own, else the agent's. So "Same as the agent" and a
     * model of the sub-agent's own is that model on the agent's provider - which is how a cheaper
     * model from the same account is chosen without naming the account twice. Until 2026-10-03 a
     * blank provider with a model set built nothing, and Settings could not list the models to
     * pick from.
     */
    public String provider(ChatSettings agent) {
        return provider == null || provider.isBlank() ? agent.getProvider() : provider;
    }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public Integer getMaxIterations() { return maxIterations; }
    public void setMaxIterations(Integer maxIterations) { this.maxIterations = maxIterations; }

    public Integer getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(Integer maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }

    public Integer getContextMaxTokens() { return contextMaxTokens; }
    public void setContextMaxTokens(Integer contextMaxTokens) { this.contextMaxTokens = contextMaxTokens; }

    public int getTimeoutMinutes() { return timeoutMinutes; }
    public void setTimeoutMinutes(int timeoutMinutes) { this.timeoutMinutes = timeoutMinutes; }

    public String getPromptCache() { return promptCache; }
    public void setPromptCache(String promptCache) { this.promptCache = promptCache; }

    // WHICH MODELS AN AGENT MAY NAME when it deploys one (the maintainer's request, 2026-10-29: an
    // agent invented model names, and a fixed model or none was the only choice). ANY: any model a
    // configured provider lists; ONLY: just the entries of model-list; EXCEPT: all but those. An
    // entry is a provider instance id, for all its models, or "<instance id>|<model>", one per line.
    // What an agent may name is always also a model a provider lists (AgentModels). The default
    // model above, used when an agent names none, is the person's own choice and is not checked.
    public static final String MODELS_ANY = "ANY";
    public static final String MODELS_ONLY = "ONLY";
    public static final String MODELS_EXCEPT = "EXCEPT";

    @JsonProperty("model-rule")
    private String modelRule = MODELS_ANY;
    @JsonProperty("model-list")
    private String modelList = "";

    public String getModelRule() { return modelRule; }
    public void setModelRule(String modelRule) { this.modelRule = modelRule; }
    public String getModelList() { return modelList; }
    public void setModelList(String modelList) { this.modelList = modelList; }

    /** The rule as one of ANY, ONLY or EXCEPT; anything else reads as ANY. */
    public String modelRule() {
        String r = modelRule == null ? "" : modelRule.strip().toUpperCase(java.util.Locale.ROOT);
        return MODELS_ONLY.equals(r) || MODELS_EXCEPT.equals(r) ? r : MODELS_ANY;
    }

    /**
     * The lifetime a sub-agent's calls ask for: its own choice, else the agent's, resolved for a
     * sub-agent - so Auto, either way, is five minutes. Never no cache at all.
     */
    public ChatSettings.PromptCache promptCache(ChatSettings agent) {
        ChatSettings.PromptCache chosen = promptCache == null || promptCache.isBlank()
                ? agent.getPromptCache() : ChatSettings.PromptCache.fromValue(promptCache);
        return chosen.forSubAgent();
    }

    /**
     * How many steps a sub-agent may take, AS SET: its own number, else the agent's - no limit
     * ({@link ChatSettings#NO_ITERATION_LIMIT}) included. Read it through
     * {@link ChatSettings#iterationCeiling}.
     *
     * <p>An agent with no limit used NOT to hand that on, on the reasoning that a background
     * sub-agent works with nobody watching. Since 2026-10-03 it does (the maintainer's request -
     * "Same as the agent" read 50 under an unlimited agent): the time limit is what bounds a
     * sub-agent, and it always has one.
     */
    public int maxIterations(ChatSettings agent) {
        return maxIterations != null && maxIterations >= 0 ? maxIterations : agent.getMaxIterations();
    }

    /**
     * The most a sub-agent may write in one model call, AS SET: its own number, else the agent's.
     * {@link ChatSettings#FROM_MODEL} when neither is - the sub-agent's own model then decides,
     * which need not be the agent's.
     */
    public int maxOutputTokens(ChatSettings agent) {
        return isSet(maxOutputTokens) ? maxOutputTokens : agent.getMaxOutputTokens();
    }

    /** The context window a sub-agent is taken to have, AS SET - as above. */
    public int contextMaxTokens(ChatSettings agent) {
        return isSet(contextMaxTokens) ? contextMaxTokens : agent.getMemoryMaxTokens();
    }

    private static boolean isSet(Integer value) {
        return value != null && value > 0;
    }

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
