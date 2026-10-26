package ai.proxyma.core.settings.pricing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The vendors' list prices, built in (resources/pricing/list-prices.json) and dated: what a model
 * costs when nobody entered a rate for it, and what the Add model rate form starts from.
 *
 * <p>A rate the operator entered always wins over this list; the list only stands in for a model
 * with no row, so that spending limits count from the first call instead of at $0.
 *
 * <p>WHICH PROTOCOLS MAY USE IT is the point of {@link #find(String, String)}: a model id names a
 * vendor's model only where the vendor serves it. Anthropic, OpenAI (and Azure's deployment of its
 * models), Gemini, and the gateways that resell them (OpenRouter, LiteLLM) may; an OpenAI-compatible
 * server may use DeepSeek's entries alone, since DeepSeek's API is one; Ollama and anything unknown
 * never - a model running on your own hardware costs nothing per token, whatever it is called.
 */
public final class ListPrices {

    /** One list entry: the ids it answers to, its vendor and its rates. */
    public record Entry(String vendor, List<String> ids, RateCard rates) {}

    private static final ListPrices BUILT_IN = load("/pricing/list-prices.json");

    private final String asOf;
    private final List<Entry> entries;

    ListPrices(String asOf, List<Entry> entries) {
        this.asOf = asOf;
        this.entries = List.copyOf(entries);
    }

    public static ListPrices builtIn() {
        return BUILT_IN;
    }

    /** The date the prices were taken from the vendors' pages. */
    public String asOf() {
        return asOf;
    }

    /** The list price of {@code model} as served through {@code protocol}, if the list has one. */
    public Optional<Entry> find(String protocol, String model) {
        Set<String> vendors = vendorsFor(protocol);
        if (vendors.isEmpty() || model == null) return Optional.empty();
        boolean reseller = "OPENROUTER".equals(protocol) || "LITELLM".equals(protocol);
        return match(normalize(model, reseller), vendors);
    }

    /** The list price of {@code model} by any vendor - for the form's prefill and "is it priced". */
    public Optional<Entry> findAnyVendor(String model) {
        if (model == null) return Optional.empty();
        return match(normalize(model, true), Set.of("anthropic", "openai", "google", "deepseek"));
    }

    private Optional<Entry> match(String model, Set<String> vendors) {
        Entry best = null;
        int bestLength = -1;
        for (Entry entry : entries) {
            if (!vendors.contains(entry.vendor())) continue;
            for (String id : entry.ids()) {
                if (matches(model, id) && id.length() > bestLength) {
                    best = entry;
                    bestLength = id.length();
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * A model id is the list's id itself, or that id with a dated snapshot after it
     * ({@code claude-haiku-4-5-20251001}, {@code gpt-4o-2024-08-06}) or a version after an
     * {@code @} (Vertex). {@code gpt-5} is not {@code gpt-5.5}, and {@code gpt-4o} is not
     * {@code gpt-4o-mini}: what follows the id must start with a digit.
     */
    static boolean matches(String model, String id) {
        if (model.equals(id)) return true;
        if (!model.startsWith(id) || model.length() <= id.length() + 1) return false;
        char separator = model.charAt(id.length());
        char next = model.charAt(id.length() + 1);
        return separator == '@' || (separator == '-' && Character.isDigit(next));
    }

    // Lower case; a reseller's "vendor/model" loses its vendor.
    static String normalize(String model, boolean dropVendorPrefix) {
        String m = model.trim().toLowerCase(Locale.ROOT);
        if (dropVendorPrefix) {
            int slash = m.lastIndexOf('/');
            if (slash >= 0) m = m.substring(slash + 1);
        }
        return m;
    }

    static Set<String> vendorsFor(String protocol) {
        if (protocol == null) return Set.of();
        return switch (protocol) {
            case "ANTHROPIC" -> Set.of("anthropic");
            case "OPENAI", "AZURE_OPENAI" -> Set.of("openai");
            case "GEMINI" -> Set.of("google");
            case "OPENAI_COMPATIBLE" -> Set.of("deepseek");
            case "OPENROUTER", "LITELLM" -> Set.of("anthropic", "openai", "google", "deepseek");
            default -> Set.of();
        };
    }

    static ListPrices load(String resource) {
        try (InputStream in = ListPrices.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException(resource + " is missing from the classpath");
            JsonNode root = new ObjectMapper().readTree(in);
            List<Entry> entries = new ArrayList<>();
            for (JsonNode m : root.path("models")) {
                List<String> ids = new ArrayList<>();
                for (JsonNode id : m.path("ids")) ids.add(id.asText().toLowerCase(Locale.ROOT));
                JsonNode longTier = m.path("long");
                RateCard rates = new RateCard(
                        m.path("input").asDouble(), m.path("output").asDouble(),
                        m.path("cacheRead").asDouble(), m.path("cacheWrite").asDouble(),
                        optional(m, "cacheWriteLong"),
                        longTier.path("contextTokens").asLong(0),
                        optional(longTier, "input"), optional(longTier, "output"),
                        optional(longTier, "cacheRead"), optional(longTier, "cacheWrite"),
                        PricingSchedule.parse(m.path("schedule").asText(null)),
                        optional(m, "priorityMultiplier"));
                entries.add(new Entry(m.path("vendor").asText(), List.copyOf(ids), rates));
            }
            return new ListPrices(root.path("asOf").asText(), entries);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Double optional(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asDouble() : null;
    }
}
