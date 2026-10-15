package ai.proxyma.core.settings.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * settings-core-08: a user-scoped write lands in that user's own blob, which every later read of
 * their settings parses whole, and any signed-in user could store keys of any name and any size
 * there. A field must be one the settings class declares, and a value is bounded on either path.
 */
class SettingsWriteValidationTest {

    @Test
    @DisplayName("a user-scoped field the settings class does not declare is refused")
    void unknownUserFieldIsRefused() {
        assertThat(SettingsController.invalidWrite("jira", "anything-at-all", "x")).isNotNull();
        assertThat(SettingsController.invalidWrite("onboarding", "tour-completed", "true")).isNull();
        assertThat(SettingsController.invalidWrite("connector-tools", "connector-management", "true")).isNull();
    }

    @Test
    @DisplayName("a value is bounded - small for a user's own blob, larger for system configuration")
    void valuesAreBounded() {
        String big = "x".repeat(SettingsController.MAX_USER_VALUE_CHARS + 1);
        assertThat(SettingsController.invalidWrite("onboarding", "tour-completed", big)).isNotNull();
        assertThat(SettingsController.invalidWrite("proxy", "ca-certificate", big)).isNull();
        assertThat(SettingsController.invalidWrite("proxy", "ca-certificate",
                "x".repeat(SettingsController.MAX_SYSTEM_VALUE_CHARS + 1))).isNotNull();
    }
}
