package ai.proxyma.contracts.license;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A trial key for an AIR-GAPPED Proxyma Enterprise Server, asked for from a connected browser.
 *
 * <p>A server with no route to the license server cannot ask for its own trial. It shows its
 * deployment code instead ({@link DeploymentCode}); whoever runs it pastes the code into a form on
 * proxyma.ai, which posts this, and gets back a signed trial license bound to that deployment by its
 * {@code machineId} claim. Pasted into the server, the license verifies offline and works on that
 * deployment only.
 *
 * <p>Anonymous: nothing here names a person. The answer's sentence is English; a page in another
 * language words it from the answer's status.
 */
public class LicenseTrialKeyRequest {

    @NotBlank
    @Size(max = 64)
    private String deploymentCode;

    public LicenseTrialKeyRequest() {}

    public LicenseTrialKeyRequest(String deploymentCode) {
        this.deploymentCode = deploymentCode;
    }

    public String getDeploymentCode() { return deploymentCode; }
    public void setDeploymentCode(String deploymentCode) { this.deploymentCode = deploymentCode; }
}
