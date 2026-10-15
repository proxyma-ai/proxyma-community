package ai.proxyma.core.update;

import ai.proxyma.core.settings.dto.UpdatesSettings;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.system.parts.PartsManifest;
import ai.proxyma.core.system.parts.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * A newer dev build with nothing for THIS platform is passed over IN SILENCE (the maintainer,
 * 2026-10-10): up to date, and no line about a version this computer cannot have. It was named from
 * 2026-10-08 ("has no version for this computer yet") until then.
 */
class UpdateSkippedDevBuildTest {

    private static final String API = "https://api.example.test/repos/proxyma-ai/proxyma-releases";

    @TempDir Path parts;

    @AfterEach
    void clearProperty() {
        System.clearProperty(UpdateService.PARTS_DIR_PROPERTY);
    }

    private UpdateService service(String listJson) {
        System.setProperty(UpdateService.PARTS_DIR_PROPERTY, parts.toString());
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(API + "/releases?per_page=30")).andRespond(withSuccess(listJson, MediaType.APPLICATION_JSON));
        UpdatesSettings settings = new UpdatesSettings();
        settings.setDevBuilds(true);
        settings.setAutoUpdate(false);
        SettingsService settingsService = mock(SettingsService.class);
        when(settingsService.getSettings(UpdatesSettings.PREFIX, UpdatesSettings.class)).thenReturn(settings);
        UpdateService service = new UpdateService(builder, settingsService, mock(UpdateHistory.class));
        ReflectionTestUtils.setField(service, "currentVersion", "0.29.2");
        ReflectionTestUtils.setField(service, "releasesApiUrl", API);
        return service;
    }

    private static String release(String tag, boolean prerelease, String... assets) {
        StringBuilder a = new StringBuilder();
        for (String name : assets) {
            if (!a.isEmpty()) a.append(',');
            a.append("{\"name\":\"%s\",\"browser_download_url\":\"https://example.test/download/%s/%s\"}".formatted(name, tag, name));
        }
        return "{\"tag_name\":\"%s\",\"draft\":false,\"prerelease\":%s,\"assets\":[%s]}".formatted(tag, prerelease, a);
    }

    @Test
    @DisplayName("a dev build with only another platform's half: up to date, and nothing is said")
    void passesOverTheBuildItCannotApply() {
        String platform = Platform.current();
        assumeTrue(platform != null, "the parts channel needs a supported platform");
        String other = platform.startsWith("windows") ? "parts-linux-x64.txt" : "parts-windows-x64.txt";
        String list = "[" + release("v0.29.3-dev2685", true, other, "SHA256SUMS.txt", "SHA256SUMS.txt.sig") + ","
                + release("v0.29.2", false, PartsManifest.assetName(platform), "SHA256SUMS.txt", "SHA256SUMS.txt.sig") + "]";
        UpdateService service = service(list);

        service.checkNow();

        assertEquals(UpdateService.UpdateStatus.UP_TO_DATE, service.getStatus());
        assertNull(service.getLastError());
    }

    @Test
    @DisplayName("a dev build that has this platform's half is offered, with nothing to say")
    void offersTheBuildItCanApply() {
        String platform = Platform.current();
        assumeTrue(platform != null, "the parts channel needs a supported platform");
        String list = "[" + release("v0.29.3-dev2686", true, PartsManifest.assetName(platform), "SHA256SUMS.txt", "SHA256SUMS.txt.sig") + ","
                + release("v0.29.2", false, PartsManifest.assetName(platform), "SHA256SUMS.txt", "SHA256SUMS.txt.sig") + "]";
        UpdateService service = service(list);

        service.checkNow();

        assertEquals(UpdateService.UpdateStatus.AVAILABLE, service.getStatus());
        assertEquals("v0.29.3-dev2686", service.getAvailableVersion());
        assertNull(service.getLastError());
    }
}
