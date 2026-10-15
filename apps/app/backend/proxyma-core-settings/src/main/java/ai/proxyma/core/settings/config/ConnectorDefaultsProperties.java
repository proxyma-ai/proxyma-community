package ai.proxyma.core.settings.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

// Deploy-time-only, per-connector facts a deployment knows about its own servers, bound from
// proxyma.connector-defaults.* in application.properties/env vars. Empty for Proxyma itself.
//
// Proxyma never encodes any tenant's servers (the rule that removed the tenant deployment):
// this is an empty map here, and the consuming deployment supplies its own values in
// its own configuration. Keys are the connector's SETTINGS PREFIX - the same string used for its
// both the connector and the token field a user must fill in for it.
@Component
@ConfigurationProperties(prefix = "proxyma.connector-defaults")
public class ConnectorDefaultsProperties {

    private Map<String, String> tokenPageUrl = new LinkedHashMap<>();

    public Map<String, String> getTokenPageUrl() { return tokenPageUrl; }
    public void setTokenPageUrl(Map<String, String> tokenPageUrl) { this.tokenPageUrl = tokenPageUrl; }

    // The exact page where THIS deployment's users mint a personal access token for a connector,
    // when it is not the vendor's standard one.
    //
    // ToolConnectorResolver otherwise derives the token page from the server's own base URL plus a
    // well-known Atlassian path, which is right for a stock Server/Data Center install. But an
    // organization can front its Jira with a third-party token plugin serving that page from an
    // entirely different path, and the derived link then 404s the user at exactly the moment the
    // tool told them "go here to fix it". Configure the real URL here to override the derived one;
    // leave it unset for any connector whose standard path is correct.
    //
    //   proxyma.connector-defaults.token-page-url.jira=https://jira.example.com/plugins/servlet/...
    //
    // Only session-based URLs belong here - a page that shows whoever is logged in their own
    // tokens. A per-user URL (one with a username baked into the path) would hand every user the
    // same wrong person's page.
    public String tokenPageUrlFor(String settingsPrefix) {
        if (settingsPrefix == null) {
            return null;
        }
        String url = tokenPageUrl.get(settingsPrefix);
        return url != null && !url.isBlank() ? url.trim() : null;
    }
}
