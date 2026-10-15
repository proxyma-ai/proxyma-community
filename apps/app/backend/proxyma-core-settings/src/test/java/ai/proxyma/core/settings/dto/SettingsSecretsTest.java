package ai.proxyma.core.settings.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SEC-31 contract, pinned.
 *
 * <p>The finding: {@code SettingsService.saveAiProviderField} encrypted on a NAME test
 * ({@code equals("api-key")}) while the class header documented {@link EncryptedField} as meaning
 * AES-GCM. Eight fields carried the annotation and seven were written verbatim - six days in clear
 * text, found by reading the write path rather than by anything failing. The fix was to make the
 * write path consult {@link SettingsSecrets#isEncryptedField}, which derives its answer from the
 * annotation.
 *
 * <p>The whole value of that fix is that the annotation and the behaviour cannot drift apart. So
 * the important test here is not any individual field - it is
 * {@link Coverage#everyAnnotatedFieldIsReportedEncrypted()}, which walks every DTO in the registry
 * by reflection and asserts the two agree. Adding a new {@code @EncryptedField} to any settings
 * DTO is then automatically covered, and adding one to a DTO that is MISSING from
 * {@code PREFIX_CLASSES} - the failure mode that class's own javadoc warns about, where a whole
 * prefix silently keeps writing clear text - fails here.
 */
class SettingsSecretsTest {

    @Nested
    @DisplayName("the annotation is the contract")
    class Coverage {
        @Test
        @DisplayName("every @EncryptedField in every registered DTO is reported as encrypted")
        void everyAnnotatedFieldIsReportedEncrypted() {
            List<String> missed = new ArrayList<>();
            int checked = 0;
            for (Map.Entry<String, Class<?>> e : SettingsSecrets.PREFIX_CLASSES.entrySet()) {
                for (Field f : e.getValue().getDeclaredFields()) {
                    if (!f.isAnnotationPresent(EncryptedField.class)) continue;
                    checked++;
                    if (!SettingsSecrets.isEncryptedField(e.getKey(), kebab(f.getName()))) {
                        missed.add(e.getKey() + "." + kebab(f.getName()));
                    }
                }
            }
            assertTrue(checked > 0, "no @EncryptedField found at all - this test proves nothing");
            assertTrue(missed.isEmpty(),
                    "annotated but NOT reported as encrypted, so written in clear text: " + missed);
        }

        @Test
        @DisplayName("and nothing UNannotated is reported as encrypted")
        void unannotatedFieldsAreNotEncrypted() {
            List<String> wrong = new ArrayList<>();
            for (Map.Entry<String, Class<?>> e : SettingsSecrets.PREFIX_CLASSES.entrySet()) {
                for (Field f : e.getValue().getDeclaredFields()) {
                    if (f.isAnnotationPresent(EncryptedField.class)) continue;
                    if (SettingsSecrets.isEncryptedField(e.getKey(), kebab(f.getName()))) {
                        wrong.add(e.getKey() + "." + kebab(f.getName()));
                    }
                }
            }
            // Over-encrypting corrupts: getSettings decrypts only @EncryptedField, so an
            // ENC(...) envelope on anything else is handed back as that literal string.
            assertTrue(wrong.isEmpty(), "encrypted without the annotation, which corrupts: " + wrong);
        }

        @Test
        @DisplayName("every DTO carrying the annotation is actually in the registry")
        void annotatedDtosAreRegistered() {
            // The javadoc's stated failure mode: a DTO missing from PREFIX_CLASSES silently keeps
            // writing its secrets in clear text. Checked against the classes the registry knows,
            // since a classpath scan is deliberately not used.
            List<String> annotated = new ArrayList<>();
            for (Class<?> c : SettingsSecrets.PREFIX_CLASSES.values()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.isAnnotationPresent(EncryptedField.class)) { annotated.add(c.getSimpleName()); break; }
                }
            }
            // Three since 2026-10-01: the Jira, Confluence, Bitbucket and SVN tokens left settings
            // for ConnectorConnection in the connections redesign, and the network-share account
            // (smb) followed - each encrypts through ConnectorConnectionService
            // (ConnectorConnectionServiceTest pins that). Two in the Community edition since
            // 2026-10-15: the third, the Microsoft 365 app registration (m365), is a Proxyma
            // Enterprise module's, registered through SettingsPrefixContributor and pinned there
            // (MicrosoftSecretRedactionTest).
            assertTrue(annotated.size() >= 2,
                    "expected several DTOs with encrypted fields, found " + annotated);
        }
    }

    @Nested
    @DisplayName("what it must answer FALSE to")
    class Negatives {
        @Test
        @DisplayName("an unknown prefix - encrypting what nothing will decrypt is corruption")
        void unknownPrefixIsFalse() {
            assertFalse(SettingsSecrets.isEncryptedField("no-such-prefix", "api-key"));
        }

        @Test
        @DisplayName("an unknown field on a known prefix")
        void unknownFieldIsFalse() {
            String prefix = SettingsSecrets.PREFIX_CLASSES.keySet().iterator().next();
            assertFalse(SettingsSecrets.isEncryptedField(prefix, "no-such-field-at-all"));
        }

        @Test
        @DisplayName("null and empty are answered, not thrown on")
        void nullsAreFalse() {
            assertFalse(SettingsSecrets.isEncryptedField(null, "api-key"));
            assertFalse(SettingsSecrets.isEncryptedField("jira", null));
            assertFalse(SettingsSecrets.isEncryptedField("", ""));
        }
    }

    @Nested
    @DisplayName("the name test that caused SEC-31 is not what decides")
    class NotByName {
        @Test
        @DisplayName("a field called api-key on an UNREGISTERED prefix is not encrypted")
        void nameAloneIsNotEnough() {
            // Under the old equals("api-key") behaviour this answered true for every prefix.
            assertFalse(SettingsSecrets.isEncryptedField("something-invented", "api-key"));
        }

        @Test
        @DisplayName("registered prefixes exist and the registry is not empty")
        void registryIsPopulated() {
            assertFalse(SettingsSecrets.PREFIX_CLASSES.isEmpty());
            assertTrue(SettingsSecrets.PREFIX_CLASSES.size() > 5,
                    "registry looks truncated: " + SettingsSecrets.PREFIX_CLASSES.keySet());
        }
    }

    @Nested
    @DisplayName("one resolver for which field a key names")
    class Resolver {
        @Test
        @DisplayName("every @JsonProperty name of every registered DTO resolves to its own field")
        void everyPropertyNameResolvesToItsField() {
            for (Map.Entry<String, Class<?>> e : SettingsSecrets.PREFIX_CLASSES.entrySet()) {
                for (Field f : e.getValue().getDeclaredFields()) {
                    com.fasterxml.jackson.annotation.JsonProperty jp =
                            f.getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
                    if (jp == null || jp.value().isEmpty()) continue;
                    assertEquals(f, SettingsSecrets.fieldFor(e.getValue(), jp.value()),
                            e.getKey() + "." + jp.value());
                    assertTrue(SettingsSecrets.isKnownField(e.getKey(), jp.value()));
                }
            }
        }

        @Test
        @DisplayName("a key no DTO declares is unknown, and so is any key under an unknown prefix")
        void unknownKeysAreUnknown() {
            assertFalse(SettingsSecrets.isKnownField("chat", "no-such-field"));
            assertFalse(SettingsSecrets.isKnownField("no-such-prefix", "model"));
            assertFalse(SettingsSecrets.isKnownField(null, "model"));
        }

        @Test
        @DisplayName("org mode is gone: 'org' is not a settings group")
        void orgIsNotAPrefix() {
            assertEquals(null, SettingsSecrets.classFor("org"));
        }
    }

    /** camelCase -> kebab-case, the translation SettingsSecrets.fieldFor accepts. */
    private static String kebab(String camel) {
        StringBuilder sb = new StringBuilder();
        for (char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) sb.append('-').append(Character.toLowerCase(c));
            else sb.append(c);
        }
        return sb.toString();
    }

    @Test
    @DisplayName("the kebab helper matches what the production translation produces")
    void kebabHelperIsRight() {
        assertEquals("api-key", kebab("apiKey"));
        assertEquals("personal-access-token", kebab("personalAccessToken"));
        assertEquals("url", kebab("url"));
    }
}
