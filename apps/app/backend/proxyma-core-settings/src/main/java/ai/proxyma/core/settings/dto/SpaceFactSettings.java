package ai.proxyma.core.settings.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

// The astronomy fact shown under the chat hero, with the source it was attributed to. Written by
// SpaceFactService (proxyma-core-chat) on its 30-minute refresh, read back on boot.
//
// DEPLOYMENT-WIDE, unlike the per-user chat personalization it replaced: the fact says nothing
// about the reader, so one generation serves every user of the deployment. That is the whole cost
// argument for it being here at all - a per-user version would multiply one cheap background call
// by the user count for no gain.
//
// Persisted rather than kept in memory only so a restart does not spend another LLM call to
// redisplay something the deployment already had. Nothing here is user input and nothing is
// secret; the settings file is simply the storage mechanism this codebase already has for a small
// piece of global state.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SpaceFactSettings {

    public static final String PREFIX = "space-fact";

    // One or two sentences. Blank until the first successful refresh, which is what makes the hero
    // render no subtitle at all - there is no built-in seed list to fall back to any more.
    @JsonProperty("fact")
    private String fact;

    // The same fact in Vietnamese, written by the same call since 2026-10-01 so the hero follows the
    // app's language. Absent when the model gave none; the hero then shows the English one.
    @JsonProperty("fact-vi")
    private String factVi;

    // Link text, e.g. "NASA - Venus". Never a bare URL: it renders as a short inline hyperlink
    // inside the hero subtitle, where a full URL would not fit.
    @JsonProperty("source-title")
    private String sourceTitle;

    // Checked against the host allowlist AND fetched before it is ever stored - see
    // SpaceFactService.verify. A URL in here has been reachable at least once.
    @JsonProperty("source-url")
    private String sourceUrl;

    // Epoch millis of the generation that produced the three fields above. Only used for
    // diagnostics; the refresh cadence is the scheduler's, not derived from this.
    @JsonProperty("generated-at")
    private long generatedAt;

    public String getFact() { return fact; }
    public void setFact(String fact) { this.fact = fact; }

    public String getFactVi() { return factVi; }
    public void setFactVi(String factVi) { this.factVi = factVi; }

    public String getSourceTitle() { return sourceTitle; }
    public void setSourceTitle(String sourceTitle) { this.sourceTitle = sourceTitle; }

    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }

    public long getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(long generatedAt) { this.generatedAt = generatedAt; }
}
