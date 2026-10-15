package ai.proxyma.contracts.license;

import java.time.Instant;

/**
 * The answer to a {@link LicenseTrialKeyRequest}: the signed trial license to paste into the
 * server, or why there is none - {@code TRIAL_USED} when this deployment has had its trial,
 * {@code INVALID} for a code that is not one.
 */
public class LicenseTrialKeyResponse {

    private LicenseStatus status;

    /** The signed trial license. Null unless {@code status} is VALID. */
    private String license;

    /** When the trial ends. Null unless {@code status} is VALID. */
    private Instant expiresAt;

    /** A sentence for the person at the form, in English. */
    private String message;

    public LicenseTrialKeyResponse() {}

    public LicenseTrialKeyResponse(LicenseStatus status, String license, Instant expiresAt, String message) {
        this.status = status;
        this.license = license;
        this.expiresAt = expiresAt;
        this.message = message;
    }

    public LicenseStatus getStatus() { return status; }
    public void setStatus(LicenseStatus status) { this.status = status; }
    public String getLicense() { return license; }
    public void setLicense(String license) { this.license = license; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
