package ai.proxyma.core.telemetryclient;

import ai.proxyma.contracts.license.LicenseTier;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.license.Features;
import ai.proxyma.core.settings.service.SettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A thumbs-up/down carries the rated reply and its question, so it leaves the deployment only on
 * the same terms as usage counts - a PRIVACY test, since 0.10.0.
 *
 * <p>Before that it shipped whatever the telemetry setting said and on every tier, while the
 * Privacy Policy and the DPA said no conversation reached us unless someone ticked a box.
 */
class RatingConsentTest {

    private static FeedbackShipper shipper(LicenseTier tier, boolean dataCollection, String url) {
        FeatureGateService gate = mock(FeatureGateService.class);
        when(gate.getFeatures()).thenReturn(Features.forTier(tier));
        SettingsService settings = mock(SettingsService.class);
        when(settings.isDataCollectionAllowed()).thenReturn(dataCollection);
        FeedbackShipper shipper = new FeedbackShipper(RestClient.builder(), gate, settings, null, null);
        ReflectionTestUtils.setField(shipper, "telemetryServerUrl", url);
        return shipper;
    }

    @Test
    @DisplayName("a rating stays in the deployment while usage counts are not shared")
    void offByDefaultSetting() {
        assertFalse(shipper(LicenseTier.COMMUNITY, false, "https://telemetry.example").ratingsMayLeave());
    }

    @Test
    @DisplayName("a rating may leave once usage counts are shared on a tier that allows it")
    void sharedOnCommunity() {
        assertTrue(shipper(LicenseTier.COMMUNITY, true, "https://telemetry.example").ratingsMayLeave());
    }

    @Test
    @DisplayName("never under an Enterprise license, whatever the setting says")
    void neverOnEnterprise() {
        assertFalse(shipper(LicenseTier.ENTERPRISE, true, "https://telemetry.example").ratingsMayLeave());
    }

    @Test
    @DisplayName("never from Enterprise Server, even with no license in force - before a trial or after it")
    void neverFromEnterpriseServer() {
        FeedbackShipper unlicensedServer = shipper(LicenseTier.COMMUNITY, true, "https://telemetry.example");
        ReflectionTestUtils.setField(unlicensedServer, "multiUser", true);
        assertFalse(unlicensedServer.ratingsMayLeave());
    }

    @Test
    @DisplayName("never when no telemetry endpoint is configured")
    void noEndpoint() {
        assertFalse(shipper(LicenseTier.COMMUNITY, true, "").ratingsMayLeave());
    }
}
