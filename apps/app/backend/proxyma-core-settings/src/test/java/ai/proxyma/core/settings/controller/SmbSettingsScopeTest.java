package ai.proxyma.core.settings.controller;

import ai.proxyma.core.settings.dto.NetworkShareSettings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may write the network-share settings, and what of them travels back to a browser.
 *
 * <p>Since 2026-10-01 a user's domain account is a Network share CONNECTION, encrypted and owned
 * like every other connection (ConnectorConnectionServiceTest), and the per-user {@code smb} prefix
 * that held it is gone. What is left here is the admin-only list of allowed file servers - which
 * must stay off the user-scoped path, or each user could widen the ceiling over their own account.
 */
class SmbSettingsScopeTest {

    @Test
    @DisplayName("the per-user smb prefix is gone - the account is a connection now")
    void smbIsNoLongerASettingsPrefix() {
        assertThat(SettingsController.VIEWER_PREFIXES).doesNotContain("smb");
        assertThat(SettingsController.CREDENTIAL_PREFIXES).doesNotContainKey("smb");
    }

    @Test
    @DisplayName("the allowed file servers are NOT per user - a user must not be able to widen them")
    void networkShareIsNotUserScoped() {
        // Being absent is what sends it down the system-config path: ADMIN reads, SUPER_ADMIN
        // writes, and no user override is ever layered on top.
        assertThat(SettingsController.VIEWER_PREFIXES).doesNotContain(NetworkShareSettings.PREFIX);
    }

    @Test
    @DisplayName("the Microsoft 365 client secret is the one connector credential the license gates")
    void microsoftSecretIsTierGated() {
        // The save is refused only for an ENCRYPTED field under a listed prefix - both halves. Since
        // the two tiers every other connector is in Community, so m365 is the only prefix listed. The
        // other half - that its client secret IS encrypted - is the Microsoft 365 module's to pin
        // (MicrosoftSecretRedactionTest), since that module is what registers the prefix.
        assertThat(SettingsController.CREDENTIAL_PREFIXES)
                .containsExactly(java.util.Map.entry("m365", ai.proxyma.core.connector.spi.Edition.ENTERPRISE));
    }

    @Test
    @DisplayName("the Microsoft 365 tenant config is super-admin only, for reads as well as writes")
    void microsoftTenantConfigIsSuperAdminOnly() {
        // Membership is what routes checkAccess to isSuperAdmin() for GET, PATCH and DELETE alike.
        // Off this list, the prefix falls to system config, where an ADMIN may read it (SEC-41).
        assertThat(SettingsController.SUPER_ADMIN_PREFIXES).contains("m365");
        // And never user-scoped, where any user could write their own copy of it.
        assertThat(SettingsController.VIEWER_PREFIXES).doesNotContain("m365");
    }

    @Test
    @DisplayName("other connectors are untouched - their owners still read their own values back")
    void otherPrefixesAreNotRedacted() {
        Map<String, Object> stored = Map.of("credential", "user:pass");
        assertThat(SettingsController.redactWriteOnly("svn", stored)).isSameAs(stored);
    }
}
