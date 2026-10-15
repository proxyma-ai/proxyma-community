package ai.proxyma.core.settings.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What the guardrails check, and what they do about it.
 *
 * <p>TWO LAYERS, AND ONLY ONE OF THEM COSTS ANYTHING. The deterministic rules run always: they are
 * regular expressions and a deny-list, they need no model, no network and no credential, and they
 * work on an air-gapped install. The LLM classifier is a second layer that is OFF by default and
 * has no fallback to the main chat model - see {@code LlmRole.GUARDRAIL}. Turning it on adds a
 * model call per user message and another per answer, which is real latency and real money on
 * every single turn, so it is a decision an operator makes rather than one they inherit.
 *
 * <p>WHY THE DEFAULTS ARE WHAT THEY ARE. Input violations BLOCK; output and retrieval violations
 * REDACT. Blocking an answer the user is already reading is worse than removing the part that
 * should not be there, and a blocked input has cost nothing yet - no provider call has been made
 * and no turn has been written. The three are separately configurable because deployments
 * disagree about this and every position is defensible.
 *
 * <p>THREE STAGES, NOT TWO. Input and output are what people picture when they hear "guardrail",
 * and between them they miss everything the agent RETRIEVES - which, in a product built around
 * retrieval, is most of what the model reads. The retrieval stage is deterministic only and is
 * described on {@link #retrievalEnabled}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GuardrailSettings {

    public static final String PREFIX = "guardrail";

    /** Whether a decision is applied or only recorded. */
    public enum Mode {
        /** Decisions take effect: a block refuses, a redaction cuts. */
        ENFORCE,
        /**
         * Decisions are recorded and NOT applied. Every message goes through untouched, and what
         * would have happened shows up in the metrics.
         *
         * <p>This is how a threshold gets chosen without experimenting on people. Turning a
         * guardrail straight on means finding out what it refuses by having it refuse somebody,
         * and a similarity floor is not a number anyone can pick correctly in advance - it depends
         * on the embedding model and on the traffic. Bedrock has the same idea as an action of
         * NONE, which detects and reports without blocking.
         */
        MONITOR
    }

    /**
     * ENFORCE by default.
     *
     * <p>The alternative - shipping in MONITOR so nothing can break - sounds safer and is worse:
     * a guardrail that is on and doing nothing, with no signal that it is doing nothing, is the
     * failure this module keeps designing against. Enabling a guardrail should guard.
     */
    @JsonProperty("mode")
    private Mode mode = Mode.ENFORCE;

    /** What to do when a stage finds something. */
    public enum Action {
        /** Refuse the turn. The user sees why; nothing is sent to a provider, or nothing is kept. */
        BLOCK,
        /** Replace the offending span with a marker and carry on with the rest. */
        REDACT
    }

    @JsonProperty("input-enabled")
    private boolean inputEnabled = true;

    @JsonProperty("output-enabled")
    private boolean outputEnabled = true;

    @JsonProperty("on-input-violation")
    private Action onInputViolation = Action.BLOCK;

    @JsonProperty("on-output-violation")
    private Action onOutputViolation = Action.REDACT;

    /**
     * Check what TOOLS RETURN, before the agent loop hands it back to the model.
     *
     * <p>THE STAGE THE OTHER TWO CANNOT COVER. A search hit, a fetched page, a Jira comment, a
     * connector document and an MCP tool's result all become model context without passing through
     * either of the stages above: nobody typed them, and the model has not written anything yet.
     * For a product whose whole job is retrieval that was the largest gap in this module, and it
     * is the one Azure ships a separate Prompt Shield for.
     *
     * <p>DETERMINISTIC ONLY. The classifier and the examples corpus never run here, whatever their
     * own switches say - see {@code GuardrailService}. A turn makes ONE input check and ONE output
     * check, and it makes as many retrieval checks as the agent makes tool calls, which on a
     * research-shaped turn is a dozen or more. A per-tool-call completion would multiply both the
     * latency and the bill of every agentic turn by an amount nobody could predict from this
     * screen. Patterns are microseconds and cost nothing, so they run on every one of them.
     *
     * <p>On by default. The rules that apply here are narrow on purpose - credentials, injection
     * phrasing and the operator's own deny-list, but NOT personal data and NOT server paths, both
     * of which belong in retrieved content - so the default is a guardrail that guards without
     * standing in the way of the retrieval it is guarding.
     */
    @JsonProperty("retrieval-enabled")
    private boolean retrievalEnabled = true;

    /**
     * REDACT by default, and the reason differs from the output stage's.
     *
     * <p>BLOCK here does not refuse the turn. It withholds ONE tool result: the agent is told the
     * result was held back and why, and goes on to its next call. That is the right shape - the
     * person asking did not write the offending text and usually has no idea it exists, so
     * ending their turn over it would be punishing them for a document.
     *
     * <p>REDACT is still the better default, because a poisoned page is usually one planted
     * sentence inside a page that genuinely answers the question. Cutting the sentence keeps the
     * answer; withholding the page loses it. An operator who would rather lose the page can say
     * so.
     *
     * <p>ONE EXCEPTION, AND IT IS NOT CONFIGURABLE: an injection finding in retrieved content
     * always withholds the whole result. Every other rule matches a span that IS the problem, so
     * cutting it solves it; an injection pattern matches the opening of a sentence, and cutting
     * that leaves the instruction behind it standing. See {@code GuardrailService.decide}.
     */
    @JsonProperty("on-retrieval-violation")
    private Action onRetrievalViolation = Action.REDACT;

    /**
     * API keys, tokens and private keys. On by default in all three stages: the input case is a
     * user pasting a credential into a chat that is then persisted and shipped to a provider, the
     * retrieval case is one sitting in a document, a commit or a ticket comment the agent reads,
     * and the output case is a model repeating one back.
     *
     * <p>The retrieval case is the common one in practice, and it is the only stage that stops the
     * credential before it is in the context window rather than after.
     */
    @JsonProperty("detect-secrets")
    private boolean detectSecrets = true;

    /**
     * Email addresses, phone numbers, and payment card numbers that pass a Luhn check.
     *
     * <p>Messages and answers only. Retrieved content is excluded and that is deliberate: a
     * support ticket or a customer record is a name, an email and a phone number by construction,
     * so applying this to retrieval would blank the answer to most of the questions people ask
     * this product. The output stage sees the same data at the point it would reach a reader.
     */
    @JsonProperty("detect-pii")
    private boolean detectPii = true;

    /**
     * Messages and retrieved content. Phrases whose whole purpose is to move the model off its
     * instructions - "ignore all previous instructions", "you are now DAN", an injected
     * {@code <system>} block. Never on answers: a model writing one of these is quoting.
     *
     * <p>RETRIEVAL is the case this matters most for. Typed into the chat box it is a user
     * attacking a system they are already talking to; arriving inside a fetched page or a ticket
     * description it is INDIRECT prompt injection, planted for somebody who will never see it.
     *
     * <p>This is a SPEED BUMP and is documented as one everywhere it is described. Detecting
     * prompt injection by pattern catches the copy-pasted attempt and not the paraphrase; the
     * defense that actually holds is that retrieved content is never trusted as instruction,
     * which the default system prompt states in its ABSOLUTE RULES and which this sits beneath.
     */
    @JsonProperty("detect-prompt-injection")
    private boolean detectPromptInjection = true;

    /**
     * Output only. Absolute server paths that reached the answer text.
     *
     * <p>Overlaps deliberately with {@code SensitivePaths.scrub()}, which is the primary control
     * and runs at the client boundary. This one is a second look at the same class of leak from
     * the other end, and finding something here means the boundary missed it.
     */
    @JsonProperty("detect-path-leak")
    private boolean detectPathLeak = true;

    /**
     * Operator-supplied terms, one per line. Matched case-insensitively on word boundaries, so
     * "acme" does not fire on "acmestore".
     *
     * <p>The obvious use is a project code name or an unannounced customer, which is a policy
     * question no shipped rule can answer.
     */
    @JsonProperty("deny-terms")
    private String denyTerms;

    /**
     * Block a message that closely matches a known example - a jailbreak, a past attack, a query
     * that must never be answered.
     *
     * <p>Independent of the classifier, and that is the point: one embedding and one
     * nearest-neighbour lookup, no completion, no per-turn model cost. It needs an embedding model
     * configured (the same one indexing uses) and nothing else.
     *
     * <p>Off by default because an empty corpus makes it inert anyway, and because a threshold
     * this decides on its own deserves a deliberate switch.
     */
    @JsonProperty("examples-enabled")
    private boolean examplesEnabled = false;

    /**
     * How close a message has to be to a known example before it is refused.
     *
     * <p>STRICT ON PURPOSE, and stricter than the rules-retrieval floor beside it. These two
     * numbers look alike and do opposite jobs: the rules floor decides whether a clause is worth
     * SHOWING TO A MODEL, so erring low costs a call. This one IS the decision - nothing else
     * looks at the message afterwards - so erring low blocks people. Guardrails AI's
     * DetectJailbreak defaults to a cosine distance of 0.2, which is this number at 0.8.
     *
     * <p>Embedding-model dependent, like every cosine threshold. Tune it against messages the
     * deployment actually sees rather than trusting the default.
     */
    @JsonProperty("examples-threshold")
    private Double examplesThreshold = 0.8;

    // -- The optional LLM layer ------------------------------------------------

    @JsonProperty("llm-enabled")
    private boolean llmEnabled = false;

    @JsonProperty("provider")
    private String provider;

    @JsonProperty("model")
    private String model;

    /**
     * Small on purpose. The classifier is asked for a verdict and a category list, not for an
     * explanation, and a ceiling is the cheapest guard against a model that decides to write an
     * essay on every turn.
     */
    @JsonProperty("max-output-tokens")
    private Integer maxOutputTokens = 256;

    /**
     * Refuse the turn when the classifier does not return a verdict, instead of letting it through.
     *
     * <p>OFF BY DEFAULT, and the default is the interesting half. Fail-open means a provider
     * outage silently removes a control; fail-closed means it takes the product down. Neither is
     * obviously right, which is exactly why it is a setting - but only one of them can be the
     * default, and shipping the one that turns somebody's guardrail-model timeout into "Proxyma is
     * broken" is not it. The deterministic rules run either way and need no provider.
     *
     * <p>It covers every way a verdict fails to arrive: a timeout, an unreachable provider, a
     * reply with no JSON in it, and a model that says "allow: false" while naming nothing. That
     * last one is a judgement call - the model did answer - but what it produced is not something
     * anyone can act on, and a deployment that asked to stop rather than guess should stop.
     *
     * <p>Only reachable when the classifier is enabled. A deployment with no guardrail model
     * configured never calls it, so this cannot lock anybody out of a product they have not
     * pointed at a provider.
     */
    @JsonProperty("fail-closed")
    private boolean failClosed = false;

    /**
     * Keep the matched text on a recent decision, so the Activity panel can show what fired.
     *
     * <p>OFF BY DEFAULT, AND THE DEFAULT IS THE SECURITY CONTROL. Everything else here defaults to
     * whatever protects a deployment best; this one defaults to the value that protects it, which
     * is off. An existing install must not start retaining credentials in memory because somebody
     * applied an update - the operator who wants this has to come and ask for it, on the screen
     * that tells them what it costs.
     *
     * <p>WHY IT EXISTS AT ALL. Without it the Activity panel says "3 matches, category secret",
     * and an operator tuning a policy cannot tell a real credential from a rule that fires on
     * every build number. The only way to find out was to reproduce the message by hand and guess
     * at what had matched, which is worse than the feature in every respect including this one -
     * a guess is repeated until it is right, and each attempt is another copy of the text.
     *
     * <p>WHAT TURNING IT ON MEANS, stated plainly because the settings screen says the same thing:
     * matched spans are held in a 200-entry in-memory window that a restart empties. They are
     * never written to the database, never logged, never sent to telemetry, and truncated to 120
     * characters. Anyone who can open the settings screen can read them, which on a server install
     * is any SUPER_ADMIN or ADMIN - {@code SecurityConfig} gates {@code /api/v1/guardrail/**} at
     * {@code hasAnyRole("SUPER_ADMIN", "ADMIN")}, matched to who the screen is shown to rather than
     * to who may change the setting. Writing it is SUPER_ADMIN only; READING the spans it captures
     * is not. See {@code GuardrailEvidence}.
     *
     * <p>It changes NOTHING about what is detected, blocked or redacted. A deployment behaves
     * identically with this on and off; the only difference is whether a decision that has already
     * been made can be explained afterwards.
     */
    @JsonProperty("capture-matched-text")
    private boolean captureMatchedText = false;

    /**
     * Put an unguessable marker in the system prompt, and refuse any answer that repeats it.
     *
     * <p>Rebuff's idea, and the only check here that detects a system-prompt leak RATHER THAN
     * guessing at one. Every other approach to "did the model reveal its instructions" is a
     * pattern or a classifier looking at the answer and forming an opinion. This one is exact: the
     * marker exists nowhere in the world except this deployment's system prompt, so an answer
     * containing it can only have come from that prompt. No false positives, and no judgement.
     *
     * <p>WHAT IT DOES NOT DO. It catches a VERBATIM leak - the model reciting or quoting its
     * instructions. A model that paraphrases them, or is talked into describing them in its own
     * words, never emits the marker and this never fires. It is a tripwire on the specific attack
     * that works most often, not a guarantee about the class.
     *
     * <p>Off by default because it needs a per-deployment token, which is generated on first use
     * rather than shipped - see {@link #canaryToken}.
     */
    @JsonProperty("canary-enabled")
    private boolean canaryEnabled = false;

    /**
     * The marker itself. Generated once, then left alone.
     *
     * <p>STABLE BY NECESSITY, not by preference. A conversation's system prompt is resolved at its
     * FIRST turn and persisted with it, so a token that changed - on restart, per conversation, per
     * turn - would leave every conversation started before the change carrying a marker nothing
     * looks for any more. The check would go on passing and mean nothing, which is this module's
     * favourite failure mode.
     *
     * <p>Written back into settings the first time the canary is switched on.
     *
     * <p>An {@link EncryptedField} since 2026-10-14 (settings-core-03, -04), which is all it takes:
     * stored encrypted, returned to the settings page as the placeholder like every annotated field,
     * and carried in an export's sealed envelope rather than its readable half. It is not a
     * credential, but knowing it buys a false alarm on demand, and it used to be readable by every
     * ADMIN and written in clear into a "no secrets" export.
     */
    @EncryptedField
    @JsonProperty("canary-token")
    private String canaryToken;

    /**
     * How many refused messages inside {@link #repeatWindowMinutes} put someone in a cooldown.
     *
     * <p>ZERO MEANS OFF, and that is the default. Every other layer judges one message on its own
     * merits, which makes the fortieth jailbreak attempt in five minutes look exactly like the
     * first - and the difference between those two is most of the signal. This is the only thing
     * here that sees a pattern rather than a message.
     *
     * <p>Off by default because the wrong-way failure is worse than the thing it prevents: a
     * support engineer pasting five error logs that each contain a bearer token is doing their job,
     * and locking them out for ten minutes punishes the most legitimate heavy user in the building.
     * A deployment that wants this picks its own number.
     *
     * <p>Counted per USER where there is one and per conversation otherwise, which is the honest
     * limit of it in desktop mode: there is one user, so it is really per conversation, and a new
     * conversation starts clean. It is a speed bump against somebody working through a list by
     * hand. It is not a defense against anything automated, and it does not survive a restart.
     */
    @JsonProperty("repeat-threshold")
    private Integer repeatThreshold = 0;

    /** The window the count is taken over. Slides continuously, not a fixed bucket. */
    @JsonProperty("repeat-window-minutes")
    private Integer repeatWindowMinutes = 15;

    /**
     * How long a cooldown lasts. During it, messages are refused BEFORE any check runs.
     *
     * <p>That ordering is deliberate and is most of the value: the case this exists for is somebody
     * sending many messages quickly, and a cooldown that still paid for a classifier call on each
     * one would make the attempt cheaper to mount than to withstand.
     */
    @JsonProperty("repeat-cooldown-minutes")
    private Integer repeatCooldownMinutes = 10;

    /**
     * What the classifier is asked to look for, one per line. Free text rather than a fixed enum:
     * the categories a deployment cares about are its own, and a fixed list would be a promise
     * that the model treats them as more than words in a prompt.
     *
     * <p>A category may be followed by INDENTED lines, which are read as sample phrases for it:
     *
     * <pre>violence
     *     hurting someone on purpose
     *     how do I make a weapon at home
     * self-harm</pre>
     *
     * <p>Bedrock pairs each denied topic with up to five phrases of about 100 characters and
     * reports that it measurably beats a bare definition; the same ceilings are applied here, in
     * {@code LlmGuardrailClassifier}. A flat list with no indentation parses exactly as it always
     * did, which is required rather than nice - this field ships with a default and deployments
     * have edited it.
     */
    @JsonProperty("llm-categories")
    private String llmCategories = "violence\nself-harm\nsexual content involving minors\n"
            + "instructions for weapons or explosives\nharassment or hate speech";

    /**
     * Check text against the POLICY DOCUMENTS as well as the category list above.
     *
     * <p>The policy corpus is markdown, PDF, Office files or fetched pages that an operator
     * uploads - the rules as somebody actually wrote them. Each turn retrieves the few clauses
     * that bear on the text and puts those in front of the same classifier call, so this adds a
     * retrieval, not a second completion.
     *
     * <p>Requires {@link #llmEnabled}: judging prose against prose is what a model is for, and
     * there is nothing for a regular expression to do with a paragraph of policy. Off by default,
     * like the classifier it rides on.
     */
    @JsonProperty("semantic-enabled")
    private boolean semanticEnabled = false;

    /**
     * Run the policy check on ANSWERS as well as on messages.
     *
     * <p>Off by default while input is on, which is the asymmetry the whole feature is shaped
     * around: checking what people send is the thing operators ask for, and it doubles the added
     * cost per turn to also check what comes back. An operator who wants answers held to the
     * policy too can say so knowingly.
     */
    @JsonProperty("semantic-on-output")
    private boolean semanticOnOutput = false;

    /**
     * How many policy clauses are retrieved per check.
     *
     * <p>Small on purpose. These go into the classifier prompt on every turn, so this is a direct
     * per-turn token cost, and a model judging against three relevant clauses does better than one
     * judging against twenty mostly-irrelevant ones.
     */
    @JsonProperty("semantic-top-k")
    private Integer semanticTopK = 4;

    /**
     * The DENSE similarity floor that decides whether any policy applies to a message at all.
     *
     * <p>Below it nothing is retrieved and no classifier call is made, which is what keeps this
     * layer from adding a cost to every turn. It has to be the dense arm that decides: BM25 takes
     * no floor and returns its best matches for any query with any word in it, so a fused hybrid
     * result is never empty and "no policy bears on this" would be inexpressible. See
     * {@code PolicyRetriever.anythingRelevant}.
     *
     * <p><b>This value is embedding-model dependent and is a default, not a recommendation.</b>
     * Cosine similarity means different things to different models, and the only way to set it
     * properly is to try the messages a deployment actually sees.
     *
     * <p>Deliberately erring LOW. The two ways to be wrong are not symmetric: too low costs a
     * classifier call that will almost always answer "allow", while too high silently disables the
     * whole layer - no retrieval, no call, no protection, and nothing anywhere saying so. A
     * guardrail that quietly does nothing is the worse failure, so the default leans toward
     * running.
     */
    @JsonProperty("semantic-min-score")
    private Double semanticMinScore = 0.25;

    /**
     * Optional per-role endpoint override, same shape and same reason as the other model roles:
     * runtimes that serve one model per process cannot be described by a single provider-level
     * base URL. Blank means "use the provider's own".
     */
    @JsonProperty("base-url")
    private String baseUrl;

    @JsonProperty("api-key")
    private String apiKey;

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }

    public boolean isInputEnabled() { return inputEnabled; }
    public void setInputEnabled(boolean inputEnabled) { this.inputEnabled = inputEnabled; }

    public boolean isOutputEnabled() { return outputEnabled; }
    public void setOutputEnabled(boolean outputEnabled) { this.outputEnabled = outputEnabled; }

    public Action getOnInputViolation() { return onInputViolation; }
    public void setOnInputViolation(Action onInputViolation) { this.onInputViolation = onInputViolation; }

    public Action getOnOutputViolation() { return onOutputViolation; }
    public void setOnOutputViolation(Action onOutputViolation) { this.onOutputViolation = onOutputViolation; }

    public boolean isRetrievalEnabled() { return retrievalEnabled; }
    public void setRetrievalEnabled(boolean retrievalEnabled) { this.retrievalEnabled = retrievalEnabled; }

    public Action getOnRetrievalViolation() { return onRetrievalViolation; }
    public void setOnRetrievalViolation(Action onRetrievalViolation) {
        this.onRetrievalViolation = onRetrievalViolation;
    }

    public boolean isDetectSecrets() { return detectSecrets; }
    public void setDetectSecrets(boolean detectSecrets) { this.detectSecrets = detectSecrets; }

    public boolean isDetectPii() { return detectPii; }
    public void setDetectPii(boolean detectPii) { this.detectPii = detectPii; }

    public boolean isDetectPromptInjection() { return detectPromptInjection; }
    public void setDetectPromptInjection(boolean detectPromptInjection) {
        this.detectPromptInjection = detectPromptInjection;
    }

    public boolean isDetectPathLeak() { return detectPathLeak; }
    public void setDetectPathLeak(boolean detectPathLeak) { this.detectPathLeak = detectPathLeak; }

    public String getDenyTerms() { return denyTerms; }
    public void setDenyTerms(String denyTerms) { this.denyTerms = denyTerms; }

    public boolean isFailClosed() { return failClosed; }
    public void setFailClosed(boolean failClosed) { this.failClosed = failClosed; }

    public boolean isCaptureMatchedText() { return captureMatchedText; }
    public void setCaptureMatchedText(boolean captureMatchedText) {
        this.captureMatchedText = captureMatchedText;
    }

    public boolean isCanaryEnabled() { return canaryEnabled; }
    public void setCanaryEnabled(boolean canaryEnabled) { this.canaryEnabled = canaryEnabled; }

    public String getCanaryToken() { return canaryToken; }
    public void setCanaryToken(String canaryToken) { this.canaryToken = canaryToken; }

    public Integer getRepeatThreshold() { return repeatThreshold; }
    public void setRepeatThreshold(Integer repeatThreshold) { this.repeatThreshold = repeatThreshold; }

    public Integer getRepeatWindowMinutes() { return repeatWindowMinutes; }
    public void setRepeatWindowMinutes(Integer repeatWindowMinutes) {
        this.repeatWindowMinutes = repeatWindowMinutes;
    }

    public Integer getRepeatCooldownMinutes() { return repeatCooldownMinutes; }
    public void setRepeatCooldownMinutes(Integer repeatCooldownMinutes) {
        this.repeatCooldownMinutes = repeatCooldownMinutes;
    }

    public boolean isLlmEnabled() { return llmEnabled; }
    public void setLlmEnabled(boolean llmEnabled) { this.llmEnabled = llmEnabled; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public Integer getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(Integer maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }

    public String getLlmCategories() { return llmCategories; }
    public void setLlmCategories(String llmCategories) { this.llmCategories = llmCategories; }

    public boolean isExamplesEnabled() { return examplesEnabled; }
    public void setExamplesEnabled(boolean examplesEnabled) { this.examplesEnabled = examplesEnabled; }

    public Double getExamplesThreshold() { return examplesThreshold; }
    public void setExamplesThreshold(Double examplesThreshold) { this.examplesThreshold = examplesThreshold; }

    public boolean isSemanticEnabled() { return semanticEnabled; }
    public void setSemanticEnabled(boolean semanticEnabled) { this.semanticEnabled = semanticEnabled; }

    public boolean isSemanticOnOutput() { return semanticOnOutput; }
    public void setSemanticOnOutput(boolean semanticOnOutput) { this.semanticOnOutput = semanticOnOutput; }

    public Integer getSemanticTopK() { return semanticTopK; }
    public void setSemanticTopK(Integer semanticTopK) { this.semanticTopK = semanticTopK; }

    public Double getSemanticMinScore() { return semanticMinScore; }
    public void setSemanticMinScore(Double semanticMinScore) { this.semanticMinScore = semanticMinScore; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
}
