package ai.proxyma.core.license;

import ai.proxyma.contracts.license.LicenseTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Constellations (agent teams) is an ADD-ON: every Enterprise license carries it by default, and on
 * Desktop and Home Server its own key grants it and nothing else. The page's menu reads the same flag
 * the server enforces - LicenseStatusResponse carries it, as it carries keyAccounts.
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
    @DisplayName("an add-on key grants Constellations on Community and nothing Enterprise sells")
    void addonOnCommunity() {
        Features f = Features.of(LicenseTier.COMMUNITY, java.util.List.of("constellations", "not-an-addon"));
        assertEquals(true, f.isConstellationsAllowed());
        assertEquals(false, f.isKeyAccountsAllowed());
        assertEquals(false, f.isOrgModeAllowed());
        assertEquals(java.util.Set.of("constellations"), f.addons());

        FeatureGateService gate = new FeatureGateService();
        gate.grantAddons(java.util.List.of("constellations"));
        assertEquals(true, gate.getFeatures().isConstellationsAllowed());
        assertEquals(LicenseTier.COMMUNITY, gate.getCurrentTier());
        gate.grantAddons(java.util.List.of());
        assertEquals(false, gate.getFeatures().isConstellationsAllowed());
    }

    @Test
    @DisplayName("an Enterprise license whose addons claim leaves Constellations out does not grant it")
    void enterpriseClaimDecides() {
        assertEquals(false, Features.of(LicenseTier.ENTERPRISE, java.util.List.of()).isConstellationsAllowed());
        assertEquals(true, Features.of(LicenseTier.ENTERPRISE, java.util.List.of()).isKeyAccountsAllowed());
        LicenseStatusResponse s = LicenseStatusResponse.of(LicenseTier.ENTERPRISE, null, true).withAddons(java.util.List.of());
        assertEquals(false, s.constellationsAllowed());
        assertEquals(java.util.List.of(), s.addons());
    }

    @Test
    @DisplayName("every copy of a status keeps the flag")
    void copiesKeepIt() {
        LicenseStatusResponse s = LicenseStatusResponse.of(LicenseTier.ENTERPRISE, null, true);
        assertEquals(true, s.withTrial(true).withTrialEndedAt(null).withTrialState("GRANTED", false)
                .withRecheck(null).constellationsAllowed());
    }
}
