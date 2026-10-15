package ai.proxyma.core.guardrail;

import ai.proxyma.core.settings.dto.GuardrailSettings;
import ai.proxyma.core.settings.service.SettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The "server file paths" check exists only where there is a server (2026-09-29). On a desktop
 * install every path in an answer is the user's own, and redacting it only broke answers.
 */
class PathLeakEditionTest {

    private static GuardrailService service(MockEnvironment env) {
        GuardrailSettings s = new GuardrailSettings();
        s.setDetectPathLeak(true);
        SettingsService settings = mock(SettingsService.class);
        when(settings.getSettings(eq(GuardrailSettings.PREFIX), eq(GuardrailSettings.class))).thenReturn(s);
        GuardrailService service = new GuardrailService(settings, null, null, null, null, null);
        if (env != null) service.setEnvironment(env);
        return service;
    }

    @Test
    @DisplayName("a desktop install - no accounts, no uploads - does not run the path check")
    void desktopHasNoServerPaths() {
        assertFalse(service(new MockEnvironment()).settings().isDetectPathLeak());
    }

    @Test
    @DisplayName("Enterprise Server and the home server keep it")
    void serversKeepIt() {
        assertTrue(service(new MockEnvironment().withProperty("proxyma.multi-user", "true"))
                .settings().isDetectPathLeak());
        assertTrue(service(new MockEnvironment().withProperty("proxyma.file-upload.enabled", "true"))
                .settings().isDetectPathLeak());
    }

    @Test
    @DisplayName("built by hand with no environment, every rule stays on")
    void noEnvironmentKeepsEveryRule() {
        assertTrue(service(null).settings().isDetectPathLeak());
    }
}
