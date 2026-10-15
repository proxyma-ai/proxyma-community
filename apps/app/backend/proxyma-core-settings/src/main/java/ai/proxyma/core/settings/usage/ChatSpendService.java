package ai.proxyma.core.settings.usage;

import ai.proxyma.core.common.usage.TokenUsage;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;

/**
 * Accumulates and reads chat spend per model per calendar month - see ChatSpendMonth for the shape
 * and why it is a rollup rather than a ledger.
 *
 * NOTHING HERE MAY BREAK A CHAT TURN. Every method swallows its own failures, the same discipline
 * PlatformSpendService applies for the same reason: this is bookkeeping alongside the turn, not part
 * of it, and a user losing an answer because a counter could not be written would be a far worse
 * bug than a figure being short. Reads degrade to zero/empty for the same reason - an unreadable
 * ledger renders as "nothing spent yet", never as a broken page.
 */
@Service
public class ChatSpendService {

    private static final Logger log = LoggerFactory.getLogger(ChatSpendService.class);

    private final ChatSpendMonthRepository repository;
    private final PlatformSpendService platformSpendService;
    private final ModelPricingService modelPricingService;
    private final SpendRowWriter writer;
    private final Clock clock;

    // ONE constructor, deliberately. There used to be a second, package-private one taking a Clock
    // "so the month boundary can be exercised without waiting for one" - and it stopped the server
    // booting. Spring only auto-selects a constructor when a class has exactly one; with two and
    // neither annotated it falls back to a no-arg constructor, finds none, and the whole context
    // fails: "Failed to instantiate [ChatSpendService]: No default constructor found". It took the
    // demo deployment down on 0.1.0-rc5.
    //
    // Removed rather than annotated with @Autowired. This repository has no test suite, so the seam
    // it existed for had no consumer and never would have - speculative flexibility that cost a
    // production boot.
    public ChatSpendService(ChatSpendMonthRepository repository, PlatformSpendService platformSpendService,
                            ModelPricingService modelPricingService, SpendRowWriter writer) {
        this.repository = repository;
        this.platformSpendService = platformSpendService;
        this.modelPricingService = modelPricingService;
        this.writer = writer;
        this.clock = Clock.systemDefaultZone();
    }

    /** "YYYY-MM" for right now, in the server's own zone. */
    public String currentMonth() {
        return YearMonth.now(clock).toString();
    }

    /**
     * Folds one already-priced call onto this month's row for the model that served it.
     *
     * In a transaction of its own (SpendRowWriter), matching PlatformSpendService: the caller
     * (SmartChatMemory.addCumulativeUsage) runs inside the chat transaction, and a turn that rolls
     * back for an unrelated reason still spent the tokens the provider charged for - while a
     * failure here must never roll the turn back.
     *
     * `costUsd` is passed in rather than re-priced here. The caller has already priced this exact
     * usage to update the conversation total, and pricing it a second time would be both wasted work
     * and a chance for the two figures to disagree if a rate were edited between the two calls.
     */
    public void record(TokenUsage usage, String provider, String model, double costUsd) {
        try {
            if (usage == null || usage == TokenUsage.EMPTY) return;
            if (usage.inputTokens() == 0 && usage.outputTokens() == 0
                    && usage.cacheReadTokens() == 0 && usage.cacheWriteTokens() == 0) return;
            // A model name is the grouping key of every figure this table produces, so a blank one
            // would open an unlabelled bucket that looks like a real model on the breakdown.
            if (provider == null || provider.isBlank() || model == null || model.isBlank()) {
                log.debug("[ChatSpend] call with no provider/model - not recorded");
                return;
            }

            String month = currentMonth();
            writer.write(() -> {
                ChatSpendMonth row = repository.findByYearMonthAndProviderAndModel(month, provider, model)
                        .orElseGet(() -> new ChatSpendMonth(month, provider, model));
                row.add(usage.inputTokens(), usage.outputTokens(), usage.cacheReadTokens(),
                        usage.cacheWriteTokens(), costUsd);
                repository.saveAndFlush(row);
            });

        } catch (Exception e) {
            log.warn("[ChatSpend] Could not record spend on {}/{}: {}", provider, model, e.getMessage());
        }
    }

    /** The whole summary in one read - see ChatCostSummary. */
    public ChatCostSummary summary() {
        String month = currentMonth();
        try {
            List<ChatCostSummary.ModelSpend> models = repository.findByYearMonthOrderByCostUsdDescModelAsc(month)
                    .stream()
                    .map(r -> new ChatCostSummary.ModelSpend(
                            r.getProvider(), r.getModel(), r.getCostUsd(),
                            r.getInputTokens(), r.getOutputTokens(),
                            r.getCacheReadTokens(), r.getCacheWriteTokens(),
                            r.getCallCount(),
                            // Asked per row rather than inferred from costUsd being 0: a configured
                            // rate of zero is a real answer, and telling the user to go and set a
                            // rate they already set would be worse than saying nothing.
                            modelPricingService.hasPricing(r.getProvider(), r.getModel())))
                    .toList();
            // Indexing/processing spend for the same window, so desktop keeps the whole picture on
            // one screen once Administration > Usages is gone there. Its own try/catch: the chat
            // figures are the point of this screen and must not be lost to a failure in the
            // secondary one.
            List<ChatCostSummary.ServiceSpend> platform = platformSpendThisMonth();
            double platformUsd = platform.stream().mapToDouble(ChatCostSummary.ServiceSpend::costUsd).sum();

            return new ChatCostSummary(
                    month,
                    repository.totalCostForMonth(month),
                    repository.totalCostAllTime(),
                    repository.totalCallsForMonth(month),
                    repository.totalCallsAllTime(),
                    models,
                    platform,
                    platformUsd);
        } catch (Exception e) {
            log.warn("[ChatSpend] Could not read the chat cost summary: {}", e.getMessage());
            return new ChatCostSummary(month, 0, 0, 0, 0, List.of(), List.of(), 0);
        }
    }

    /**
     * Platform spend since the first instant of this calendar month, in the server's own zone -
     * the same boundary ChatSpendMonth's rows are stamped with, so the two halves of the screen
     * cover the same period rather than one covering a rolling window.
     */
    private List<ChatCostSummary.ServiceSpend> platformSpendThisMonth() {
        try {
            Instant monthStart = YearMonth.now(clock).atDay(1)
                    .atStartOfDay(clock.getZone()).toInstant();
            return platformSpendService.totalsByServiceSince(monthStart).entrySet().stream()
                    .map(e -> new ChatCostSummary.ServiceSpend(
                            e.getKey().name(), e.getValue().costUsd(), e.getValue().calls()))
                    .filter(s -> s.calls() > 0)
                    .sorted(Comparator.comparingDouble(ChatCostSummary.ServiceSpend::costUsd).reversed())
                    .toList();
        } catch (Exception e) {
            log.warn("[ChatSpend] Could not read platform spend for this month: {}", e.getMessage());
            return List.of();
        }
    }
}
