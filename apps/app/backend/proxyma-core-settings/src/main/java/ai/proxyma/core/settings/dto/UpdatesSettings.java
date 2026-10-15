package ai.proxyma.core.settings.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * When Proxyma looks for a new version, and what it does when it finds one.
 *
 * <p>TWO SEPARATE DECISIONS, and they used to be one. {@code auto-update} gated the CHECK as well
 * as the download, so an operator who did not want updates installing behind their back also lost
 * the ability to be told one existed - the only way to find out was to visit the website. Checking
 * costs one HTTPS request against the releases API and downloads nothing; installing is the part
 * worth a switch. They are now {@link #checkFrequencyHours} and {@link #autoUpdate}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UpdatesSettings {

    public static final String PREFIX = "updates";

    /**
     * How often to look, in hours. {@code 0} means never look on a schedule - a manual check from
     * Settings still works, and is the point of keeping the two apart.
     *
     * <p>24 rather than the 1 this shipped with. An hourly poll of the releases API from every
     * installation is a request per hour per machine to learn something that changes every few
     * weeks, and the only thing a user gains from hearing about a release at 14:00 rather than
     * 09:00 the next day is nothing at all. The value is clamped rather than validated: see
     * {@link #getCheckFrequencyHours()}.
     */
    @JsonProperty("check-frequency-hours")
    private int checkFrequencyHours = 24;

    /**
     * Download and stage a new version as soon as one is found, so applying it is one click and no
     * waiting. Off means the check still reports what exists and downloads nothing.
     *
     * <p>The name is unchanged from when it meant both things, deliberately: this prefix is stored
     * as JSON and a renamed key would read as absent, silently returning every deployment to the
     * default. Its MEANING is narrower now, which costs nothing - true still means "keep me up to
     * date without asking".
     */
    @JsonProperty("auto-update")
    private boolean autoUpdate = true;

    /**
     * A clamp, not validation. A settings blob is edited by a UI, an import, and a person with a
     * text editor; a negative or absurd value here would otherwise become a check every
     * millisecond or an integer overflow in the scheduler.
     */
    public int getCheckFrequencyHours() {
        if (checkFrequencyHours <= 0) return 0;
        return Math.min(checkFrequencyHours, 24 * 30);
    }

    public void setCheckFrequencyHours(int v) { this.checkFrequencyHours = v; }

    /**
     * Also consider DEVELOPMENT builds - the prereleases every push to the dev branch publishes to
     * the releases repository as {@code v<next>-dev<N>}. Off by default: they are untested beyond
     * the pipeline, and a customer should never find one installed without having asked.
     *
     * <p>Turning it off does not go back to the last release. A dev build is numbered above the
     * release it follows, so the installation stays on it until a newer release is published.
     */
    @JsonProperty("dev-builds")
    private boolean devBuilds;

    public boolean isAutoUpdate() { return autoUpdate; }
    public void setAutoUpdate(boolean v) { this.autoUpdate = v; }

    public boolean isDevBuilds() { return devBuilds; }
    public void setDevBuilds(boolean v) { this.devBuilds = v; }
}
