package ai.proxyma.core.settings.usage;

import ai.proxyma.core.common.usage.TokenUsage;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Records what the platform spent on LLM calls that no user's chat turn caused.
 *
 * The counterpart of ConversationUsageRecorder, which does the same job for spend that IS a user's.
 * Both price through ModelPricingService so the two ledgers cannot drift on what a token costs.
 *
 * ACCOUNTING MUST NEVER BREAK THE THING IT MEASURES. Every entry point here swallows its own
 * failures: a full disk, a locked table or an unpriced model must not fail an ingest run or a
 * search. The consequence of a swallowed failure is an under-counted total, which is visible on the
 * Usages page and recoverable; the consequence of a thrown one is a broken pipeline.
 */
@Service
public class PlatformSpendService {

    private static final Logger log = LoggerFactory.getLogger(PlatformSpendService.class);

    private final PlatformSpendRepository repository;
    private final ModelPricingService modelPricingService;

    public PlatformSpendService(PlatformSpendRepository repository,
                                ModelPricingService modelPricingService) {
        this.repository = repository;
        this.modelPricingService = modelPricingService;
    }

    /**
     * Records one platform LLM call and returns what it cost, or 0 when nothing was recorded.
     *
     * REQUIRES_NEW so this commits on its own. Embedding is called from inside the ingest
     * transaction, and without a separate transaction a rolled-back document would also roll back
     * the record of money that was genuinely spent - the provider charged for those tokens whether
     * or not the chunk landed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public double record(PlatformService service, TokenUsage usage, String provider, String model) {
        try {
            if (usage == null || usage == TokenUsage.EMPTY) return 0;
            // A provider that reports no usage is common (several embedding APIs return none) and is
            // not an error - there is simply nothing to price. Logged at debug so an unexpectedly
            // empty Usages page has somewhere to start.
            if (usage.inputTokens() == 0 && usage.outputTokens() == 0) {
                log.debug("[PlatformSpend] {} on {}/{} reported no token usage - not recorded",
                        service, provider, model);
                return 0;
            }

            double costUsd = modelPricingService.costUsd(provider, model, usage);
            repository.save(new PlatformSpendRecord(
                    service, provider, model,
                    usage.inputTokens(), usage.outputTokens(), costUsd, Instant.now()));
            return costUsd;

        } catch (Exception e) {
            log.warn("[PlatformSpend] Could not record {} spend on {}/{}: {}",
                    service, provider, model, e.getMessage());
            return 0;
        }
    }

    /** Total platform spend since an instant. Never throws - an unreadable ledger reads as zero. */
    public double totalSince(Instant since) {
        try {
            return repository.totalCostSince(since);
        } catch (Exception e) {
            log.warn("[PlatformSpend] Could not read the platform total: {}", e.getMessage());
            return 0;
        }
    }

    /** Per-activity breakdown for the Usages page. */
    public Map<PlatformService, ServiceTotal> totalsByServiceSince(Instant since) {
        Map<PlatformService, ServiceTotal> out = new EnumMap<>(PlatformService.class);
        try {
            List<Object[]> rows = repository.totalsByServiceSince(since);
            for (Object[] r : rows) {
                out.put((PlatformService) r[0], new ServiceTotal(
                        ((Number) r[1]).doubleValue(),
                        ((Number) r[2]).longValue(),
                        ((Number) r[3]).longValue(),
                        ((Number) r[4]).longValue()));
            }
        } catch (Exception e) {
            log.warn("[PlatformSpend] Could not read the platform breakdown: {}", e.getMessage());
        }
        return out;
    }

    public record ServiceTotal(double costUsd, long inputTokens, long outputTokens, long calls) {}
}
