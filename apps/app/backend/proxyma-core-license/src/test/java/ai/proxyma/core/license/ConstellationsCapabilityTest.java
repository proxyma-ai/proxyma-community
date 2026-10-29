package ai.proxyma.core.license;

import ai.proxyma.contracts.license.LicenseTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Constellations (agent teams) is Enterprise only, and the page's menu reads the same flag the server
 * enforces - LicenseStatusResponse carries it, as it carries keyAccounts.
 */
class ConstellationsCapabilityTest {

    @Test
    @DisplayName("constellations is true for ENTERPRISE and for no other tier")
    void enterpriseOnly() {
        for (LicenseTier tier : LicenseTier.values()) {
            assertEquals(tier == LicenseTier.ENTERPRISE, Features.forTier(tier).isConstellationsAllowed(), tier.name());
            assertEquals(tier == LicenseTier.ENTERPRISE,
                    LicenseStatusResponse.of(tier, null, true).constellationsAllowed(), tier.name());
        }
    }

    @Test
    @DisplayName("every copy of a status keeps the flag")
    void copiesKeepIt() {
        LicenseStatusResponse s = LicenseStatusResponse.of(LicenseTier.ENTERPRISE, null, true);
        assertEquals(true, s.withTrial(true).withTrialEndedAt(null).withTrialState("GRANTED", false)
                .withRecheck(null).constellationsAllowed());
    }
}
