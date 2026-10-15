package ai.proxyma.core.settings.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The network-share settings that remain once the account became a connection (2026-10-01).
 *
 * <p>The domain password is a Network share connection's secret, encrypted by
 * ConnectorConnectionService like every other connection's - not an {@code @EncryptedField} on a
 * settings prefix, which is SEC-31's shape and the reason these tests used to exist. What is left
 * under {@code network-share} is the admin-only list of allowed file servers and the sync interval,
 * and neither is a secret.
 */
class NetworkShareSecretsTest {

    @Test
    @DisplayName("the admin-only prefix is registered")
    void thePrefixIsKnown() {
        assertThat(SettingsSecrets.classFor(NetworkShareSettings.PREFIX)).isEqualTo(NetworkShareSettings.class);
        assertThat(SettingsSecrets.classFor("smb")).as("the per-user account prefix is gone").isNull();
    }

    @Test
    @DisplayName("nothing under the admin-only prefix is a secret")
    void networkShareRulesAreNotSecrets() {
        // The allowed-hosts list is a boundary, not a credential. Masking it would hide from an
        // administrator the one value they are there to review.
        assertThat(SettingsSecrets.isEncryptedField(NetworkShareSettings.PREFIX, "allowed-hosts")).isFalse();
        assertThat(SettingsSecrets.isEncryptedField(NetworkShareSettings.PREFIX, "sync-interval-seconds")).isFalse();
    }
}
