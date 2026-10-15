package ai.proxyma.core.settings.usage;

import ai.proxyma.core.common.audit.AuditCategory;
import ai.proxyma.core.common.audit.AuditEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The single place an LLM call asks "is there budget left?".
 *
 * CHECKED IN THE GATEWAY, since 2026-09-29 (DefaultLlmGateway.checkBudget), before every chat call,
 * session call and stream, metered embedding and rerank - and by the metered embedding bean. This
 * said it was checked at each call site because "there is no one choke point"; the gateway became
 * that choke point, and the per-call-site version had missed the guardrail classifier, MCP
 * sub-agents, "Condense now", search over MCP and REST, both non-LLM rerank tiers and re-index
 * embeddings. Call sites that check as well do so only for their own wording.
 *
 * What it compares against is the per-call ledger (GlobalQuotaService), which the same gateway
 * writes - so what is checked and what is counted are the same calls.
 *
 * BLOCKS EVERYTHING, including ingest, which is what was asked for. The consequence is handled
 * rather than ignored: on the ingest path the exception reaches SyncService.markFailed(), which
 * records the reason on the document and schedules it for auto-retry, so work refused for budget
 * resumes on its own once the quota resets or is raised. A refused document is FAILED with an
 * explanation - never left looking indexed when it is not.
 *
 * WHAT ASKING COSTS, since 2026-10-03. Being in the gateway put this before every chat call,
 * stream, embedding and rerank, and each check totalled the month's ledger - a GROUP BY over every
 * llm_calls row since the reset day, slower every day of the month - before it looked at whether a
 * ceiling was configured at all. The ceiling is off by default, so every deployment paid for a
 * query whose answer could not matter. Now: no ceiling, no query; and with one, the total is read
 * at most once every few seconds and shared by every call in between. The price is that a
 * deployment can run past its ceiling by what it spends in that interval - against a figure for a
 * month.
 */
@Component
public class GlobalQuotaGate {

    private static final Logger log = LoggerFactory.getLogger(GlobalQuotaGate.class);

    /** How long one reading of the ledger answers for. */
    static final long REMEMBER_MS = 5_000;

    private final GlobalQuotaService globalQuotaService;
    private java.util.function.LongSupplier clock = System::currentTimeMillis;

    // The last reading and when it was taken. One reference, replaced whole, so a reader sees a
    // reading and its time together; two threads reading at once cost one extra query, not a lock.
    private volatile Reading last;

    private record Reading(GlobalQuotaService.GlobalQuotaStatus status, long at) {}

    public GlobalQuotaGate(GlobalQuotaService globalQuotaService) {
        this.globalQuotaService = globalQuotaService;
    }

    /** For a test that needs time to pass. */
    void setClock(java.util.function.LongSupplier clock) {
        this.clock = clock;
    }

    // Null when no ceiling is configured. A reading taken under another ceiling is not reused, so
    // raising the figure in Settings lets work resume at once rather than a few seconds later.
    private GlobalQuotaService.GlobalQuotaStatus reading() {
        double limit = globalQuotaService.limitUsd();
        if (limit <= 0) return null;
        long now = clock.getAsLong();
        Reading known = last;
        if (known != null && known.status().limitUsd() == limit && now - known.at() < REMEMBER_MS) {
            return known.status();
        }
        GlobalQuotaService.GlobalQuotaStatus status = globalQuotaService.status();
        last = new Reading(status, now);
        return status;
    }

    /**
     * Refuses the call when the deployment-wide ceiling has been reached.
     *
     * @param activity what is being refused, phrased to complete "... so {activity} was not run"
     *                 (e.g. "indexing this document", "this message"). It reaches the operator on
     *                 the document row or in the conversation, so it should name the work, not the
     *                 subsystem.
     * @throws GlobalQuotaExceededException when there is no budget left
     */
    public void checkOrRefuse(String activity) {
        GlobalQuotaService.GlobalQuotaStatus status;
        try {
            status = reading();
        } catch (Exception e) {
            // A gate that halts the product because its own query failed is worse than one that
            // briefly fails to enforce. Fail open, loudly.
            log.warn("[GlobalQuota] Could not evaluate the quota before {} - allowing: {}",
                    activity, e.getMessage());
            return;
        }
        if (status == null || !status.exhausted()) return;

        log.warn("[GlobalQuota] Refusing {} - ${} of ${} spent this quota month "
                        + "(users ${}, platform ${})",
                activity, round(status.totalUsd()), round(status.limitUsd()),
                round(status.userUsd()), round(status.platformUsd()));
        AuditEntry.of(AuditCategory.LIMITS, "spending-limit.reached")
                .outcome(AuditEntry.DENIED)
                .target(activity)
                .summary("Refused " + activity + " - the monthly spending limit of $" + round(status.limitUsd())
                        + " is reached ($" + round(status.totalUsd()) + " spent)")
                .detail("limitUsd", status.limitUsd())
                .detail("spentUsd", status.totalUsd())
                .record();
        throw new GlobalQuotaExceededException(activity, status.limitUsd(), status.totalUsd());
    }

    /** True when there is no budget left, for callers that prefer to degrade rather than throw. */
    public boolean isExhausted() {
        try {
            GlobalQuotaService.GlobalQuotaStatus status = reading();
            return status != null && status.exhausted();
        } catch (Exception e) {
            log.warn("[GlobalQuota] Could not evaluate the global quota: {}", e.getMessage());
            return false;
        }
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
