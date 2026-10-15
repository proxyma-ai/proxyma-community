package ai.proxyma.contracts.license;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * First-contact activation. A Proxyma installation sends this once, with the key the customer
 * bought and an identifier for the machine it is being installed on; the license server records
 * the machine against the license, enforces the tier's device limit, and returns a signed
 * license the installation then verifies offline and caches.
 *
 * <p>Activation is deliberately ONLINE. Verifying a signature offline proves the license is
 * genuine but says nothing about how many machines are already using it, so device limits can
 * only be enforced at a point where the server is involved. That point is here, once, rather
 * than on every start - see {@link LicenseActivationResponse#getCacheUntil()}.
 *
 * <p>{@code deviceName} is optional and informational. {@code edition} is ENFORCED: the license
 * server refuses an Enterprise license on "desktop" and anything but Enterprise on "server". An
 * absent edition - a client older than the field - is let through, because it cannot be told apart.
 *
 * <p>Every string is bounded: they arrive unauthenticated, and an over-long one used to reach a
 * varchar(255) column and fail as a 500 rather than a refusal.
 */
public class LicenseActivationRequest {

    @NotBlank
    @Size(max = 4096)
    private String licenseKey;

    /**
     * Stable per-installation identifier. Derived locally and never reversible to hardware
     * details - see the client's MachineIdProvider. Two installations on the same physical
     * machine are two devices, which is the honest reading of a per-device limit.
     */
    @NotBlank
    @Size(max = 128)
    private String machineId;

    /** Optional, informational. Hostname, typically. */
    @Size(max = 255)
    private String deviceName;

    /** Optional. "desktop" or "server" - see the class note for what the server enforces on it. */
    @Size(max = 16)
    private String edition;

    public LicenseActivationRequest() {}

    public LicenseActivationRequest(String licenseKey, String machineId, String deviceName, String edition) {
        this.licenseKey = licenseKey;
        this.machineId = machineId;
        this.deviceName = deviceName;
        this.edition = edition;
    }

    public String getLicenseKey() { return licenseKey; }
    public void setLicenseKey(String licenseKey) { this.licenseKey = licenseKey; }

    public String getMachineId() { return machineId; }
    public void setMachineId(String machineId) { this.machineId = machineId; }

    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }

    public String getEdition() { return edition; }
    public void setEdition(String edition) { this.edition = edition; }
}
