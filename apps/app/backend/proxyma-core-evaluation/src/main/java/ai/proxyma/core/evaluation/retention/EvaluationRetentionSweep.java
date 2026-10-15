package ai.proxyma.core.evaluation.retention;


import ai.proxyma.core.evaluation.repository.ChatTurnMetricRepository;
import ai.proxyma.core.evaluation.repository.SearchStageMetricRepository;
import ai.proxyma.core.settings.dto.RetentionSettings;
import ai.proxyma.core.settings.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Prunes the latency records System > Performance reads.
 *
 * These are the fastest-growing tables in the deployment - one ChatTurnMetric per chat turn, one
 * SearchStageMetric per search - and nothing ever deleted a row from either.
 */@Component
public class EvaluationRetentionSweep {

    private static final Logger log = LoggerFactory.getLogger(EvaluationRetentionSweep.class);

    private final ChatTurnMetricRepository chatTurnMetricRepository;
    private final SearchStageMetricRepository searchStageMetricRepository;
    private final SettingsService settingsService;

    public EvaluationRetentionSweep(ChatTurnMetricRepository chatTurnMetricRepository,
                                    SearchStageMetricRepository searchStageMetricRepository,
                                    SettingsService settingsService) {
        this.chatTurnMetricRepository = chatTurnMetricRepository;
        this.searchStageMetricRepository = searchStageMetricRepository;
        this.settingsService = settingsService;
    }

    // No @Transactional here on purpose. A bare one resolves the @PRIMARY transaction manager,
    // which on desktop is localTransactionManager - a different EntityManagerFactory from the one
    // these repositories are bound to, so it wrapped the sweep in a transaction that was never
    // theirs and the deletes failed on every run. Each repository method now carries its own
    // @Transactional, which the repository proxy runs against the manager its own
    // @EnableJpaRepositories names. Per-table transactions are also the right granularity: these
    // are independent prunes, not one atomic unit, and one failing should not undo the others.
    @Scheduled(fixedRate = 86_400_000, initialDelay = 900_000)
    public void sweep() {
        try {
            int days = settingsService.getSettings(RetentionSettings.PREFIX, RetentionSettings.class)
                    .getEvaluationDays();
            if (days <= 0) return;   // 0 = keep forever

            Instant cutoff = Instant.now().minus(Duration.ofDays(days));
            int turns = chatTurnMetricRepository.deleteRecordedBefore(cutoff);
            int searches = searchStageMetricRepository.deleteRecordedBefore(cutoff);

            // Logged even at zero: a sweep that silently does nothing is indistinguishable from one
            // that never ran, which this codebase has been caught by before.
            log.info("[Retention] evaluation data older than {} day(s): {} turn metric(s), "
                            + "{} search metric(s) deleted",
                    days, turns, searches);
        } catch (Exception e) {
            log.warn("[Retention] evaluation sweep failed", e);
        }
    }
}
