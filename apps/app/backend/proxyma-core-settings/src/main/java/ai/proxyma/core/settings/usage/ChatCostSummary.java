package ai.proxyma.core.settings.usage;

import java.util.List;

/**
 * What the desktop cost view renders: this calendar month as the headline, lifetime beside it, and
 * the month broken down by the model that did the work.
 *
 * No quota, no ceiling, no reset day. Those exist because a deployment shares one budget between
 * accounts it has to hold to it - desktop has one user and one machine, and the honest question
 * there is "what has this cost me", not "how much am I allowed". The server's own Usages page
 * continues to answer the other one.
 *
 * Every figure is an ESTIMATE derived from the configured per-model rates and Proxyma's own token
 * counts, never from a provider's bill - the wording of that caveat lives once, in Settings > Cost
 * Settings, and the view links to it rather than restating it.
 */
public record ChatCostSummary(
        /** "YYYY-MM" the monthUsd/monthCalls figures cover, so the UI can name the month it is showing. */
        String month,
        double monthUsd,
        double lifetimeUsd,
        long monthCalls,
        long lifetimeCalls,
        /** This month only, dearest first. Empty before anything has been chatted. */
        List<ModelSpend> models,
        /**
         * What indexing and processing cost this month - embedding, OCR, the memory digest. Not
         * chat, and not caused by a turn anyone was waiting on, so it is reported beside the chat
         * figures rather than added into them.
         *
         * MONTH ONLY, no lifetime counterpart, and that asymmetry is deliberate: platform_spend is
         * a per-call ledger that the retention sweep ages out (retention.usage-days, 365 by
         * default), so a "lifetime" drawn from it would silently mean "since the sweep" - the exact
         * trap ChatSpendMonth is a rollup to avoid. A figure that quietly changes meaning is worse
         * than one that is not offered.
         */
        List<ServiceSpend> platform,
        double platformMonthUsd
) {
    /**
     * One model's share of the month. EVERY model that did work appears, including one nobody has
     * configured a rate for - it is reported with its real token counts and a costUsd of 0.
     *
     * @param priced whether a rate is configured for this provider/model. A zero cost means two
     *               different things - "this rate says it is free" and "there is no rate" - and only
     *               the second is something the user can fix, so the server answers it here instead
     *               of leaving the UI to guess from the cost being zero. This replaces a `local`
     *               flag that asserted a model ran on the operator's own hardware; that came from
     *               the per-instance "Runs locally" setting, which is gone.
     */
    public record ModelSpend(
            String provider,
            String model,
            double costUsd,
            long inputTokens,
            long outputTokens,
            long cacheReadTokens,
            long cacheWriteTokens,
            long calls,
            boolean priced
    ) {}

    /** One platform activity's share of the month - see PlatformService for what each one is. */
    public record ServiceSpend(String service, double costUsd, long calls) {}
}
