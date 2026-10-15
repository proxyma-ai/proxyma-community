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
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * A check that finds the release already staged leaves it staged. The guard for this compared the
 * status to READY after the check had set it to CHECKING, so it never fired: with auto-update on,
 * every scheduled check downloaded the staged release again, and Apply was refused while it did.
 */
class UpdateAlreadyStagedTest {

    private static final String API = "https://api.example.test/repos/proxyma-ai/proxyma-releases";

    @TempDir Path parts;

    @AfterEach
    void clearProperty() {
        System.clearProperty(UpdateService.PARTS_DIR_PROPERTY);
    }

    @Test
    @DisplayName("a check that finds the staged release again stays READY and downloads nothing")
    void stagedReleaseIsNotDownloadedAgain() {
        String platform = Platform.current();
        assumeTrue(platform != null, "the parts channel needs a supported platform");
        System.setProperty(UpdateService.PARTS_DIR_PROPERTY, parts.toString());

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String asset = PartsManifest.assetName(platform);
        // Exactly one request, the release lookup. A download would be a second, unexpected one.
        server.expect(requestTo(API + "/releases/latest")).andRespond(withSuccess("""
                {"tag_name":"v0.17.0","assets":[{"name":"%s",
                 "browser_download_url":"https://example.test/download/v0.17.0/%s"}]}"""
                .formatted(asset, asset), MediaType.APPLICATION_JSON));

        UpdatesSettings settings = new UpdatesSettings();
        settings.setAutoUpdate(true);
        SettingsService settingsService = mock(SettingsService.class);
        when(settingsService.getSettings(UpdatesSettings.PREFIX, UpdatesSettings.class)).thenReturn(settings);

        UpdateService service = new UpdateService(builder, settingsService, mock(UpdateHistory.class));
        ReflectionTestUtils.setField(service, "currentVersion", "0.16.0");
        ReflectionTestUtils.setField(service, "releasesApiUrl", API);
        // What a finished download leaves behind.
        ReflectionTestUtils.setField(service, "availableVersion", "v0.17.0");
        ReflectionTestUtils.setField(service, "status", UpdateService.UpdateStatus.READY);

        service.checkNow();

        assertEquals(UpdateService.UpdateStatus.READY, service.getStatus());
        server.verify();
    }
}
