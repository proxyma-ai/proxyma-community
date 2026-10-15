package ai.proxyma.core.settings.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Deployment-wide rules for the network-share connector. ADMINISTRATOR ONLY.
 *
 * <p>NEVER USER-SCOPED, PRECISELY SO IT CANNOT BE OVERRIDDEN PER USER. The settings store lets a
 * user override any field under a user-scoped prefix, and the allowed-hosts list is the one value
 * where that would undo the control: it decides which servers this deployment's
 * users can be made to send an NTLM exchange for their domain account to. A user who could widen
 * it could point a source at a host they control and collect their own - or, through a
 * prompt-injected document that talks the agent into registering a source, somebody else's.
 *
 * <p>So the credential lives on each user's Network share CONNECTION, and the boundary lives here.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NetworkShareSettings {

    public static final String PREFIX = "network-share";

    /**
     * The file servers this deployment may connect to. Commas, semicolons, whitespace or newlines
     * separate entries; {@code *.domain.example} matches hosts under that domain but not the
     * domain itself.
     *
     * <p>BLANK MEANS NO CEILING since 2026-10-01: each connection is its owner's own account sent to
     * a server its owner typed, and this list is how an organization says which servers may receive
     * it at all (ConnectorConnectionService.fileServerAllowedByPolicy). It meant "network shares are
     * off" while it was the only boundary.
     */
    @JsonProperty("allowed-hosts")
    private String allowedHosts;

    /**
     * Six hours. A full walk of a share over the network is far heavier than an API delta query -
     * there is no incremental listing here, every sync re-walks the tree - so it runs on the
     * slowest cadence of any connector.
     */
    @JsonProperty("sync-interval-seconds")
    private int syncIntervalSeconds = 21600;

    public String getAllowedHosts() { return allowedHosts; }
    public void setAllowedHosts(String allowedHosts) { this.allowedHosts = allowedHosts; }

    public int getSyncIntervalSeconds() { return syncIntervalSeconds; }
    public void setSyncIntervalSeconds(int syncIntervalSeconds) { this.syncIntervalSeconds = syncIntervalSeconds; }
}
