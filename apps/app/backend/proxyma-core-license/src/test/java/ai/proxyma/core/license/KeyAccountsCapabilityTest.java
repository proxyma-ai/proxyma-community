package ai.proxyma.core.license;

import ai.proxyma.contracts.license.LicenseTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Key accounts are Enterprise only (decided 2026-10-06, docs/architecture.html#spec-11-3), and
 * the page's menu reads the same flag the server enforces - LicenseStatusResponse carries it rather
 * than the frontend deriving it from the tier name.
 */
class KeyAccountsCapabilityTest {

    @Test
    @DisplayName("keyAccounts is true for ENTERPRISE and for no other tier")
    void enterpriseOnly() {
        for (LicenseTier tier : LicenseTier.values()) {
            assertEquals(tier == LicenseTier.ENTERPRISE, Features.forTier(tier).isKeyAccountsAllowed(), tier.name());
            assertEquals(tier == LicenseTier.ENTERPRISE,
                    LicenseStatusResponse.of(tier, null, true).keyAccountsAllowed(), tier.name());
        }
    }
}
