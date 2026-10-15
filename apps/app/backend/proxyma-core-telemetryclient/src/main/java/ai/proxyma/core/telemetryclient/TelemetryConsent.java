package ai.proxyma.core.telemetryclient;

import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.settings.service.SettingsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Whether diagnostics may be KEPT and SENT - the three conditions in one place.
 *
 * <ol>
 *   <li>A telemetry endpoint is configured at all. An air-gapped deployment sets it empty, and
 *       then nothing is stored either: data that can never leave has no reason to accumulate.</li>
 *   <li>This is not Proxyma Enterprise Server, licensed or not. Diagnostics come only from the
 *       single-user editions, where the person who turns it on is the person it describes
 *       (decided 2026-09-22); answer ratings follow the same edition rule (FeedbackShipper). Since
 *       an Enterprise license exists only on that edition, the tier check below adds nothing the
 *       edition has not decided - it is kept as the statement that Enterprise never sends.</li>
 *   <li>"Send diagnostics to Proxyma" is on. Off by default.</li>
 * </ol>
 *
 * Ordered cheapest first: the setting deserializes the settings store on every call.
 */
@Component
public class TelemetryConsent {

    @Value("${proxyma.telemetry.server.url:}")
    private String telemetryServerUrl;

    // Same property as LicenseActivationService: absent on Desktop and Home Server, true in
    // proxyma-server's application-server.properties.
    @Value("${proxyma.multi-user:false}")
    private boolean multiUser;

    private final FeatureGateService featureGateService;
    private final SettingsService settingsService;

    public TelemetryConsent(FeatureGateService featureGateService, SettingsService settingsService) {
        this.featureGateService = featureGateService;
        this.settingsService = settingsService;
    }

    public boolean mayCollect() {
        return isConfigured() && !blockedByEdition() && !blockedByTier()
                && settingsService.isDataCollectionAllowed();
    }

    public boolean isConfigured() {
        return telemetryServerUrl != null && !telemetryServerUrl.isBlank();
    }

    /** Proxyma Enterprise Server never sends diagnostics, whatever its license. */
    public boolean blockedByEdition() {
        return multiUser;
    }

    public boolean blockedByTier() {
        return !featureGateService.getFeatures().isExternalTelemetryAllowed();
    }

    public boolean switchedOn() {
        return settingsService.isDataCollectionAllowed();
    }

    public String serverUrl() {
        return telemetryServerUrl;
    }
}
