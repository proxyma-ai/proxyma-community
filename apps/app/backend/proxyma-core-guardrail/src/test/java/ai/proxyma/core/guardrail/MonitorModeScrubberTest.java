package ai.proxyma.core.guardrail;

import ai.proxyma.core.settings.dto.GuardrailSettings;
import ai.proxyma.core.settings.service.SettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Monitor mode applies nothing - including to an answer while it streams.
 *
 * <p>Until 0.10.0 the streaming scrubber ignored the mode: a monitored deployment still cut
 * credentials out of the text as it appeared, while the end-of-turn check allowed the answer and the
 * saved copy kept them. The site sells monitor mode as running every check and applying none.
 */
class MonitorModeScrubberTest {

    /** Not a real key. The prefix and shape are what the rule matches on. */
    private static final String AWS_KEY = "AKIAIOSFODNN7EXAMPLE";

    private static String streamed(GuardrailSettings.Mode mode) {
        GuardrailSettings s = new GuardrailSettings();
        s.setOutputEnabled(true);
        s.setDetectSecrets(true);
        s.setMode(mode);
        SettingsService settings = mock(SettingsService.class);
        when(settings.getSettings(eq(GuardrailSettings.PREFIX), eq(GuardrailSettings.class))).thenReturn(s);
        GuardrailService service = new GuardrailService(settings, null, null, null, null, null);

        StreamingScrubber scrubber = service.outputScrubber();
        return scrubber.accept("Here is the key: " + AWS_KEY + " - keep it safe.") + scrubber.drain();
    }

    @Test
    @DisplayName("enforce mode redacts a credential as the answer streams")
    void enforceRedacts() {
        assertFalse(streamed(GuardrailSettings.Mode.ENFORCE).contains(AWS_KEY));
    }

    @Test
    @DisplayName("monitor mode streams the answer untouched")
    void monitorLeavesTextAlone() {
        assertEquals("Here is the key: " + AWS_KEY + " - keep it safe.", streamed(GuardrailSettings.Mode.MONITOR));
    }
}
