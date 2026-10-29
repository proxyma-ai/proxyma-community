package ai.proxyma.core.chat.agents;

import ai.proxyma.core.provider.adapter.LlmProviderAdapter;
import ai.proxyma.core.provider.service.LlmProviderFactory;
import ai.proxyma.core.settings.dto.ChatSettings;
import ai.proxyma.core.settings.dto.SubAgentSettings;
import ai.proxyma.core.settings.provider.ProviderInstanceService;
import ai.proxyma.core.settings.provider.ResolvedProvider;
import ai.proxyma.core.settings.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * WHICH MODEL AN AGENT MAY NAME FOR AN AGENT IT DEPLOYS (the maintainer's request, 2026-10-29: an
 * agent invented a model name, and the agent it deployed failed at its first model call).
 *
 * <p>A name is taken only when a configured provider LISTS a model of that name and Settings > Agents >
 * Deployed agents allows it (SubAgentSettings.modelRule: any listed model, only the chosen ones, or all
 * but those). It is resolved to the provider that lists it - the agent's own provider first - so the
 * deployed agent runs on that provider. Anything else is refused with the names it may use, which is
 * what an agent needs to correct itself. Naming no model is always allowed: the agent then runs on the
 * Settings default for deployed agents, else the agent's own model.
 *
 * <p>The providers' lists are read live and kept for five minutes; a provider that cannot be asked
 * lists nothing, so its models cannot be named until it answers again.
 */
@Component
public class AgentModels {

    private static final Logger log = LoggerFactory.getLogger(AgentModels.class);

    static final long LIST_TTL_MS = 5 * 60_000L;
    /** How many names a refusal lists. */
    static final int NAMES_SHOWN = 40;

    /** A resolved choice, or why there is none. */
    public record Choice(String providerId, String model, String error) {
        static Choice refused(String why) {
            return new Choice(null, null, why);
        }

        public boolean ok() {
            return error == null;
        }
    }

    /** One model a provider lists: its instance id, the provider's name, the model's name. */
    record Listed(String providerId, String providerName, String model) {}

    private final ProviderInstanceService instances;
    private final LlmProviderFactory factory;
    private final SettingsService settings;
    private final LongSupplier clock;
    private volatile List<Listed> cached;
    private volatile long cachedAt;

    @org.springframework.beans.factory.annotation.Autowired
    public AgentModels(ProviderInstanceService instances, LlmProviderFactory factory, SettingsService settings) {
        this(instances, factory, settings, System::currentTimeMillis);
    }

    AgentModels(ProviderInstanceService instances, LlmProviderFactory factory, SettingsService settings, LongSupplier clock) {
        this.instances = instances;
        this.factory = factory;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * The provider and exact model for {@code model} as an agent named it (with {@code providerId}
     * when the caller knows it), or a refusal saying which names it may use. A blank name is no
     * choice: an ok Choice of two nulls.
     */
    public Choice choose(String providerId, String model) {
        if (model == null || model.isBlank()) return new Choice(null, null, null);
        String wanted = model.strip();
        SubAgentSettings rule = rule();
        List<Listed> listed = listed();
        if (listed.isEmpty()) {
            return Choice.refused("No provider's model list could be read, so no model can be named now. "
                    + "Leave model out: the agent runs on its default model.");
        }
        String own = ownProvider();
        Listed match = null;
        boolean listedButBarred = false;
        for (Listed l : listed) {
            if (!l.model().equalsIgnoreCase(wanted)) continue;
            if (providerId != null && !providerId.isBlank() && !providerId.equals(l.providerId())) continue;
            if (!allowed(rule, l.providerId(), l.model())) {
                listedButBarred = true;
                continue;
            }
            if (match == null || l.providerId().equals(own)) match = l;
        }
        if (match != null) return new Choice(match.providerId(), match.model(), null);
        String first = listedButBarred
                ? "Settings do not allow deployed agents to use " + wanted + "."
                : "No provider lists a model named " + wanted + ".";
        return Choice.refused(first + " Leave model out to use the default, or name one of: " + names(rule, listed) + ".");
    }

    /** Whether the rule allows this provider's model. */
    static boolean allowed(SubAgentSettings rule, String providerId, String model) {
        String mode = rule.modelRule();
        if (SubAgentSettings.MODELS_ANY.equals(mode)) return true;
        boolean listed = false;
        String entries = rule.getModelList() == null ? "" : rule.getModelList();
        for (String raw : entries.split("\\R")) {
            String e = raw.strip();
            if (e.isEmpty()) continue;
            int bar = e.indexOf('|');
            String p = bar < 0 ? e : e.substring(0, bar).strip();
            String m = bar < 0 ? "" : e.substring(bar + 1).strip();
            if (!p.equals(providerId)) continue;
            if (m.isEmpty() || m.equalsIgnoreCase(model)) {
                listed = true;
                break;
            }
        }
        return SubAgentSettings.MODELS_ONLY.equals(mode) ? listed : !listed;
    }

    // The names an agent may use, "model (Provider)", at most NAMES_SHOWN of them.
    private static String names(SubAgentSettings rule, List<Listed> listed) {
        List<String> out = new ArrayList<>();
        int more = 0;
        for (Listed l : listed) {
            if (!allowed(rule, l.providerId(), l.model())) continue;
            if (out.size() < NAMES_SHOWN) out.add(l.model() + " (" + l.providerName() + ")");
            else more++;
        }
        if (out.isEmpty()) return "none - Settings allow no listed model, so leave model out";
        return String.join(", ", out) + (more > 0 ? " and " + more + " more" : "");
    }

    // Every configured provider's models, read at most every five minutes.
    List<Listed> listed() {
        List<Listed> c = cached;
        long now = clock.getAsLong();
        if (c != null && now - cachedAt < LIST_TTL_MS) return c;
        List<Listed> out = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (ResolvedProvider p : instances.resolveAll()) {
            String id = p.id().toString();
            if (seen.put(id, true) != null) continue;
            try {
                LlmProviderAdapter adapter = factory.getAdapter(id);
                if (adapter == null) continue;
                for (String m : adapter.listModels(p)) {
                    if (m != null && !m.isBlank()) out.add(new Listed(id, p.name(), m.strip()));
                }
            } catch (RuntimeException e) {
                log.debug("[Agents] could not list the models of provider {}: {}", p.name(), e.getMessage());
            }
        }
        cached = List.copyOf(out);
        cachedAt = now;
        return cached;
    }

    private SubAgentSettings rule() {
        try {
            SubAgentSettings s = settings.getSettings(SubAgentSettings.PREFIX, SubAgentSettings.class);
            return s == null ? new SubAgentSettings() : s;
        } catch (RuntimeException e) {
            return new SubAgentSettings();
        }
    }

    private String ownProvider() {
        try {
            ChatSettings c = settings.getSettings(ChatSettings.PREFIX, ChatSettings.class);
            return c == null ? null : c.getProvider();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
