package ai.proxyma.core.settings.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// Per-user settings overrides (server mode only).
// The userId maps to the authenticated User's subject claim from the JWT/OAuth2 token.
// Desktop mode does not use this entity - it relies on the global settings store directly.
@Entity
@Table(name = "USER_SETTINGS")
public class UserSettings {

    @Id
    @Column(name = "USER_ID", nullable = false, length = 256)
    private String userId;

    // JSON-encoded settings blob; keys match the same prefix.key format as the global settings store.
    @Column(name = "SETTINGS_JSON", columnDefinition = "TEXT")
    private String settingsJson;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getSettingsJson() { return settingsJson; }
    public void setSettingsJson(String settingsJson) { this.settingsJson = settingsJson; }
}
