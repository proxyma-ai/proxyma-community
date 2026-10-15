package ai.proxyma.core.settings.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

// Global defaults for the server-mode per-user monthly token $ quota (see UserTokenQuotaService,
// proxyma-core-auth). Mirrors StorageSettings' shape: a global default plus a per-user override
// living on User itself (User.monthlyTokenQuotaUsd), not here.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QuotaSettings {

    public static final String PREFIX = "quota";

    /**
     * Ceiling on what the WHOLE DEPLOYMENT may spend on LLM calls in a quota month - every user's
     * usage plus all platform usage (embedding, OCR, memory digest) combined.
     *
     * Zero, the default, disables it entirely, which is what every existing deployment gets on
     * upgrade. A non-zero value is a HARD stop: once reached, every LLM call is refused, including
     * ingest. Refused ingest work is marked FAILED with the reason and is auto-retried by the
     * existing sweep once the quota resets or is raised, so nothing is silently lost.
     *
     * Distinct from the per-user quotas below, which bound one person and never block chat. This
     * one bounds the bill.
     */
    @JsonProperty("global-monthly-quota-usd")
    private double globalMonthlyQuotaUsd = 0.0;

    @JsonProperty("default-monthly-quota-usd")
    private double defaultMonthlyQuotaUsd = 20.0;

    // Day-of-month (1-28, so it exists in every month regardless of length) the monthly spend
    // cycle resets on - global, not per-user. Bounded to 28 deliberately so "reset on the 30th"
    // can never skip February.
    @JsonProperty("quota-reset-day")
    private int quotaResetDay = 1;

    // Global default for the new rolling 5-hour spend window (Anthropic-style enforcement) -
    // per-user override lives on User.fiveHourQuotaUsd, mirrors defaultMonthlyQuotaUsd's shape.
    @JsonProperty("default-5h-quota-usd")
    private double default5hQuotaUsd = 5.0;

    // Global default for the rolling 7-day (weekly) spend window - per-user override on
    // User.weeklyQuotaUsd.
    @JsonProperty("default-weekly-quota-usd")
    private double defaultWeeklyQuotaUsd = 50.0;

    public double getDefaultMonthlyQuotaUsd() { return defaultMonthlyQuotaUsd; }
    public void setDefaultMonthlyQuotaUsd(double v) { this.defaultMonthlyQuotaUsd = v; }
    public int getQuotaResetDay() { return quotaResetDay; }
    public void setQuotaResetDay(int v) { this.quotaResetDay = v; }
    public double getDefault5hQuotaUsd() { return default5hQuotaUsd; }
    public void setDefault5hQuotaUsd(double v) { this.default5hQuotaUsd = v; }
    public double getDefaultWeeklyQuotaUsd() { return defaultWeeklyQuotaUsd; }
    public void setDefaultWeeklyQuotaUsd(double v) { this.defaultWeeklyQuotaUsd = v; }

    public double getGlobalMonthlyQuotaUsd() { return globalMonthlyQuotaUsd; }
    public void setGlobalMonthlyQuotaUsd(double globalMonthlyQuotaUsd) {
        this.globalMonthlyQuotaUsd = globalMonthlyQuotaUsd;
    }
}
