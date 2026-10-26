package ai.proxyma.core.common.usage;

/**
 * Where a call's cost came from - shown beside it in the per-call log, so a figure the provider
 * billed is never confused with one Proxyma worked out.
 */
public enum CostSource {
    /** Nothing priced it: no rate for the model and no cost in the response. */
    NONE,
    /** The model's rate in Settings > Providers. */
    RATE,
    /** The built-in list price for the model, where no rate was entered. */
    LIST,
    /** The provider's own response stated what the call cost (OpenRouter, LiteLLM). */
    REPORTED
}
