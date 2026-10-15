package ai.proxyma.contracts.license;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Releases a machine's registration so the seat can be used elsewhere.
 *
 * <p>This is the half that makes a device limit tolerable. Without it, replacing a laptop means
 * a support request, and a customer who reinstalls twice is locked out of software they paid
 * for. The installation itself can give the seat back.
 *
 * <p>Deliberately requires only the license key and the machine ID, both of which the
 * installation already holds - asking for an admin credential here would defeat the purpose.
 * The machine can only release ITSELF, which is why that is safe: the pair is not a secret worth
 * stealing, since the worst it does is free a seat its holder already occupies.
 */
public class LicenseDeactivationRequest {

    @NotBlank
    @Size(max = 4096)
    private String licenseKey;

    @NotBlank
    @Size(max = 128)
    private String machineId;

    public LicenseDeactivationRequest() {}

    public LicenseDeactivationRequest(String licenseKey, String machineId) {
        this.licenseKey = licenseKey;
        this.machineId = machineId;
    }

    public String getLicenseKey() { return licenseKey; }
    public void setLicenseKey(String licenseKey) { this.licenseKey = licenseKey; }

    public String getMachineId() { return machineId; }
    public void setMachineId(String machineId) { this.machineId = machineId; }
}
