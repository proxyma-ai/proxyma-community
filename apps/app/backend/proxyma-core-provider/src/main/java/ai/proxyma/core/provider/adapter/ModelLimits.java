package ai.proxyma.core.provider.adapter;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a provider says a model can take in and give back: its context window and the most it
 * will write in one answer. Either half is null when the provider does not say.
 *
 * <p>Since 2026-10-03 (the maintainer's request) these are what the agent runs with until the
 * installation sets its own. The two numbers were fixed defaults before - 128k and 8k, then 200k
 * and 64k for a day - and a fixed number is wrong for most models in one direction or the other:
 * an output ceiling above the model's is a refused request, and a window larger than the model's
 * overflows before anything is condensed.
 *
 * <p>The parsers are here, apart from the HTTP calls that feed them, so that what each provider's
 * answer is taken to mean can be tested without a provider.
 */
public record ModelLimits(Integer contextWindow, Integer maxOutputTokens) {

    public static final ModelLimits UNKNOWN = new ModelLimits(null, null);

    public ModelLimits {
        contextWindow = positive(contextWindow);
        maxOutputTokens = positive(maxOutputTokens);
    }

    public boolean isKnown() {
        return contextWindow != null || maxOutputTokens != null;
    }

    /** Anthropic's GET /v1/models/{id}: both numbers, either of which may be null. */
    public static ModelLimits fromAnthropic(Map<String, Object> model) {
        if (model == null) return UNKNOWN;
        return new ModelLimits(number(model.get("max_input_tokens")), number(model.get("max_tokens")));
    }

    /** Gemini's GET models/{id}. */
    public static ModelLimits fromGemini(Map<String, Object> model) {
        if (model == null) return UNKNOWN;
        return new ModelLimits(number(model.get("inputTokenLimit")), number(model.get("outputTokenLimit")));
    }

    private static final Pattern NUM_CTX = Pattern.compile("(?m)^\\s*num_ctx\\s+(\\d+)\\s*$");

    /**
     * Ollama's POST /api/show. The window is the model file's own {@code num_ctx} when it sets
     * one - that is what the server loads the model with - and otherwise the length the model was
     * trained with, which is the most it could be given. Ollama states no output ceiling.
     *
     * <p>The trained length can OVERSTATE: a server that loads the model with a smaller window
     * than it was trained with says so nowhere in this answer. It is still the closest number
     * there is, and far closer than a fixed default.
     */
    public static ModelLimits fromOllama(Map<String, Object> shown) {
        if (shown == null) return UNKNOWN;
        if (shown.get("parameters") instanceof String parameters) {
            Matcher set = NUM_CTX.matcher(parameters);
            if (set.find()) return new ModelLimits(number(set.group(1)), null);
        }
        if (shown.get("model_info") instanceof Map<?, ?> info) {
            for (Map.Entry<?, ?> entry : info.entrySet()) {
                if (String.valueOf(entry.getKey()).endsWith(".context_length")) {
                    return new ModelLimits(number(entry.getValue()), null);
                }
            }
        }
        return UNKNOWN;
    }

    // The OpenAI wire format defines no limits on a model, and OpenAI itself states none. These
    // are the fields the OpenAI-compatible servers that DO state them use: Groq (context_window,
    // max_completion_tokens), vLLM (max_model_len), Mistral (max_context_length), llama.cpp
    // (meta.n_ctx_train), and context_length, which OpenRouter made common. OpenRouter and LiteLLM
    // have their own protocols and their own readers below.
    private static final List<String> CONTEXT_FIELDS =
            List.of("context_length", "context_window", "max_model_len", "max_context_length");
    private static final List<String> OUTPUT_FIELDS = List.of("max_completion_tokens", "max_output_tokens");

    /** One entry of an OpenAI-compatible GET /v1/models. Unknown for a service that states nothing. */
    public static ModelLimits fromOpenAiCompatible(Map<String, Object> model) {
        if (model == null) return UNKNOWN;
        Integer context = first(model, CONTEXT_FIELDS);
        if (context == null && model.get("meta") instanceof Map<?, ?> meta) context = number(meta.get("n_ctx_train"));
        return new ModelLimits(context, first(model, OUTPUT_FIELDS));
    }

    /** One entry of OpenRouter's GET /v1/models: context_length, and the most the serving provider writes. */
    public static ModelLimits fromOpenRouter(Map<String, Object> model) {
        if (model == null) return UNKNOWN;
        Integer output = null;
        if (model.get("top_provider") instanceof Map<?, ?> top) output = number(top.get("max_completion_tokens"));
        return new ModelLimits(number(model.get("context_length")), output);
    }

    /**
     * One entry of LiteLLM's GET /model/info: its {@code model_info} block, which states the window as
     * max_input_tokens and the output ceiling as max_output_tokens (max_tokens is LiteLLM's older name
     * for the latter).
     */
    public static ModelLimits fromLiteLlm(Map<String, Object> entry) {
        if (entry == null || !(entry.get("model_info") instanceof Map<?, ?> info)) return UNKNOWN;
        Integer output = number(info.get("max_output_tokens"));
        if (output == null) output = number(info.get("max_tokens"));
        return new ModelLimits(number(info.get("max_input_tokens")), output);
    }

    private static Integer first(Map<String, Object> model, List<String> fields) {
        for (String field : fields) {
            Integer value = number(model.get(field));
            if (value != null) return value;
        }
        return null;
    }

    private static Integer number(Object value) {
        if (value instanceof Number n) return positive(n.longValue() > Integer.MAX_VALUE ? null : n.intValue());
        if (value instanceof String s) {
            try {
                return positive(Integer.parseInt(s.trim()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Integer positive(Integer value) {
        return value == null || value <= 0 ? null : value;
    }
}
