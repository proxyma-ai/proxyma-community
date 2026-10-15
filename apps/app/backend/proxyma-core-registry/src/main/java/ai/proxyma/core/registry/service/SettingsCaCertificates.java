package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.CustomCaCertificates;
import ai.proxyma.core.settings.dto.ProxySettings;
import ai.proxyma.core.settings.service.SettingsService;
import org.springframework.stereotype.Component;

/**
 * The CA an administrator pasted into Settings > Network (proxy.ca-certificate), for the svn
 * process - the one connector client that cannot be handed the JVM's TLS context. Read on every
 * call, so a CA added or removed takes effect on the next command.
 */
@Component
class SettingsCaCertificates implements CustomCaCertificates {

    private final SettingsService settingsService;

    SettingsCaCertificates(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @Override
    public String pem() {
        try {
            return settingsService.getSettings(ProxySettings.PREFIX, ProxySettings.class).getCaCertificate();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
