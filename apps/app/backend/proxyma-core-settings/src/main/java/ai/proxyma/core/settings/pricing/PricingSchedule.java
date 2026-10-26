package ai.proxyma.core.settings.pricing;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/** A model whose price depends on WHEN it is called. */
public enum PricingSchedule {

    /** One price at every hour. */
    NONE,

    /**
     * DeepSeek's: the rates entered are the PEAK price, and outside peak hours every rate is half.
     * Peak is 01:00-04:00 and 06:00-10:00 UTC, Monday to Friday (api-docs.deepseek.com, pricing,
     * checked 2026-10-22).
     */
    DEEPSEEK_OFF_PEAK;

    /** What the rates are multiplied by for a call made at {@code at}. */
    public double multiplierAt(Instant at) {
        if (this != DEEPSEEK_OFF_PEAK || at == null) return 1.0;
        return deepSeekPeak(at) ? 1.0 : 0.5;
    }

    static boolean deepSeekPeak(Instant at) {
        ZonedDateTime utc = at.atZone(ZoneOffset.UTC);
        DayOfWeek day = utc.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false;
        int hour = utc.getHour();
        return (hour >= 1 && hour < 4) || (hour >= 6 && hour < 10);
    }

    /** A stored value as a schedule; anything unknown is NONE. */
    public static PricingSchedule parse(String value) {
        if (value == null || value.isBlank()) return NONE;
        try {
            return valueOf(value.trim());
        } catch (IllegalArgumentException unknown) {
            return NONE;
        }
    }
}
