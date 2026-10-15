package ai.proxyma.contracts.license;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * An installation re-checking the license it activated: the signed license it stored, and its
 * device identity. The server answers from the license row - revoked, expired or in force - and
 * refuses a license whose signed {@code machineId} claim names a different deployment.
 */
public class LicenseValidationRequest {

    @NotBlank
    @Size(max = 4096)
    private String licenseKey;

    @NotBlank
    @Size(max = 128)
    private String machineId;

    public LicenseValidationRequest() {}

    public LicenseValidationRequest(String licenseKey, String machineId) {
        this.licenseKey = licenseKey;
        this.machineId = machineId;
    }

    public String getLicenseKey() { return licenseKey; }
    public void setLicenseKey(String licenseKey) { this.licenseKey = licenseKey; }

    public String getMachineId() { return machineId; }
    public void setMachineId(String machineId) { this.machineId = machineId; }
}
