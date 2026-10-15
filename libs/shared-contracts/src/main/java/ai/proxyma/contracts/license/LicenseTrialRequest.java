package ai.proxyma.contracts.license;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * An installation asking for its trial, with no license key.
 *
 * <p>{@code edition = "server"}: Proxyma Enterprise Server asks on its first start for a 30-day
 * ENTERPRISE trial, ONCE PER DEPLOYMENT. A deployment is its database, so {@code deploymentId} - a
 * random UUID kept there - is what the server keys the trial on; {@code hostIdHash} and
 * {@code clusterIdHash} come along when they can be read, so dropping the database and starting
 * again on the same host or cluster is not a new trial either. Each is SHA-256 hex of
 * {@code "proxyma-trial-v1:"} and the identifier - never the identifier itself.
 *
 * <p>{@code edition = "desktop"}: the retired desktop trial, still asked for by builds 0.33-0.35 of
 * Proxyma Desktop and Home Server, keyed on {@code osIdHash} (the operating system's own
 * identifier, hashed the same way). Current builds of those editions need no license and never ask.
 *
 * <p>Which fields an edition needs is the server's rule; the fields themselves are all optional so
 * that either side may deploy first. The answer is a {@link LicenseActivationResponse}: a granted
 * trial is a time-limited license, activated for {@code machineId} exactly as a bought one is.
 */
public class LicenseTrialRequest {

    /**
     * The installation's device identity - see LicenseActivationRequest. On Enterprise Server it is
     * the deployment id.
     */
    @NotBlank
    @Size(max = 128)
    private String machineId;

    /** SHA-256 hex of "proxyma-trial-v1:" + the operating system's installation identifier. Desktop only. */
    @Pattern(regexp = "[0-9a-f]{64}")
    private String osIdHash;

    /** "server" (Enterprise Server) or "desktop" (the retired desktop trial). */
    @Size(max = 16)
    private String edition;

    /** The deployment's own random identifier, a UUID kept in its database. Required for "server". */
    @Pattern(regexp = "[0-9a-fA-F-]{36}")
    private String deploymentId;

    /** SHA-256 hex of "proxyma-trial-v1:" + the host's /etc/machine-id, when the deployment can read it. */
    @Pattern(regexp = "[0-9a-f]{64}")
    private String hostIdHash;

    /** SHA-256 hex of "proxyma-trial-v1:" + the operator's cluster id (PROXYMA_CLUSTER_ID), when set. */
    @Pattern(regexp = "[0-9a-f]{64}")
    private String clusterIdHash;

    public LicenseTrialRequest() {}

    public LicenseTrialRequest(String machineId, String osIdHash, String edition) {
        this.machineId = machineId;
        this.osIdHash = osIdHash;
        this.edition = edition;
    }

    /** An Enterprise Server deployment's request. */
    public static LicenseTrialRequest forDeployment(String deploymentId, String hostIdHash, String clusterIdHash) {
        LicenseTrialRequest r = new LicenseTrialRequest(deploymentId, null, "server");
        r.setDeploymentId(deploymentId);
        r.setHostIdHash(hostIdHash);
        r.setClusterIdHash(clusterIdHash);
        return r;
    }

    public String getMachineId() { return machineId; }
    public void setMachineId(String machineId) { this.machineId = machineId; }
    public String getOsIdHash() { return osIdHash; }
    public void setOsIdHash(String osIdHash) { this.osIdHash = osIdHash; }
    public String getEdition() { return edition; }
    public void setEdition(String edition) { this.edition = edition; }
    public String getDeploymentId() { return deploymentId; }
    public void setDeploymentId(String deploymentId) { this.deploymentId = deploymentId; }
    public String getHostIdHash() { return hostIdHash; }
    public void setHostIdHash(String hostIdHash) { this.hostIdHash = hostIdHash; }
    public String getClusterIdHash() { return clusterIdHash; }
    public void setClusterIdHash(String clusterIdHash) { this.clusterIdHash = clusterIdHash; }
}
