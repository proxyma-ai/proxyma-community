package ai.proxyma.core.settings.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

import java.time.Duration;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatSettings {

    public static final String PREFIX = "chat";

    @JsonProperty("provider")
    private String provider = ""; // see GraphSettings.provider - a role holds an instance id
    @JsonProperty("model")
    private String model = "";
    // Concise and 50 steps are the maintainer's defaults, set 2026-10-03.
    @JsonProperty("tone")
    private Tone tone = Tone.CONCISE;

    /**
     * An output ceiling or a context window nobody set: the MODEL's own, as its provider states it
     * (LlmProviderFactory.modelLimits), since 2026-10-03 at the maintainer's request.
     *
     * <p>Both were fixed numbers before - 8k and 128k, then 64k and 200k for a day - and a fixed
     * number is wrong for most models in one direction or the other: a ceiling above the model's
     * is a refused request, and a window larger than the model's overflows before anything is
     * condensed. Read neither field as a limit without resolving it: the output ceiling through
     * LlmProviderFactory.outputCeiling (the gateway does), the window through
     * LlmGateway.contextWindow.
     */
    public static final int FROM_MODEL = 0;
    /** The window assumed for a model whose provider states none. */
    public static final int FALLBACK_CONTEXT_WINDOW = 200000;
    /**
     * The ceiling sent to a protocol that REQUIRES one (Anthropic) when the model's own is not
     * stated. Everywhere else an unstated ceiling is simply not sent, and the provider applies
     * its own.
     */
    public static final int FALLBACK_MAX_OUTPUT_TOKENS = 64000;

    @JsonProperty("max-output-tokens")
    private int maxOutputTokens = FROM_MODEL;
    // An iteration is one model call plus its tool round trip, so the ceiling is the worst case a
    // single question can cost. It was 10, on that reasoning; at the ceiling the reader is now
    // asked whether to go on (ChatService.askToContinue), so a higher one no longer means a turn
    // that ends on a half-finished job.
    //
    // 0 IS "NO LIMIT", since 2026-09-27 (the maintainer's request) - offered for the main agent
    // only, never for a sub-agent, which runs unattended where nobody can press Stop. Read it
    // through iterationCeiling(), never raw: a raw 0 used to mean one model call with its tool
    // calls silently not followed.
    @JsonProperty("max-iterations")
    private int maxIterations = DEFAULT_MAX_ITERATIONS;

    public static final int NO_ITERATION_LIMIT = 0;
    public static final int DEFAULT_MAX_ITERATIONS = 50;

    /** The loop's ceiling for a configured value: {@link #NO_ITERATION_LIMIT} (or below) is none. */
    public static int iterationCeiling(int configured) {
        return configured <= NO_ITERATION_LIMIT ? Integer.MAX_VALUE : configured;
    }
    // The context window - see FROM_MODEL above.
    @JsonProperty("memory-max-tokens")
    private int memoryMaxTokens = FROM_MODEL;
    // How long a provider keeps the agent's prompt prefix cached. Only protocols that take an
    // explicit cache instruction act on it - Anthropic today; OpenAI-compatible, Azure and Gemini
    // cache automatically and ignore it. AUTO by default since 2026-10-07 (maintainer's feedback),
    // which is never less than the five minutes every deployment had before.
    @JsonProperty("prompt-cache")
    private PromptCache promptCache = PromptCache.AUTO;
    // How long one SEARCH tool call may run, in minutes (maintainer's request, 2026-10-04 - five
    // minutes and a setting). Every other tool keeps ChatService.TOOL_CALL_TIMEOUT_SECONDS: a search
    // across many sources with reranking runs for half a minute on a busy machine, which the 60
    // seconds meant for one slow endpoint cut off. Read it through searchTimeoutSeconds().
    @JsonProperty("search-timeout-minutes")
    private int searchTimeoutMinutes = DEFAULT_SEARCH_TIMEOUT_MINUTES;

    public static final int DEFAULT_SEARCH_TIMEOUT_MINUTES = 5;

    /** The search tools' limit in seconds; a stored value below one minute reads as the default. */
    public int searchTimeoutSeconds() {
        return (searchTimeoutMinutes >= 1 ? searchTimeoutMinutes : DEFAULT_SEARCH_TIMEOUT_MINUTES) * 60;
    }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public Tone getTone() { return tone; }
    public void setTone(Tone tone) { this.tone = tone; }

    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }

    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }

    public int getMemoryMaxTokens() { return memoryMaxTokens; }
    public void setMemoryMaxTokens(int memoryMaxTokens) { this.memoryMaxTokens = memoryMaxTokens; }


    public PromptCache getPromptCache() { return promptCache; }
    public void setPromptCache(PromptCache promptCache) { this.promptCache = promptCache; }

    public int getSearchTimeoutMinutes() { return searchTimeoutMinutes; }
    public void setSearchTimeoutMinutes(int searchTimeoutMinutes) { this.searchTimeoutMinutes = searchTimeoutMinutes; }

    /**
     * The agent's prompt-cache lifetime.
     *
     * <p>ONE_HOUR costs more per write - Anthropic bills a one-hour write at twice the base input
     * rate against 1.25x for five minutes - and pays for itself only when a conversation pauses for
     * longer than five minutes between turns and then carries on.
     *
     * <p>AUTO REPLACED OFF on 2026-10-07 (maintainer's feedback: "Change Off to Auto ... determine
     * which option is better during a conversation, but won't go below the option"). Auto picks
     * five minutes or one hour as the conversation goes - {@link #forTurn} - and never goes below
     * five minutes, so there is no setting that sends no cache instruction any more. A stored "off"
     * reads as AUTO.
     */
    public enum PromptCache {
        AUTO("auto"),
        FIVE_MINUTES("5m"),
        ONE_HOUR("1h");

        /** The one lifetime Auto steps up from; Anthropic's shortest. */
        static final Duration SHORT = Duration.ofMinutes(5);
        /** The longest; a pause past it finds nothing cached whichever was asked for. */
        static final Duration LONG = Duration.ofHours(1);

        private final String value;

        PromptCache(String value) { this.value = value; }

        @JsonValue
        public String value() { return value; }

        /** Lenient: an unknown stored value - "off" among them - reads as the default, AUTO. */
        @JsonCreator
        public static PromptCache fromValue(String value) {
            for (PromptCache c : values()) {
                if (c.value.equalsIgnoreCase(value) || c.name().equalsIgnoreCase(value)) return c;
            }
            return AUTO;
        }

        /**
         * The lifetime the agent's next calls ask for. A fixed choice is itself; AUTO is ONE_HOUR
         * when what will read this prefix next is likely to come more than five minutes from now,
         * and FIVE_MINUTES otherwise:
         *
         * <ul>
         *   <li><b>Background work is still running</b> - a sub-agent whose report wakes the agent
         *       when it is done, which at a sub-agent's pace is minutes, not seconds.</li>
         *   <li><b>The reader's last pause was longer than five minutes and shorter than an
         *       hour</b> - they are working at a pace a five-minute cache always misses and an hour
         *       catches. A pause longer than an hour says nothing either lifetime would catch.</li>
         * </ul>
         *
         * @param backgroundWork whether anything will wake this conversation's agent later
         * @param sincePreviousTurn how long the reader took to come back this time; null if unknown
         */
        public PromptCache forTurn(boolean backgroundWork, Duration sincePreviousTurn) {
            if (this != AUTO) return this;
            if (backgroundWork) return ONE_HOUR;
            if (sincePreviousTurn != null && sincePreviousTurn.compareTo(SHORT) > 0
                    && sincePreviousTurn.compareTo(LONG) <= 0) {
                return ONE_HOUR;
            }
            return FIVE_MINUTES;
        }

        /**
         * The lifetime a SUB-AGENT's calls ask for. A fixed choice is itself; AUTO is five minutes,
         * because a sub-agent is a burst of calls seconds apart that ends when its report comes
         * back - nothing reads its prefix an hour later.
         */
        public PromptCache forSubAgent() {
            return this == AUTO ? FIVE_MINUTES : this;
        }
    }

    public enum Tone {
        CONCISE("concise", 0.3, 15, 0.85),
        NORMAL("normal", 0.7, 40, 0.90),
        CREATIVE("creative", 1.0, 80, 0.95);

        private final String value;
        private final double temperature;
        private final int topK;
        private final double topP;

        Tone(String value, double temperature, int topK, double topP) {
            this.value = value;
            this.temperature = temperature;
            this.topK = topK;
            this.topP = topP;
        }

        @JsonValue
        public String value() { return this.value; }
        public double getTemperature() { return temperature; }
        public int getTopK() { return topK; }
        public double getTopP() { return topP; }

        @JsonCreator
        public static Tone fromValue(String value) {
            for (Tone t : values()) {
                if (t.value.equalsIgnoreCase(value)) return t;
            }
            throw new IllegalArgumentException("Unknown tone: " + value);
        }
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
