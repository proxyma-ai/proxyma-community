package ai.proxyma.core.settings.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

// Per-user "has this user finished the onboarding walkthrough" flag (see the tour feature in
// frontend/src/features/tour/). Always user-scoped - there is no meaningful "global"
// value for whether one particular user has seen the tour - so this rides the same per-user
// settings overlay as VIEWER_PREFIXES entries (SettingsController).
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OnboardingSettings {

    public static final String PREFIX = "onboarding";

    @JsonProperty("tour-completed")
    private boolean tourCompleted = false;

    // An edition's page walkthroughs, each shown once on the first visit to its page (the tour feature's
    // useWalkthroughOnFirstVisit, which names the field `<name>-tour-completed`). Undeclared, the write was refused as an
    // unknown field and the walkthrough came back on every visit (2026-11-10).
    @JsonProperty("youtube-tour-completed")
    private boolean youtubeTourCompleted = false;

    @JsonProperty("constellations-tour-completed")
    private boolean constellationsTourCompleted = false;

    public boolean isTourCompleted() { return tourCompleted; }
    public void setTourCompleted(boolean tourCompleted) { this.tourCompleted = tourCompleted; }
    public boolean isYoutubeTourCompleted() { return youtubeTourCompleted; }
    public void setYoutubeTourCompleted(boolean youtubeTourCompleted) { this.youtubeTourCompleted = youtubeTourCompleted; }
    public boolean isConstellationsTourCompleted() { return constellationsTourCompleted; }
    public void setConstellationsTourCompleted(boolean constellationsTourCompleted) {
        this.constellationsTourCompleted = constellationsTourCompleted;
    }
}
