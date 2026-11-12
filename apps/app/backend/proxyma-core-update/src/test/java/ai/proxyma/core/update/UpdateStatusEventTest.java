package ai.proxyma.core.update;

import ai.proxyma.core.common.update.UpdateStatusChangedEvent;
import ai.proxyma.core.settings.dto.UpdatesSettings;
import ai.proxyma.core.settings.service.SettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The updater announces each status change (UpdateStatusChangedEvent), which the page's event
 * stream carries to the desktop window's "Restart to update" control - since 2026-11-04, when that
 * control was added and the title row had to learn of a staged update without polling for it.
 */
class UpdateStatusEventTest {

    private static final String API = "https://api.example.test/repos/proxyma-ai/proxyma-releases";

    private record Fixture(UpdateService service, MockRestServiceServer server, List<UpdateStatusChangedEvent> events) {}

    private static Fixture fixture(String releasesApi) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        UpdatesSettings settings = new UpdatesSettings();
        settings.setAutoUpdate(false);
        SettingsService settingsService = mock(SettingsService.class);
        when(settingsService.getSettings(UpdatesSettings.PREFIX, UpdatesSettings.class)).thenReturn(settings);
        UpdateService service = new UpdateService(builder, settingsService, mock(UpdateHistory.class));
        ReflectionTestUtils.setField(service, "currentVersion", "0.16.0");
        ReflectionTestUtils.setField(service, "releasesApiUrl", releasesApi);
        List<UpdateStatusChangedEvent> events = new CopyOnWriteArrayList<>();
        service.setEventPublisher(e -> events.add((UpdateStatusChangedEvent) e));
        return new Fixture(service, server, events);
    }

    @Test
    @DisplayName("a release found is announced with its version, after the check that found it")
    void foundIsAnnounced() {
        Fixture f = fixture(API);
        f.server().expect(requestTo(API + "/releases/latest")).andRespond(withSuccess(
                "{\"tag_name\":\"v0.17.0\",\"assets\":[]}", MediaType.APPLICATION_JSON));

        f.service().checkNow();

        assertThat(f.service().getStatus()).isEqualTo(UpdateService.UpdateStatus.AVAILABLE);
        assertThat(f.events()).extracting(UpdateStatusChangedEvent::status).containsExactly("CHECKING", "AVAILABLE");
        assertThat(f.events().get(1).version()).isEqualTo("v0.17.0");
    }

    @Test
    @DisplayName("a status written again unchanged is not news - nothing is announced")
    void unchangedIsSilent() {
        // No releases API: every check answers UP_TO_DATE, which is the status it starts with.
        Fixture f = fixture("");
        f.service().checkNow();
        f.service().checkNow();

        assertThat(f.service().getStatus()).isEqualTo(UpdateService.UpdateStatus.UP_TO_DATE);
        assertThat(f.events()).isEmpty();
    }

    @Test
    @DisplayName("a check that cannot reach the releases API leaves a staged update READY - it is still there to apply")
    void unreachableKeepsStaged() {
        Fixture f = fixture(API);
        f.server().expect(requestTo(API + "/releases/latest")).andRespond(withServerError());
        ReflectionTestUtils.setField(f.service(), "availableVersion", "v0.17.0");
        ReflectionTestUtils.setField(f.service(), "status", UpdateService.UpdateStatus.READY);

        f.service().checkNow();

        assertThat(f.service().getStatus()).isEqualTo(UpdateService.UpdateStatus.READY);
        assertThat(f.events()).extracting(UpdateStatusChangedEvent::status).containsExactly("CHECKING", "READY");
        assertThat(f.events().get(1).version()).isEqualTo("v0.17.0");
        f.server().verify();
    }
}
