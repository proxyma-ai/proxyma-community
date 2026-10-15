package ai.proxyma.core.diagnostics.daily;

import ai.proxyma.contracts.telemetry.diagnostics.DiagnosticsEnvironment;
import ai.proxyma.core.common.diagnostics.DiagLabels;
import ai.proxyma.core.common.diagnostics.DiagnosticsContributor;
import ai.proxyma.core.provider.service.LlmProviderFactory;
import ai.proxyma.core.settings.dto.ChatSettings;
import ai.proxyma.core.settings.dto.EmbeddingSettings;
import ai.proxyma.core.settings.dto.EvaluationSettings;
import ai.proxyma.core.settings.dto.GraphSettings;
import ai.proxyma.core.settings.dto.GuardrailSettings;
import ai.proxyma.core.settings.dto.OcrSettings;
import ai.proxyma.core.settings.dto.QueryExpansionSettings;
import ai.proxyma.core.settings.dto.RerankingSettings;
import ai.proxyma.core.settings.dto.SubAgentSettings;
import ai.proxyma.core.settings.dto.WebSearchSettings;
import ai.proxyma.core.settings.service.SettingsService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * The once-a-day facts about an installation: what it runs on, and which features are switched on.
 *
 * <p>Every value is a boolean, a whole number or a code-defined token, and {@link #sanitize} holds
 * the map to that whatever a contributor passes. Chosen to answer product questions a count cannot:
 * how many installations turned the knowledge graph on, which provider protocol people chat
 * through, whether the slow searches are the ones with reranking on.
 */
@Component
public class DiagnosticsSnapshot {

    private final SettingsService settings;
    private final LlmProviderFactory providerFactory;
    private final ObjectProvider<DiagnosticsContributor> contributors;
    private final Environment environment;

    public DiagnosticsSnapshot(SettingsService settings, LlmProviderFactory providerFactory,
                               ObjectProvider<DiagnosticsContributor> contributors, Environment environment) {
        this.settings = settings;
        this.providerFactory = providerFactory;
        this.contributors = contributors;
        this.environment = environment;
    }

    /** "desktop", "home_server" or "server" - the same rule FileSourceMode uses. */
    public String edition() {
        if (environment.getProperty("proxyma.multi-user", Boolean.class, false)) return "server";
        if (environment.getProperty("proxyma.file-upload.enabled", Boolean.class, false)) return "home_server";
        return "desktop";
    }

    public DiagnosticsEnvironment environment() {
        DiagnosticsEnvironment env = new DiagnosticsEnvironment();
        env.setOs(DiagLabels.osFamily(System.getProperty("os.name")));
        env.setArch(DiagLabels.clean(System.getProperty("os.arch")));
        env.setCpuCores(Runtime.getRuntime().availableProcessors());
        env.setMemoryGb(DiagLabels.memoryBucket(totalMemoryBytes()));
        env.setJavaVersion(Runtime.version().feature());
        return env;
    }

    public Map<String, Object> features() {
        Map<String, Object> raw = new LinkedHashMap<>();
        put(raw, "queryExpansion", () -> get(QueryExpansionSettings.PREFIX, QueryExpansionSettings.class).isEnabled());
        put(raw, "reranking", () -> get(RerankingSettings.PREFIX, RerankingSettings.class).isEnabled());
        put(raw, "knowledgeGraph", () -> get(GraphSettings.PREFIX, GraphSettings.class).isEnabled());
        // Sub-agents are always on since 2026-10-03. What still varies is whether they were given a
        // model of their own, which is what this now says - under a new name, so a reader of two
        // reports never compares a switch with a model choice.
        put(raw, "subAgentModel", () -> {
            String model = get(SubAgentSettings.PREFIX, SubAgentSettings.class).getModel();
            return model != null && !model.isBlank();
        });
        put(raw, "evaluationMode", () -> get(EvaluationSettings.PREFIX, EvaluationSettings.class).isEnabled());
        put(raw, "webSearchConfigured", () -> get(WebSearchSettings.PREFIX, WebSearchSettings.class).isConfigured());
        put(raw, "guardrailInput", () -> get(GuardrailSettings.PREFIX, GuardrailSettings.class).isInputEnabled());
        put(raw, "guardrailOutput", () -> get(GuardrailSettings.PREFIX, GuardrailSettings.class).isOutputEnabled());
        put(raw, "guardrailMode", () -> get(GuardrailSettings.PREFIX, GuardrailSettings.class).getMode());
        put(raw, "ocrEngine", () -> get(OcrSettings.PREFIX, OcrSettings.class).getEngine());
        put(raw, "chatProtocol", () -> protocolOf(get(ChatSettings.PREFIX, ChatSettings.class).getProvider()));
        put(raw, "embeddingProtocol", () -> protocolOf(get(EmbeddingSettings.PREFIX, EmbeddingSettings.class).getProvider()));
        BiConsumer<String, Object> sink = raw::put;
        for (DiagnosticsContributor c : contributors) {
            try {
                c.contribute(sink);
            } catch (RuntimeException ignored) {
                // One contributor failing must not cost the snapshot.
            }
        }
        return sanitize(raw);
    }

    /**
     * Keeps booleans, whole numbers and enum constants, and a string ONLY when it is one of
     * {@link DiagLabels}' fixed words and ranges. Everything else is dropped - a list, a nested map,
     * a decimal, and any other string at all, since a string that is not code-defined vocabulary is
     * exactly what could be a name, a path or a key. Keys are cleaned too.
     */
    static Map<String, Object> sanitize(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            String key = DiagLabels.clean(e.getKey());
            Object v = e.getValue();
            if (v instanceof Boolean b) out.put(key, b);
            else if (v instanceof Integer || v instanceof Long || v instanceof Short) out.put(key, ((Number) v).longValue());
            else if (v instanceof Enum<?> en) out.put(key, DiagLabels.clean(en.name()));
            else if (v instanceof String s && DiagLabels.isVocabulary(s)) out.put(key, s);
        }
        return out;
    }

    private <T> T get(String prefix, Class<T> type) {
        return settings.getSettings(prefix, type);
    }

    /** The protocol ENUM - sanitize() keeps enum constants and drops free strings. */
    private Object protocolOf(String providerId) {
        if (providerId == null || providerId.isBlank()) return "none";
        return providerFactory.resolveProvider(providerId)
                .<Object>map(p -> p.protocol() == null ? "unknown" : p.protocol())
                .orElse("unknown");
    }

    private static void put(Map<String, Object> out, String key, Supplier<Object> value) {
        try {
            out.put(key, value.get());
        } catch (RuntimeException ignored) {
            // A setting that cannot be read is simply absent from the snapshot.
        }
    }

    private static long totalMemoryBytes() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
                return os.getTotalMemorySize();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Not a HotSpot-family runtime.
        }
        return -1;
    }
}
