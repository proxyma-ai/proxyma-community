package ai.proxyma.core.settings.pricing;

import ai.proxyma.core.common.usage.TokenUsage;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Admin-configurable $/million-token rates per provider+model, and the cost-estimation lookup
// used by the chat feature (see SmartChatMemory.addCumulativeUsage in proxyma-core-chat) to turn
// a TokenUsage into an estimated USD cost per conversation. An unconfigured (provider, model)
// pair is deliberate zero cost, never an error - self-hosted/Ollama models and any model an admin
// simply hasn't priced yet should not break a chat turn.
@Service
public class ModelPricingService {

    private final ModelPricingRepository repository;

    public ModelPricingService(ModelPricingRepository repository) {
        this.repository = repository;
    }

    public List<ModelPricing> listAll() {
        return repository.findAllByOrderByProviderAscModelAsc();
    }

    public Optional<ModelPricing> get(UUID id) {
        return repository.findById(id);
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
    }

    // Upsert semantics: editing an existing model's price is the common admin action and should
    // not require deleting and recreating the row first (the unique provider+model index would
    // reject a literal duplicate insert anyway).
    public ModelPricing create(String provider, String model, double input, double output,
                                double cacheRead, double cacheWrite) {
        ModelPricing pricing = repository.findByProviderAndModel(provider, model).orElseGet(ModelPricing::new);
        pricing.setProvider(provider);
        pricing.setModel(model);
        pricing.setInputPricePerMillion(input);
        pricing.setOutputPricePerMillion(output);
        pricing.setCacheReadPricePerMillion(cacheRead);
        pricing.setCacheWritePricePerMillion(cacheWrite);
        return repository.save(pricing);
    }

    public Optional<ModelPricing> update(UUID id, double input, double output, double cacheRead, double cacheWrite) {
        return repository.findById(id).map(pricing -> {
            pricing.setInputPricePerMillion(input);
            pricing.setOutputPricePerMillion(output);
            pricing.setCacheReadPricePerMillion(cacheRead);
            pricing.setCacheWritePricePerMillion(cacheWrite);
            return repository.save(pricing);
        });
    }

    public boolean delete(UUID id) {
        if (!repository.existsById(id)) return false;
        repository.deleteById(id);
        return true;
    }

    /**
     * What this call cost: the configured rate times the tokens Proxyma counted. Always an estimate,
     * and every spend site goes through here so there is one basis for every figure on screen.
     *
     * <p>It used to prefer a cost the PROVIDER reported, where a gateway had been opted in to
     * returning one. That is gone as of 0.1.2 with the opt-in itself: the mixed basis meant two rows
     * on the same screen could be measured differently with nothing saying which was which, and the
     * screen already tells the user in as many words that these are estimates.
     *
     * <p>Kept as a separate method from {@link #estimateCostUsd} so the ~dozen call sites do not all
     * have to change to say the same thing.
     */
    public double costUsd(String provider, String model, TokenUsage usage) {
        return estimateCostUsd(provider, model, usage);
    }

    /**
     * Whether a rate exists for this provider/model at all.
     *
     * <p>Not answerable from the cost: an unpriced model and a model priced at zero both bill 0,
     * and only the first is something the user can act on. Usage &amp; Cost asks this per row so it
     * can say which figures are missing a rate rather than presenting every zero as a fact.
     */
    public boolean hasPricing(String provider, String model) {
        return repository.findByProviderAndModel(provider, model).isPresent();
    }

    private static double orInput(double cacheRate, ModelPricing p) {
        return cacheRate > 0 ? cacheRate : p.getInputPricePerMillion();
    }

    public double estimateCostUsd(String provider, String model, TokenUsage usage) {
        return repository.findByProviderAndModel(provider, model)
                // A cache rate left at zero means "not entered", not "free": since input stopped
                // counting cached tokens (uncachedInputTokens), a model priced without a cache-read
                // rate would have billed its whole cached prompt at nothing. Such tokens fall back
                // to the input rate, which is what they cost before caching was priced at all.
                .map(p -> (usage.uncachedInputTokens() * p.getInputPricePerMillion()
                         + usage.outputTokens() * p.getOutputPricePerMillion()
                         + usage.cacheReadTokens() * orInput(p.getCacheReadPricePerMillion(), p)
                         + cacheWriteCost(usage, orInput(p.getCacheWritePricePerMillion(), p))) / 1_000_000.0)
                .orElse(0.0);
    }

    // The configured cache-write rate is the five-minute one (1.25x input on Anthropic); a one-hour
    // write is 2x input, so 1.6 times that rate. Priced at the five-minute rate until 2026-09-26,
    // which under-counted every one-hour write by 37.5%.
    static final double LONG_CACHE_WRITE_FACTOR = 2.0 / 1.25;

    private static double cacheWriteCost(TokenUsage usage, double writeRate) {
        long longWrites = Math.min(usage.longCacheWriteTokens(), usage.cacheWriteTokens());
        return (usage.cacheWriteTokens() - longWrites) * writeRate
                + longWrites * writeRate * LONG_CACHE_WRITE_FACTOR;
    }
}
