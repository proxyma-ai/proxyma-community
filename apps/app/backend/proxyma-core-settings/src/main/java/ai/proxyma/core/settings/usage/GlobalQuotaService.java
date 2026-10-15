package ai.proxyma.core.settings.usage;

import ai.proxyma.core.settings.dto.QuotaSettings;
import ai.proxyma.core.settings.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * The deployment-wide LLM spend ceiling: every user's usage plus all platform usage.
 *
 * Distinct from the per-user quotas, which bound one person and deliberately never block chat.
 * This one bounds the bill, and it is a HARD stop - a deployment that has spent its month's budget
 * stops making LLM calls rather than continuing to spend.
 *
 * DISABLED BY DEFAULT (a ceiling of zero). Every existing deployment upgrades into exactly the
 * behaviour it had, and an administrator opts in by setting a figure.
 */
@Service
public class GlobalQuotaService {

    private static final Logger log = LoggerFactory.getLogger(GlobalQuotaService.class);

    private final SettingsService settingsService;
    private final LlmCallRepository llmCalls;

    // COUNTS THE PER-CALL LEDGER, since 2026-09-29. It summed PlatformSpend (embedding, OCR, graph,
    // space facts) plus UserSpendPort (chat spend, server only) - so on DESKTOP the ceiling never
    // counted a single chat message, sub-agent, title, condensation, query expansion, rerank,
    // guardrail or workflow call, while Settings promised chat would be refused past it. On the
    // server the user half came from a table pruned after eight days, and guest spend was in
    // neither. Every model call the gateway or the metered embedding bean makes is written to
    // llm_calls with its cost, so that table is the one number that cannot miss a role.
    public GlobalQuotaService(SettingsService settingsService, LlmCallRepository llmCalls) {
        this.settingsService = settingsService;
        this.llmCalls = llmCalls;
    }

    // Setter-injected and optional, so the service built by hand in tests uses the default split.
    private CostCenterService costCenters;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCostCenters(CostCenterService costCenters) {
        this.costCenters = costCenters;
    }

    /**
     * Where the current quota month began, honouring the configured reset day.
     *
     * A reset day past the length of the current month (say 28 in February, or any day-of-month a
     * short month lacks) is clamped rather than rolled forward - rolling would put the window start
     * in the future and make the month read as spending nothing at all.
     */
    public Instant currentWindowStart() {
        QuotaSettings q = quota();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int resetDay = Math.max(1, Math.min(28, q.getQuotaResetDay()));
        LocalDate start = today.withDayOfMonth(Math.min(resetDay, today.lengthOfMonth()));
        if (start.isAfter(today)) start = start.minusMonths(1);
        return start.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** The configured ceiling in USD; 0 when the quota is off. A settings read, never the ledger. */
    public double limitUsd() {
        return quota().getGlobalMonthlyQuotaUsd();
    }

    /**
     * A single read of where the deployment stands. It totals the ledger for the quota month, so
     * it is for a page that shows the figures - the gate asks {@link #limitUsd()} first and
     * remembers the answer for a few seconds (GlobalQuotaGate).
     */
    public GlobalQuotaStatus status() {
        QuotaSettings q = quota();
        double limit = q.getGlobalMonthlyQuotaUsd();
        Instant since = currentWindowStart();

        double platform = 0;
        double users = 0;
        // User or platform by the COST CENTER of the call's role (CostCenterSettings), not merely by
        // whether a person was behind it: a user's indexing is the platform's cost by default.
        for (Object[] row : llmCalls.costBySubjectKindAndRoleSince(since)) {
            double cost = row[2] instanceof Number n ? n.doubleValue() : 0;
            boolean person = row[0] != ai.proxyma.core.common.spend.SpendSubject.Kind.SYSTEM;
            String role = row[1] == null ? null : row[1].toString();
            boolean userPays = costCenters != null ? costCenters.chargedToUser(role) : "CHAT".equals(role);
            if (person && userPays) users += cost;
            else platform += cost;
        }

        double total = platform + users;
        boolean exhausted = limit > 0 && total >= limit;
        return new GlobalQuotaStatus(limit, platform, users, exhausted);
    }

    /** True when the ceiling is configured and has been reached. */
    public boolean isExhausted() {
        try {
            // Off is the default, and off must not cost a query.
            if (limitUsd() <= 0) return false;
            return status().exhausted();
        } catch (Exception e) {
            // Never block on a bookkeeping failure. A quota that halts the product because its own
            // query threw is worse than one that briefly fails to enforce.
            log.warn("[GlobalQuota] Could not evaluate the global quota: {}", e.getMessage());
            return false;
        }
    }

    private QuotaSettings quota() {
        return settingsService.getSettings(QuotaSettings.PREFIX, QuotaSettings.class);
    }

    /**
     * @param limitUsd    the configured ceiling; 0 means the quota is disabled
     * @param platformUsd spend the platform pays for - no person behind it, or a service whose cost
     *                    center is the platform (CostCenterSettings)
     * @param userUsd     spend charged to a user or an anonymous visitor by a user-paid service
     * @param exhausted   whether the ceiling is configured AND reached
     */
    public record GlobalQuotaStatus(double limitUsd, double platformUsd, double userUsd, boolean exhausted) {

        public double totalUsd() { return platformUsd + userUsd; }

        public boolean enabled() { return limitUsd > 0; }

        /** 0..1+ - can exceed 1, since the last call before the ceiling may cross it. */
        public double fraction() { return limitUsd > 0 ? totalUsd() / limitUsd : 0; }
    }
}
