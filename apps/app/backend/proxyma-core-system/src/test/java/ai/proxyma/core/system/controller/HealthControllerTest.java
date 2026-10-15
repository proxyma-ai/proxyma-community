package ai.proxyma.core.system.controller;

import ai.proxyma.core.system.util.SystemInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The health payload's job is to stay SMALL, and that is what these pin.
 *
 * /api/v1/config is the cautionary tale one directory away: it began as a version readout and grew
 * into the SPA's feature-flag feed, until it carried orgMode, orgCredentialConfigured, branding,
 * sandbox, fileUpload and homeServer - none of which answers "is it up and what is it", and all of
 * which a monitor now has to ignore. Nothing stopped that happening because nothing was watching
 * the shape.
 *
 * So the key set is asserted EXACTLY. A new field fails this test, which is the point: it forces
 * whoever adds one to decide in the open whether a health check is really where it belongs.
 */
class HealthControllerTest {

    private static final SystemInfo LINUX = new SystemInfo(false, false, true, false);

    private static Map<String, Object> payload(HealthController c) {
        ResponseEntity<Map<String, Object>> response = c.health().block();
        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return response.getBody();
    }

    @Test
    @DisplayName("the payload is exactly six fields, and no more")
    void payloadIsExactlyTheseFields() {
        Map<String, Object> body = payload(new HealthController("0.10.2", false, LINUX));

        assertThat(body).containsOnlyKeys(
                "status", "edition", "version", "platform", "uptimeSeconds", "uptime");
    }

    @Test
    @DisplayName("none of the configuration endpoint's fields leak into it")
    void carriesNoConfigurationFields() {
        Map<String, Object> body = payload(new HealthController("0.10.2", false, LINUX));

        // The six that made /api/v1/config unusable as a health check. Named individually rather
        // than covered by the exact-keys test above, because this is the list somebody will be
        // tempted to add back one field at a time.
        assertThat(body.keySet()).doesNotContainAnyElementsOf(Set.of(
                "orgMode", "orgCredentialConfigured", "branding", "sandbox", "fileUpload",
                "homeServer", "serverPort"));
    }

    @Test
    @DisplayName("it reports the version and platform it was given")
    void reportsVersionAndPlatform() {
        assertThat(payload(new HealthController("0.10.2", false, LINUX)))
                .containsEntry("status", "ok")
                .containsEntry("version", "0.10.2")
                .containsEntry("platform", "linux");

        assertThat(payload(new HealthController("1.2.3", false, new SystemInfo(true, false, false, false))))
                .containsEntry("platform", "windows");
    }

    @Nested
    @DisplayName("edition")
    class Edition {

        /**
         * THE HOME SERVER IS THE DESKTOP JAR, and this is the distinction the flag exists for. The
         * SPA cannot tell them apart from its build mode, which is exactly why a caller with no
         * build mode at all - a monitor, or somebody on a support call - needs the answer here.
         */
        @Test
        @DisplayName("the desktop build with the home-server flag is the Home Server")
        void homeServerFlagNamesTheEdition() {
            assertThat(payload(new HealthController("0.10.2", false, LINUX)))
                    .containsEntry("edition", "Desktop");
            assertThat(payload(new HealthController("0.10.2", true, LINUX)))
                    .containsEntry("edition", "Home Server");
        }
    }

    @Nested
    @DisplayName("uptime")
    class Uptime {

        @Test
        @DisplayName("reads as a duration a person can parse at a glance")
        void humanReadable() {
            assertThat(HealthController.humanUptime(Duration.ofSeconds(45))).isEqualTo("0m 45s");
            assertThat(HealthController.humanUptime(Duration.ofMinutes(5))).isEqualTo("5m 0s");
            assertThat(HealthController.humanUptime(Duration.ofMinutes(125))).isEqualTo("2h 5m");
            assertThat(HealthController.humanUptime(Duration.ofHours(50))).isEqualTo("2d 2h 0m");
        }

        /** The machine-readable half has to stay a number, whatever the string beside it says. */
        @Test
        @DisplayName("is also a plain number of seconds")
        void machineReadable() {
            assertThat(payload(new HealthController("0.10.2", false, LINUX)).get("uptimeSeconds"))
                    .isInstanceOf(Long.class);
        }
    }
}
