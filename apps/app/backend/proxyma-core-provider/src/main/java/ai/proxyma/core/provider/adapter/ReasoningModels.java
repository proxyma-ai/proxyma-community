package ai.proxyma.core.provider.adapter;

import java.util.Locale;

/**
 * OpenAI's REASONING models - the o-series and the gpt-5 family - as OpenAI documents them: only the
 * default temperature is accepted, top_p is not supported, and max_tokens is refused in favor of
 * max_completion_tokens. Every call to one carrying the tone's sampling or a max_tokens ceiling was
 * answered with a 400, so a chat, a title, the guardrail and condensation all failed on, for
 * example, gpt-5-mini or o4-mini.
 *
 * <p>Native OpenAI and Azure OpenAI only ("native protocols first"): a gateway or an
 * OpenAI-compatible server translates or drops these parameters by its own rules. Recognized by the
 * model id, which is what OpenAI's own documentation keys the restriction on; on Azure that is the
 * deployment name, which by convention carries the model's.
 */
final class ReasoningModels {

    private ReasoningModels() {}

    static boolean isReasoning(String model) {
        if (model == null || model.isBlank()) return false;
        String m = model.trim().toLowerCase(Locale.ROOT);
        // o1, o1-mini, o3, o3-pro, o4-mini ...
        if (m.length() >= 2 && m.charAt(0) == 'o' && Character.isDigit(m.charAt(1))) return true;
        // gpt-5, gpt-5-mini, gpt-5.1 ... but not gpt-5-chat, which is the non-reasoning chat model.
        return m.startsWith("gpt-5") && !m.startsWith("gpt-5-chat");
    }
}
