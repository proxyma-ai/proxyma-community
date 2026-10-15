package ai.proxyma.core.settings.controller;

import ai.proxyma.core.settings.dto.GuardrailSettings;
import ai.proxyma.core.settings.dto.SettingsSecrets;
import ai.proxyma.core.settings.dto.WebSearchSettings;
import ai.proxyma.core.settings.service.DemoSecretMasker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * settings-core-03: write-only is decided by the annotation, not by a list of prefixes. The list
 * held m365 alone (SEC-41), and the web search key beside it - set by a Super admin only - went on
 * being returned decrypted to every Admin. The guardrail canary token was returned and exported in
 * clear the same way (settings-core-04) and is an @EncryptedField now.
 */
class WriteOnlySecretsTest {

    @Test
    @DisplayName("the web search key reads back as the placeholder; the engine id beside it as it is")
    void webSearchKeyIsWriteOnly() {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("api-key", "AIzaSyRealKey");
        stored.put("search-engine-id", "a1b2c3");

        Map<String, Object> sent = SettingsController.redactWriteOnly(WebSearchSettings.PREFIX, stored);

        assertThat(sent.get("api-key")).isEqualTo(DemoSecretMasker.MASK);
        assertThat(sent.get("search-engine-id")).isEqualTo("a1b2c3");
        assertThat(SettingsController.isPlaceholderEcho(WebSearchSettings.PREFIX, "api-key", DemoSecretMasker.MASK))
                .isTrue();
    }

    @Test
    @DisplayName("the canary token is a secret: encrypted, write-only, and sealed in an export")
    void canaryTokenIsASecret() {
        assertThat(SettingsSecrets.isEncryptedField(GuardrailSettings.PREFIX, "canary-token")).isTrue();
        Map<String, Object> sent = SettingsController.redactWriteOnly(GuardrailSettings.PREFIX,
                Map.of("canary-token", "9d0a1f2c3b4d5e6f", "canary-enabled", "true"));
        assertThat(sent.get("canary-token")).isEqualTo(DemoSecretMasker.MASK);
        assertThat(sent.get("canary-enabled")).isEqualTo("true");
    }

    @Test
    @DisplayName("an unset secret stays blank - 'not set' is a different answer from 'set, hidden'")
    void blankStaysBlank() {
        Map<String, Object> sent = SettingsController.redactWriteOnly(WebSearchSettings.PREFIX, Map.of("api-key", ""));
        assertThat(sent.get("api-key")).isEqualTo("");
    }

    @Test
    @DisplayName("every @EncryptedField of every settings group is write-only")
    void everyAnnotatedFieldIsWriteOnly() {
        int checked = 0;
        for (Map.Entry<String, Class<?>> e : SettingsSecrets.PREFIX_CLASSES.entrySet()) {
            for (Field f : e.getValue().getDeclaredFields()) {
                com.fasterxml.jackson.annotation.JsonProperty jp =
                        f.getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
                if (jp == null || !SettingsSecrets.isEncryptedField(e.getKey(), jp.value())) continue;
                Map<String, Object> sent = SettingsController.redactWriteOnly(e.getKey(), Map.of(jp.value(), "v4lue"));
                assertThat(sent.get(jp.value())).as(e.getKey() + "." + jp.value()).isEqualTo(DemoSecretMasker.MASK);
                checked++;
            }
        }
        // The web search key and the guardrail canary in the Community edition; the Microsoft 365
        // client secret is a Proxyma Enterprise module's, pinned by its MicrosoftSecretRedactionTest.
        assertThat(checked).isGreaterThanOrEqualTo(2);
    }
}
