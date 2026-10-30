package ai.proxyma.contracts.license;

import java.time.Instant;
import java.util.List;

/**
 * The license server's answer to an activation attempt.
 *
 * <p>On success this carries the signed license JWT the installation persists and verifies
 * offline from then on. The installation does NOT trust this response on its own - it verifies
 * the {@code activationKey} signature against the public keys embedded in its own build before
 * storing it, so a forged response buys nothing.
 *
 * <p>Two kinds are issued: ENTERPRISE, since 2026-10-14 (a trial is a 30-day Enterprise license
 * whose signed claims say {@code trial: true}), and since 2026-10-30 ADD-ON licenses for Desktop and
 * Home Server - tier COMMUNITY with an {@code addons} claim ({@link Addons}). A license carries its own expiry in its signature,
 * and the installation acts on that expiry itself, online or not.
 */
public class LicenseActivationResponse {

    private boolean activated;

    /** Machine-readable outcome. Present on failure too, where {@code activated} is false. */
    private LicenseStatus status;

    private LicenseTier tier;

    /** The signed license JWT. Null unless {@code activated} is true. */
    private String activationKey;

    /** When the license itself stops being valid. Null means perpetual - the retired desktop tiers. */
    private Instant expiresAt;

    /**
     * Legacy, and equal to {@code expiresAt}. It promised a server-decided ceiling on how long an
     * installation could run offline, which no client ever read (security finding swx-08); the
     * ceiling is the license's own signed expiry, which every Enterprise license now carries. Kept
     * only so builds that deserialize it keep doing so.
     */
    private Instant cacheUntil;

    /** Devices currently registered against this license, after this activation. */
    private int devicesUsed;

    /** The tier's device limit. -1 means unlimited. */
    private int devicesAllowed;

    /** Human-readable reason, shown to the person doing the activation. Never a stack trace. */
    private String message;
    /** The license is a trial (from /trial or a trial key), which ends at {@code expiresAt}. */
    private boolean trial;

    /** The add-ons the license grants (Addons); empty for none. Absent from an older license server. */
    private List<String> addons;

    public LicenseActivationResponse() {}

    public boolean isActivated() { return activated; }
    public void setActivated(boolean activated) { this.activated = activated; }

    public LicenseStatus getStatus() { return status; }
    public void setStatus(LicenseStatus status) { this.status = status; }

    public LicenseTier getTier() { return tier; }
    public void setTier(LicenseTier tier) { this.tier = tier; }

    public String getActivationKey() { return activationKey; }
    public void setActivationKey(String activationKey) { this.activationKey = activationKey; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getCacheUntil() { return cacheUntil; }
    public void setCacheUntil(Instant cacheUntil) { this.cacheUntil = cacheUntil; }

    public int getDevicesUsed() { return devicesUsed; }
    public void setDevicesUsed(int devicesUsed) { this.devicesUsed = devicesUsed; }

    public int getDevicesAllowed() { return devicesAllowed; }
    public void setDevicesAllowed(int devicesAllowed) { this.devicesAllowed = devicesAllowed; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public boolean isTrial() { return trial; }
    public void setTrial(boolean trial) { this.trial = trial; }
    public List<String> getAddons() { return addons; }
    public void setAddons(List<String> addons) { this.addons = addons; }
}
