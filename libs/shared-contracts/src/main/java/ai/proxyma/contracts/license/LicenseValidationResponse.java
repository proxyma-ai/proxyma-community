package ai.proxyma.contracts.license;

import ai.proxyma.contracts.license.LicenseTier;
import java.time.LocalDate;

public class LicenseValidationResponse {

    private LicenseStatus status;
    private LocalDate expiresAt;
    private LicenseTier tier;

    public LicenseValidationResponse() {}

    public LicenseValidationResponse(LicenseStatus status, LocalDate expiresAt, LicenseTier tier) {
        this.status = status;
        this.expiresAt = expiresAt;
        this.tier = tier;
    }

    public LicenseStatus getStatus() { return status; }
    public void setStatus(LicenseStatus status) { this.status = status; }

    public LocalDate getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDate expiresAt) { this.expiresAt = expiresAt; }

    public LicenseTier getTier() { return tier; }
    public void setTier(LicenseTier tier) { this.tier = tier; }
}
