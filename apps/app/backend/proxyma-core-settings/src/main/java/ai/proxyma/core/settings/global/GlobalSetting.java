package ai.proxyma.core.settings.global;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// One row per global (deployment-wide) setting, in flat "prefix.key" = value form. This is the
// authoritative global settings store in BOTH desktop (H2, in the chat database) and server
// (PostgreSQL). Per-user overrides live separately in UserSettings; cost/pricing in ModelPricing.
//
// Values are stored verbatim, exactly as SettingsService produces them: @EncryptedField values are
// AES-GCM ciphertext (ENC(...)), everything else is plain text.
@Entity
@Table(name = "GLOBAL_SETTINGS")
public class GlobalSetting {

    @Id
    @Column(name = "SETTING_KEY", length = 256, nullable = false)
    private String settingKey;

    // TEXT, NEVER @Lob (settings-core-10). The comment here said @Lob mapped to TEXT on both
    // databases; on PostgreSQL Hibernate maps a @Lob String to an `oid` large object, written anew for
    // every changed value and never unlinked, and readable only inside the transaction that fetched
    // it - the class of bug SubAgentRunColumnsTest pins. GlobalSettingColumnsTest pins this one.
    @Column(name = "SETTING_VALUE", columnDefinition = "TEXT")
    private String settingValue;

    public GlobalSetting() {
    }

    public GlobalSetting(String settingKey, String settingValue) {
        this.settingKey = settingKey;
        this.settingValue = settingValue;
    }

    public String getSettingKey() { return settingKey; }
    public void setSettingKey(String settingKey) { this.settingKey = settingKey; }

    public String getSettingValue() { return settingValue; }
    public void setSettingValue(String settingValue) { this.settingValue = settingValue; }
}
