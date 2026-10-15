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

    public boolean isTourCompleted() { return tourCompleted; }
    public void setTourCompleted(boolean tourCompleted) { this.tourCompleted = tourCompleted; }
}
