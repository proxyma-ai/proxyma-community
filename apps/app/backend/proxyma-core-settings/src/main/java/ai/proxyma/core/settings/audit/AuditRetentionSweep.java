package ai.proxyma.core.settings.audit;

import ai.proxyma.core.settings.dto.RetentionSettings;
import ai.proxyma.core.settings.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Prunes {@code audit_events} past {@code retention.audit-days}. The only thing that removes a row
 * on Enterprise Server, where the page has no Clear.
 *
 * <p>No @Transactional here, for the reason PlatformSpendRetentionSweep gives: the repository's
 * delete carries its own, on the manager its package is bound to.
 */
@Component
public class AuditRetentionSweep {

    private static final Logger log = LoggerFactory.getLogger(AuditRetentionSweep.class);

    private final AuditEventRepository repository;
    private final SettingsService settingsService;

    public AuditRetentionSweep(AuditEventRepository repository, SettingsService settingsService) {
        this.repository = repository;
        this.settingsService = settingsService;
    }

    @Scheduled(fixedRate = 86_400_000, initialDelay = 660_000)
    public void sweep() {
        try {
            int days = settingsService.getSettings(RetentionSettings.PREFIX, RetentionSettings.class)
                    .getAuditDays();
            if (days <= 0) return;   // 0 = keep forever
            int deleted = repository.deleteRecordedBefore(Instant.now().minus(Duration.ofDays(days)));
            // Logged even at zero, as every sweep here is: one that silently does nothing cannot be
            // told from one that never ran.
            log.info("[Retention] audit entries older than {} day(s): {} row(s) deleted", days, deleted);
        } catch (Exception e) {
            log.warn("[Retention] audit sweep failed", e);
        }
    }
}
