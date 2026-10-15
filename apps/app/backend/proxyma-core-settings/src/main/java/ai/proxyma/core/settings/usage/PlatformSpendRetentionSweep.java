package ai.proxyma.core.settings.usage;

import ai.proxyma.core.settings.dto.RetentionSettings;
import ai.proxyma.core.settings.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Prunes the two per-call usage tables past their retention window: platform spend, and - since
 * 2026-09-16 - the per-call LLM ledger.
 *
 * Owned by this module rather than a central sweep service, matching how every other table here is
 * handled: the module that writes the rows decides when they go. A single cross-module sweeper
 * would have to reach into four modules' repositories and would break the dependency graph to do
 * it.
 *
 * Runs daily, offset from startup so it never competes with boot.
 */
@Component
public class PlatformSpendRetentionSweep {

    private static final Logger log = LoggerFactory.getLogger(PlatformSpendRetentionSweep.class);

    private final PlatformSpendRepository repository;
    private final LlmCallRepository callRepository;
    private final SettingsService settingsService;
    private final GlobalQuotaService globalQuota;

    public PlatformSpendRetentionSweep(PlatformSpendRepository repository,
                                       LlmCallRepository callRepository,
                                       SettingsService settingsService,
                                       GlobalQuotaService globalQuota) {
        this.repository = repository;
        this.callRepository = callRepository;
        this.settingsService = settingsService;
        this.globalQuota = globalQuota;
    }

    // No @Transactional here on purpose. A bare one resolves the @PRIMARY transaction manager,
    // which on desktop is localTransactionManager - a different EntityManagerFactory from the one
    // these repositories are bound to, so it wrapped the sweep in a transaction that was never
    // theirs and the deletes failed on every run. Each repository method now carries its own
    // @Transactional, which the repository proxy runs against the manager its own
    // @EnableJpaRepositories names. Per-table transactions are also the right granularity: these
    // are independent prunes, not one atomic unit, and one failing should not undo the others.
    @Scheduled(fixedRate = 86_400_000, initialDelay = 600_000)
    public void sweep() {
        try {
            int days = settingsService.getSettings(RetentionSettings.PREFIX, RetentionSettings.class)
                    .getUsageDays();
            if (days <= 0) return;   // 0 = keep forever

            Instant cutoff = Instant.now().minus(Duration.ofDays(days));
            int deleted = repository.deleteRecordedBefore(cutoff);
            // Logged even at zero: a sweep that silently does nothing is indistinguishable from one
            // that never ran, which this codebase has been caught by before.
            log.info("[Retention] platform spend older than {} day(s): {} row(s) deleted", days, deleted);

            // THE ONE THING BOUNDING THE PER-CALL LEDGER. One row per model call is the trade the
            // rollup tables deliberately avoided, so this is the setting that keeps it honest -
            // swept separately, because a failure to prune one table must not leave the other
            // unpruned and unreported.
            //
            // NEVER INSIDE THE QUOTA MONTH, since 2026-09-29: the global ceiling totals this table
            // (GlobalQuotaService), so a retention shorter than a month would let a deployment
            // spend past its limit simply by forgetting what it spent.
            Instant windowStart = globalQuota.currentWindowStart();
            Instant callCutoff = cutoff.isBefore(windowStart) ? cutoff : windowStart;
            int calls = callRepository.deleteRecordedBefore(callCutoff);
            log.info("[Retention] LLM calls older than {} day(s): {} row(s) deleted", days, calls);
        } catch (Exception e) {
            // Stack included: the previous message-only form reported "Null return value from
            // advice does not match primitive return type" for months with nothing to locate it by.
            log.warn("[Retention] usage sweep failed (platform spend and/or LLM calls)", e);
        }
    }
}
