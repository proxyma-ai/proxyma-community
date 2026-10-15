package ai.proxyma.core.telemetryclient;

import ai.proxyma.contracts.license.LicenseTier;
import ai.proxyma.contracts.telemetry.TelemetryEventType;
import ai.proxyma.contracts.telemetry.diagnostics.DiagnosticsCounter;
import ai.proxyma.contracts.telemetry.diagnostics.DiagnosticsDay;
import ai.proxyma.core.common.diagnostics.Diagnostics;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.license.Features;
import ai.proxyma.core.settings.service.SettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The gate every stored and sent diagnostic passes, and the per-event path that no longer sends.
 */
class DiagnosticsConsentTest {

    @BeforeEach
    @AfterEach
    void clean() {
        Diagnostics.aggregator().clear();
    }

    private static TelemetryConsent consent(LicenseTier tier, boolean switchedOn, String url) {
        FeatureGateService gate = mock(FeatureGateService.class);
        when(gate.getFeatures()).thenReturn(Features.forTier(tier));
        SettingsService settings = mock(SettingsService.class);
        when(settings.isDataCollectionAllowed()).thenReturn(switchedOn);
        TelemetryConsent c = new TelemetryConsent(gate, settings);
        ReflectionTestUtils.setField(c, "telemetryServerUrl", url);
        return c;
    }

    @Test
    void offByDefaultMeansNothingIsKept() {
        assertThat(consent(LicenseTier.COMMUNITY, false, "https://t.example").mayCollect()).isFalse();
    }

    @Test
    void onWithAnEndpointOnATierThatAllowsIt() {
        assertThat(consent(LicenseTier.COMMUNITY, true, "https://t.example").mayCollect()).isTrue();
        assertThat(consent(LicenseTier.COMMUNITY, true, "https://t.example").mayCollect()).isTrue();
    }

    @Test
    void neverUnderEnterpriseWhateverTheSwitchSays() {
        TelemetryConsent c = consent(LicenseTier.ENTERPRISE, true, "https://t.example");
        assertThat(c.mayCollect()).isFalse();
        assertThat(c.blockedByTier()).isTrue();
    }

    @Test
    void neverFromEnterpriseServerEvenUnlicensed() {
        // Community is what an unlicensed Enterprise Server runs on - several users, one switch.
        TelemetryConsent c = consent(LicenseTier.COMMUNITY, true, "https://t.example");
        ReflectionTestUtils.setField(c, "multiUser", true);
        assertThat(c.mayCollect()).isFalse();
        assertThat(c.blockedByEdition()).isTrue();
    }

    @Test
    void neverWithoutAnEndpoint() {
        assertThat(consent(LicenseTier.COMMUNITY, true, "").mayCollect()).isFalse();
        assertThat(consent(LicenseTier.COMMUNITY, true, null).mayCollect()).isFalse();
    }

    @Test
    void theEventPathOnlyCountsAndNeverForwardsPayloadFields() {
        TelemetryShipper shipper = new TelemetryShipper();
        shipper.record(TelemetryEventType.SEARCH, Map.of(
                "queryLength", 41, "queryExpansion", true, "reranking", false,
                "query", "what is the acquisition price"));
        shipper.record(TelemetryEventType.SOURCE_REGISTERED, Map.of(
                "connectorType", "LOCAL_FOLDER", "config", "C:\\Users\\alice\\Contracts"));
        shipper.record(TelemetryEventType.APP_STOP, Map.of("uptimeSeconds", 7200L));

        List<DiagnosticsDay> days = Diagnostics.aggregator().drain();
        assertThat(days).hasSize(1);
        String all = days.get(0).getCounters().stream()
                .map(c -> c.getName() + c.getLabels() + c.getCount()).toList().toString();
        assertThat(all).doesNotContain("acquisition", "alice", "Contracts", "41");
        assertThat(days.get(0).getCounters()).extracting(DiagnosticsCounter::getName)
                .contains("search.query", "source.registered", "app.stop", "app.uptime_minutes");
        assertThat(days.get(0).getCounters().stream()
                .filter(c -> c.getName().equals("app.uptime_minutes")).findFirst().orElseThrow().getCount())
                .isEqualTo(120);
    }
}
