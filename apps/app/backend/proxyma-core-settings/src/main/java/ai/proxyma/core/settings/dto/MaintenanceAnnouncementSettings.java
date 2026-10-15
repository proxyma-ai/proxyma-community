package ai.proxyma.core.settings.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

// Durable (survives JVM restart) admin-authored banner announcing upcoming or in-progress
// maintenance, independent of MaintenanceModeService's in-memory hard-gate flag - an admin may
// schedule this hours/days ahead of actually flipping the hard gate. Read publicly via
// GET /api/v1/maintenance/announcement; saved via the generic
// PATCH /api/v1/settings/maintenance-announcement/{field} endpoint (SettingsController), which
// defaults this prefix to admin-only since it's not in VIEWER_PREFIXES/SUPER_ADMIN_PREFIXES/
// BLOCKED_PREFIXES.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MaintenanceAnnouncementSettings {

    public static final String PREFIX = "maintenance-announcement";

    @JsonProperty("banner-enabled")
    private boolean bannerEnabled = false;

    @JsonProperty("reason")
    private String reason;

    // ISO-8601 instant string (e.g. "2026-07-15T09:00:00Z"), nullable - "no scheduled start
    // announced yet" is a valid state distinct from "starts now".
    @JsonProperty("scheduled-start")
    private String scheduledStart;

    // ISO-8601 instant string, nullable - "no ETA given" is a valid state.
    @JsonProperty("expected-back-at")
    private String expectedBackAt;

    public boolean isBannerEnabled() { return bannerEnabled; }
    public void setBannerEnabled(boolean v) { this.bannerEnabled = v; }

    public String getReason() { return reason; }
    public void setReason(String v) { this.reason = v; }

    public String getScheduledStart() { return scheduledStart; }
    public void setScheduledStart(String v) { this.scheduledStart = v; }

    public String getExpectedBackAt() { return expectedBackAt; }
    public void setExpectedBackAt(String v) { this.expectedBackAt = v; }
}
