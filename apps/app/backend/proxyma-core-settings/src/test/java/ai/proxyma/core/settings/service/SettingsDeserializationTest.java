package ai.proxyma.core.settings.service;

import ai.proxyma.core.settings.dto.GuardrailSettings;
import ai.proxyma.core.settings.global.GlobalSetting;
import ai.proxyma.core.settings.global.GlobalSettingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ONE UNREADABLE SETTING MUST NOT TAKE A WHOLE PREFIX WITH IT.
 *
 * <p>{@code getSettings} deserialized the entire prefix in one call and, on any failure, returned
 * {@code defaultInstance(targetClass)}. For {@code guardrail} that means the mode, both violation
 * actions and every detector reverting at once - so an operator sees a screen of defaults while
 * the stored configuration says otherwise, and the only trace is a WARN naming neither the key nor
 * the value. The hand-off has carried it as an open item for exactly that reason: it is silent,
 * and what it silently does is turn enforcement off.
 *
 * <p>These pin the recovery, not the logging. What matters is that a good value survives a bad
 * neighbour.
 */
class SettingsDeserializationTest {

    private GlobalSettingRepository repository;
    private SettingsService settings;

    @BeforeEach
    void setUp() {
        repository = mock(GlobalSettingRepository.class);
        settings = new SettingsService(
                new ObjectMapper(),
                mock(CredentialEncryptionService.class),
                mock(ApplicationEventPublisher.class),
                repository,
                null,
                null);
    }

    /** Feeds the service a stored settings table. */
    private void stored(String... keyThenValue) {
        List<GlobalSetting> rows = new ArrayList<>();
        for (int i = 0; i < keyThenValue.length; i += 2) {
            GlobalSetting row = new GlobalSetting();
            row.setSettingKey(keyThenValue[i]);
            row.setSettingValue(keyThenValue[i + 1]);
            rows.add(row);
        }
        when(repository.findAll()).thenReturn(rows);
    }

    @Test
    @DisplayName("a good prefix reads back as stored")
    void readsStoredValues() {
        stored("guardrail.mode", "MONITOR",
               "guardrail.detect-secrets", "false");

        GuardrailSettings s = settings.getSettings(GuardrailSettings.PREFIX, GuardrailSettings.class);
        assertEquals(GuardrailSettings.Mode.MONITOR, s.getMode());
        assertFalse(s.isDetectSecrets());
    }

    @Test
    @DisplayName("one unreadable value does not revert the settings beside it")
    void keepsGoodValuesBesideABadOne() {
        // semantic-top-k is an Integer. "not-a-number" is what a hand-edited file, or a field
        // whose type changed between versions, looks like from here.
        stored("guardrail.mode", "MONITOR",
               "guardrail.semantic-top-k", "not-a-number",
               "guardrail.detect-secrets", "false");

        GuardrailSettings s = settings.getSettings(GuardrailSettings.PREFIX, GuardrailSettings.class);

        // The whole point: MONITOR survives. Before this, the bad key reverted the prefix and this
        // came back ENFORCE - a deployment quietly enforcing when its configuration says watch.
        assertEquals(GuardrailSettings.Mode.MONITOR, s.getMode());
        assertFalse(s.isDetectSecrets());
        // And the unreadable one falls back to its own default rather than to nothing.
        assertEquals(4, s.getSemanticTopK());
    }

    @Test
    @DisplayName("an enum constant that no longer exists is dropped, not fatal")
    void unknownEnumConstant() {
        // The shape a renamed enum takes on an install that stored the old name.
        stored("guardrail.mode", "SOME_MODE_THAT_WAS_RENAMED",
               "guardrail.detect-pii", "false");

        GuardrailSettings s = settings.getSettings(GuardrailSettings.PREFIX, GuardrailSettings.class);
        assertFalse(s.isDetectPii(), "the readable key must survive the unreadable one");
        assertEquals(GuardrailSettings.Mode.ENFORCE, s.getMode(), "the bad key falls back to its default");
    }

    @Test
    @DisplayName("nothing stored still yields defaults")
    void emptyStore() {
        stored();
        GuardrailSettings s = settings.getSettings(GuardrailSettings.PREFIX, GuardrailSettings.class);
        assertEquals(GuardrailSettings.Mode.ENFORCE, s.getMode());
        assertTrue(s.isDetectSecrets());
        assertFalse(s.isCaptureMatchedText(), "capture stays off when nothing is stored");
    }

    @Test
    @DisplayName("keys under another prefix are not read into this one")
    void otherPrefixesIgnored() {
        stored("chat.provider", "openai",
               "guardrail.mode", "MONITOR");

        GuardrailSettings s = settings.getSettings(GuardrailSettings.PREFIX, GuardrailSettings.class);
        assertEquals(GuardrailSettings.Mode.MONITOR, s.getMode());
    }
}
