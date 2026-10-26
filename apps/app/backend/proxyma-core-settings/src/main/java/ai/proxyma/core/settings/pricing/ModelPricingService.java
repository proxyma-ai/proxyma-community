package ai.proxyma.core.settings.pricing;

import ai.proxyma.core.common.usage.CallFacts;
import ai.proxyma.core.common.usage.CostSource;
import ai.proxyma.core.common.usage.TokenUsage;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Admin-configurable $/million-token rates per provider+model, and what turns a call's usage into
// what it cost. An unconfigured (provider, model) pair the built-in list does not know either is
// deliberate zero cost, never an error - self-hosted/Ollama models and any model an admin simply
// hasn't priced yet should not break a chat turn.
//
// SINCE 2026-10-22 a call's cost is SETTLED where the call is made (settle(), from the gateway),
// with what only the response knows: the cost the provider stated, which always wins, the service
// tier, the region, the web searches, and when it ran. costUsd() then adds that settled part to an
// estimate of whatever was left unsettled - see TokenUsage.
@Service
public class ModelPricingService {

    /** A cost and where it came from. */
    public record Priced(double costUsd, CostSource source) {}

    /** Every rate a row can carry; null (or zero) where not given. */
    public record RateInput(double input, double output, double cacheRead, double cacheWrite,
                            Double cacheWriteLong, Long longContextThresholdTokens, Double longInput,
                            Double longOutput, Double longCacheRead, Double longCacheWrite, String schedule) {
        /** The four rates every row has had since cost was first estimated. */
        public static RateInput basic(double input, double output, double cacheRead, double cacheWrite) {
            return new RateInput(input, output, cacheRead, cacheWrite, null, null, null, null, null, null, null);
        }
    }

    // A rate is read for every chunk of a stream that carries a usage, so rows are kept for a few
    // seconds rather than read each time - short, so a server's other replicas see an edit soon.
    private static final long RATE_CACHE_MILLIS = 15_000;

    private record CachedRow(Optional<ModelPricing> row, long readAt) {}

    private final ModelPricingRepository repository;
    private final ListPrices listPrices;
    private final Map<String, CachedRow> rows = new ConcurrentHashMap<>();

    // @Autowired because there are two: Spring picks a constructor by itself only when a class has
    // exactly one, and without it the application did not start.
    @org.springframework.beans.factory.annotation.Autowired
    public ModelPricingService(ModelPricingRepository repository) {
        this(repository, ListPrices.builtIn());
    }

    ModelPricingService(ModelPricingRepository repository, ListPrices listPrices) {
        this.repository = repository;
        this.listPrices = listPrices;
    }

    public List<ModelPricing> listAll() {
        return repository.findAllByOrderByProviderAscModelAsc();
    }

    public Optional<ModelPricing> get(UUID id) {
        return repository.findById(id);
    }

    /** The built-in list, for the form that prefills a rate from it. */
    public ListPrices listPrices() {
        return listPrices;
    }

    /**
     * Carries a provider's rates across a rename, called by ProviderInstanceService when the name
     * on a provider instance changes.
     *
     * <p>Pricing is keyed on the provider NAME, because that is what the cost ledger records for
     * every call - and a rename deliberately does not rewrite history, so old spend rows keep the
     * old string. Rates are not history: they are current configuration, and a rate that stayed
     * behind under the old name would silently price every future call at $0. That was survivable
     * while rates were a separate list in Cost Settings, where the stale row was at least visible;
     * now that they are edited inside the provider's own dialog, a rate left under a name no
     * provider has any more would be invisible as well as wrong.
     *
     * <p>A row already sitting on the target name for the same model wins and the old one is
     * dropped - the unique (provider, model) index would otherwise reject the update outright, and
     * a stale row can only be there because some earlier provider of that name was deleted.
     */
    public void renameProvider(String oldName, String newName) {
        if (oldName == null || newName == null || oldName.isBlank() || newName.isBlank()
                || oldName.equals(newName)) {
            return;
        }
        for (ModelPricing row : repository.findAllByProviderOrderByModelAsc(oldName)) {
            repository.findByProviderAndModel(newName, row.getModel())
                    .ifPresent(repository::delete);
            row.setProvider(newName);
            repository.save(row);
        }
        rows.clear();
    }

    // Upsert semantics: editing an existing model's price is the common admin action and should
    // not require deleting and recreating the row first (the unique provider+model index would
    // reject a literal duplicate insert anyway). The four basic rates only - a row's other rates
    // are kept as they were. save() writes all of them.
    public ModelPricing create(String provider, String model, double input, double output,
                               double cacheRead, double cacheWrite) {
        ModelPricing pricing = repository.findByProviderAndModel(provider, model).orElseGet(ModelPricing::new);
        pricing.setProvider(provider);
        pricing.setModel(model);
        applyBasic(pricing, RateInput.basic(input, output, cacheRead, cacheWrite));
        rows.clear();
        return repository.save(pricing);
    }

    /** Creates or replaces a row with every rate given. */
    public ModelPricing save(String provider, String model, RateInput rates) {
        ModelPricing pricing = repository.findByProviderAndModel(provider, model).orElseGet(ModelPricing::new);
        pricing.setProvider(provider);
        pricing.setModel(model);
        apply(pricing, rates);
        rows.clear();
        return repository.save(pricing);
    }

    public Optional<ModelPricing> update(UUID id, double input, double output, double cacheRead, double cacheWrite) {
        return repository.findById(id).map(pricing -> {
            applyBasic(pricing, RateInput.basic(input, output, cacheRead, cacheWrite));
            rows.clear();
            return repository.save(pricing);
        });
    }

    /** Replaces every rate of an existing row. */
    public Optional<ModelPricing> update(UUID id, RateInput rates) {
        return repository.findById(id).map(pricing -> {
            apply(pricing, rates);
            rows.clear();
            return repository.save(pricing);
        });
    }

    public boolean delete(UUID id) {
        if (!repository.existsById(id)) return false;
        repository.deleteById(id);
        rows.clear();
        return true;
    }

    private static void applyBasic(ModelPricing pricing, RateInput r) {
        pricing.setInputPricePerMillion(r.input());
        pricing.setOutputPricePerMillion(r.output());
        pricing.setCacheReadPricePerMillion(r.cacheRead());
        pricing.setCacheWritePricePerMillion(r.cacheWrite());
    }

    private static void apply(ModelPricing pricing, RateInput r) {
        applyBasic(pricing, r);
        pricing.setCacheWriteLongPricePerMillion(positive(r.cacheWriteLong()));
        Long threshold = r.longContextThresholdTokens();
        boolean longTier = threshold != null && threshold > 0;
        pricing.setLongContextThresholdTokens(longTier ? threshold : null);
        pricing.setLongInputPricePerMillion(longTier ? positive(r.longInput()) : null);
        pricing.setLongOutputPricePerMillion(longTier ? positive(r.longOutput()) : null);
        pricing.setLongCacheReadPricePerMillion(longTier ? positive(r.longCacheRead()) : null);
        pricing.setLongCacheWritePricePerMillion(longTier ? positive(r.longCacheWrite()) : null);
        PricingSchedule schedule = PricingSchedule.parse(r.schedule());
        pricing.setPricingSchedule(schedule == PricingSchedule.NONE ? null : schedule.name());
    }

    private static Double positive(Double value) {
        return value != null && value > 0 ? value : null;
    }

    /**
     * What this usage cost: whatever the gateway already settled, plus an estimate of the rest at
     * the rate for this provider and model (or the list price, where a protocol is known and no
     * rate was entered). Every spend site goes through here, so there is one basis for every
     * figure on screen.
     */
    public double costUsd(String provider, String model, TokenUsage usage) {
        return price(provider, model, usage).costUsd();
    }

    /** {@link #costUsd} with where the cost came from. */
    public Priced price(String provider, String model, TokenUsage usage) {
        if (usage == null) return new Priced(0, CostSource.NONE);
        TokenUsage rest = usage.unsettledCounts();
        Priced restPrice = rest.isEmptyCounts()
                ? new Priced(0, null)
                : estimate(provider, model, rest, CallFacts.now());
        CostSource source = strongest(usage.costSource(), restPrice.source());
        return new Priced(usage.settledCostUsd() + restPrice.costUsd(), source == null ? CostSource.NONE : source);
    }

    /**
     * ONE call's usage with its cost settled: the cost the provider stated if it stated one -
     * always, whatever the rates say - else the rates (or the list price) applied with what the
     * response said about the call. Called by the gateway on the chunks of a call as they arrive.
     */
    public TokenUsage settle(String provider, String model, TokenUsage usage, CallFacts facts) {
        if (usage == null) return null;
        if (facts != null && facts.reportedCostUsd() != null && facts.reportedCostUsd() >= 0
                && Double.isFinite(facts.reportedCostUsd())) {
            return usage.settled(facts.reportedCostUsd(), CostSource.REPORTED);
        }
        Priced p = estimate(provider, model, usage, facts);
        return usage.settled(p.costUsd(), p.source());
    }

    /** The cost of these counts from the rates alone, with what is known about the call. */
    Priced estimate(String provider, String model, TokenUsage usage, CallFacts facts) {
        Optional<ModelPricing> row = row(provider, model);
        if (row.isPresent()) {
            return new Priced(CostCalculator.cost(RateCard.of(row.get()), usage, facts), CostSource.RATE);
        }
        Optional<ListPrices.Entry> listed = facts == null ? Optional.empty() : listPrices.find(facts.protocol(), model);
        if (listed.isPresent()) {
            return new Priced(CostCalculator.cost(listed.get().rates(), usage, facts), CostSource.LIST);
        }
        // No rate at all: only the per-request fees the response stated are known.
        return new Priced(CostCalculator.cost(null, usage, facts), CostSource.NONE);
    }

    /**
     * Whether a rate exists for this provider/model at all - an entered one, or a list price for a
     * model of that name.
     *
     * <p>Not answerable from the cost: an unpriced model and a model priced at zero both bill 0,
     * and only the first is something the user can act on. Usage &amp; Cost asks this per row so it
     * can say which figures are missing a rate rather than presenting every zero as a fact.
     */
    public boolean hasPricing(String provider, String model) {
        return row(provider, model).isPresent() || listPrices.findAnyVendor(model).isPresent();
    }

    private Optional<ModelPricing> row(String provider, String model) {
        if (provider == null || model == null) return Optional.empty();
        String key = provider + "\n" + model;
        long now = System.currentTimeMillis();
        CachedRow cached = rows.get(key);
        if (cached != null && now - cached.readAt() < RATE_CACHE_MILLIS) return cached.row();
        Optional<ModelPricing> read = repository.findByProviderAndModel(provider, model);
        rows.put(key, new CachedRow(read, now));
        return read;
    }

    private static CostSource strongest(CostSource a, CostSource b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
