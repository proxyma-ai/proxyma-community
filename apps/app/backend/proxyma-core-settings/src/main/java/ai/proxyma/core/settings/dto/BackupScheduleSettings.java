package ai.proxyma.core.settings.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

// Admin-configurable schedule for BackupService's combined pgBackRest + pg_dump run (server
// profile only, but this DTO itself has no Spring/JPA dependency so it lives alongside every
// other settings DTO here). Read/written via the generic
// GET/PATCH/DELETE /api/v1/settings/{prefix}/{field} API (SettingsController) - "backup-schedule"
// is not in VIEWER_PREFIXES/SUPER_ADMIN_PREFIXES/BLOCKED_PREFIXES there, so it falls through to
// the default admin-only checkAccess() gate. No dedicated controller code needed.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BackupScheduleSettings {

    public static final String PREFIX = "backup-schedule";

    public enum Frequency { DAILY, WEEKLY, MONTHLY }

    // DAILY / WEEKLY / MONTHLY.
    @JsonProperty("frequency")
    private String frequency = Frequency.DAILY.name();

    // Used only when frequency=WEEKLY. One of java.time.DayOfWeek's enum names ("MONDAY".."SUNDAY").
    @JsonProperty("day-of-week")
    private String dayOfWeek;

    // Used only when frequency=MONTHLY. 1-28 (sidesteps month-length edge cases).
    @JsonProperty("day-of-month")
    private Integer dayOfMonth;

    // 24h clock, hour of day the scheduled run fires.
    @JsonProperty("hour")
    private Integer hour = 3;

    @JsonProperty("minute")
    private Integer minute = 0;

    // Extra trigger: fire a run as soon as the Postgres database has grown by at least this many
    // bytes since the last successful backup, independent of the time-based schedule above.
    // Null/absent means this trigger is off.
    @JsonProperty("growth-threshold-bytes")
    private Long growthThresholdBytes;

    public String getFrequency() { return frequency; }
    public void setFrequency(String frequency) { this.frequency = frequency; }

    public String getDayOfWeek() { return dayOfWeek; }
    public void setDayOfWeek(String dayOfWeek) { this.dayOfWeek = dayOfWeek; }

    public Integer getDayOfMonth() { return dayOfMonth; }
    public void setDayOfMonth(Integer dayOfMonth) { this.dayOfMonth = dayOfMonth; }

    public Integer getHour() { return hour; }
    public void setHour(Integer hour) { this.hour = hour; }

    public Integer getMinute() { return minute; }
    public void setMinute(Integer minute) { this.minute = minute; }

    public Long getGrowthThresholdBytes() { return growthThresholdBytes; }
    public void setGrowthThresholdBytes(Long growthThresholdBytes) { this.growthThresholdBytes = growthThresholdBytes; }
}
