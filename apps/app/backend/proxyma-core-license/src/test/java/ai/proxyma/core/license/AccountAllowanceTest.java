package ai.proxyma.core.license;

import ai.proxyma.contracts.license.LicenseTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how many accounts Proxyma Enterprise Server may create on each tier.
 *
 * <p>The rule lives in this module rather than in proxyma-core-auth, which enforces it, because
 * that module only builds under -Pserver and is outside the test suite the gate runs.
 */
class AccountAllowanceTest {

    @Test
    @DisplayName("unlicensed: the first account is allowed, a second is not")
    void communityGetsOneAccount() {
        FeatureGateService gate = new FeatureGateService();
        assertTrue(gate.isNewAccountAllowed(0));
        assertFalse(gate.isNewAccountAllowed(1));
        assertFalse(gate.isNewAccountAllowed(50));
    }

    @Test
    @DisplayName("Enterprise: accounts are unlimited")
    void enterpriseIsUnlimited() {
        FeatureGateService gate = new FeatureGateService();
        gate.refresh(LicenseTier.ENTERPRISE);
        assertTrue(gate.isNewAccountAllowed(0));
        assertTrue(gate.isNewAccountAllowed(1));
        assertTrue(gate.isNewAccountAllowed(10_000));
    }

    @Test
    @DisplayName("a lapsed license refuses new accounts again - existing ones are not this rule's business")
    void lapsingBackToCommunityRefusesTheNextAccount() {
        FeatureGateService gate = new FeatureGateService();
        gate.refresh(LicenseTier.ENTERPRISE);
        assertTrue(gate.isNewAccountAllowed(50));
        gate.refresh(LicenseTier.COMMUNITY);
        assertFalse(gate.isNewAccountAllowed(50));
    }

    @Test
    @DisplayName("only the tier that allows org mode creates more than one account")
    void onlyOrgModeTiersGetMoreThanOne() {
        for (LicenseTier tier : LicenseTier.values()) {
            boolean orgMode = Features.forTier(tier).isOrgModeAllowed();
            assertTrue(FeatureGateService.isNewAccountAllowed(0, orgMode), tier + " must allow the first account");
            if (orgMode) {
                assertTrue(FeatureGateService.isNewAccountAllowed(2, true), tier.name());
            } else {
                assertFalse(FeatureGateService.isNewAccountAllowed(2, false), tier.name());
            }
        }
    }
}
