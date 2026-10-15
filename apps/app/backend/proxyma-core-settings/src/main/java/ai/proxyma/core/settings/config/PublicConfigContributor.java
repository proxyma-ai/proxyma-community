package ai.proxyma.core.settings.config;

import java.util.Map;

/**
 * Adds to what {@code GET /api/v1/config} tells the page about this deployment - for a module the
 * Community edition does not have. Proxyma Enterprise's Microsoft 365 module reports which of its
 * source types the deployment has switched on (teamsEnabled, chatsEnabled, outlookEnabled); without
 * it those keys are simply absent, which the page reads as off.
 *
 * <p>A PORT with no Community implementation: every bean of this type is asked, in no particular
 * order, on every read of the endpoint. What one adds is readable by anyone who can open the page -
 * a visitor to the sign-in page included - so it is switches, never configuration.
 */
public interface PublicConfigContributor {

    /** Puts this module's entries into {@code config}. Never throws; never removes an entry. */
    void contribute(Map<String, Object> config);
}
