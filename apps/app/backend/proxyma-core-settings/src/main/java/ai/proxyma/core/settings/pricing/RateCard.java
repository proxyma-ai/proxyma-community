package ai.proxyma.core.settings.pricing;

/**
 * Everything one model's price is made of, in dollars per million tokens - from a row the operator
 * entered (ModelPricing) or from the built-in list (ListPrices). A rate of zero or null means "not
 * given"; CostCalculator says what each one falls back to.
 *
 * @param cacheWrite         a FIVE-minute cache write (Anthropic 1.25x input; OpenAI GPT-5.6 and
 *                           later 1.25x; elsewhere writes cost what input does)
 * @param cacheWriteLong     a ONE-hour write (Anthropic 2x input), null for "1.6 times cacheWrite",
 *                           which is Anthropic's ratio
 * @param longContextTokens  the prompt size above which the long* rates price the WHOLE call, or 0
 *                           for none (Claude Haiku 5.5 above 100K, Gemini Pro above 200K, GPT-5.5
 *                           above 272K)
 * @param schedule           a price that depends on the time of the call
 * @param priorityMultiplier what a response served on the "priority" tier costs against the
 *                           standard one, or null for the provider's usual multiplier
 */
public record RateCard(double input, double output, double cacheRead, double cacheWrite, Double cacheWriteLong,
                       long longContextTokens, Double longInput, Double longOutput, Double longCacheRead,
                       Double longCacheWrite, PricingSchedule schedule, Double priorityMultiplier) {

    public static RateCard of(ModelPricing row) {
        return new RateCard(row.getInputPricePerMillion(), row.getOutputPricePerMillion(),
                row.getCacheReadPricePerMillion(), row.getCacheWritePricePerMillion(),
                row.getCacheWriteLongPricePerMillion(),
                row.getLongContextThresholdTokens() == null ? 0 : row.getLongContextThresholdTokens(),
                row.getLongInputPricePerMillion(), row.getLongOutputPricePerMillion(),
                row.getLongCacheReadPricePerMillion(), row.getLongCacheWritePricePerMillion(),
                PricingSchedule.parse(row.getPricingSchedule()), null);
    }
}
