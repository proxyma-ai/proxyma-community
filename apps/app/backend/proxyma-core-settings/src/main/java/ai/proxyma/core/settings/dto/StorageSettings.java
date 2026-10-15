package ai.proxyma.core.settings.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class StorageSettings {

    public static final String PREFIX = "storage";

    @JsonProperty("default-user-quota-bytes")
    private long defaultUserQuotaBytes = 2L * 1024 * 1024 * 1024;

    // Conservative multiplier applied to TrackedDocument.contentSize (chars) to approximate the
    // vector + BM25 index footprint of embedded content when computing a user's used storage.
    @JsonProperty("overhead-multiplier")
    private double overheadMultiplier = 1.4;

    public long getDefaultUserQuotaBytes() { return defaultUserQuotaBytes; }
    public void setDefaultUserQuotaBytes(long v) { this.defaultUserQuotaBytes = v; }
    public double getOverheadMultiplier() { return overheadMultiplier; }
    public void setOverheadMultiplier(double v) { this.overheadMultiplier = v; }
}
