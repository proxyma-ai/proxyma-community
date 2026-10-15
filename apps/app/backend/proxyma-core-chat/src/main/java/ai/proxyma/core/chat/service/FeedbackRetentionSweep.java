package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.repository.FeedbackRepository;
import ai.proxyma.core.settings.dto.RetentionSettings;
import ai.proxyma.core.settings.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Prunes submitted feedback, and its attachments, past {@code retention.feedback-days}.
 *
 * <p>THE SETTING HAD NO SWEEP (swx-05). Settings > Data retention offered "Feedback (days)" and
 * nothing read it, so every submission - up to 20 MB of attachments each, from a visitor too
 * while the door was open - was kept for ever whatever the field said.
 *
 * <p>No @Transactional here, for the reason AuditRetentionSweep gives: the repository's delete
 * carries its own, on the manager its package is bound to.
 */
@Component
class FeedbackRetentionSweep {

    private static final Logger log = LoggerFactory.getLogger(FeedbackRetentionSweep.class);

    private final FeedbackRepository repository;
    private final SettingsService settingsService;

    FeedbackRetentionSweep(FeedbackRepository repository, SettingsService settingsService) {
        this.repository = repository;
        this.settingsService = settingsService;
    }

    @Scheduled(fixedRate = 86_400_000, initialDelay = 720_000)
    void sweep() {
        try {
            int days = settingsService.getSettings(RetentionSettings.PREFIX, RetentionSettings.class)
                    .getFeedbackDays();
            if (days <= 0) return;   // 0 = keep forever
            int deleted = repository.deleteSubmittedBefore(Instant.now().minus(Duration.ofDays(days)));
            // Logged even at zero: a sweep that silently does nothing cannot be told from one that
            // never ran.
            log.info("[Retention] feedback older than {} day(s): {} row(s) deleted", days, deleted);
        } catch (Exception e) {
            log.warn("[Retention] feedback sweep failed", e);
        }
    }
}
