package ai.proxyma.it.app;

import ai.proxyma.it.harness.Http;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The desktop edition starts, says what it is, and serves its own user interface.
 *
 * <p>Thin-looking and load-bearing. Every other journey in this package assumes the app booted,
 * so when the whole suite goes red these are the tests whose failure says why - and two of them
 * cover failures this project has actually shipped.
 */
@DisplayName("Desktop: the installation starts and serves itself")
class DesktopSmokeIT extends DesktopJourney {

    @Test
    @DisplayName("health answers with the edition and the version, and nothing else")
    void healthIsSmall() {
        JsonNode health = api().get("/api/v1/health").ok().json();

        assertThat(health.path("status").asText()).isEqualTo("ok");
        assertThat(health.path("edition").asText()).isEqualTo("Desktop");
        assertThat(health.path("version").asText()).isNotBlank();

        // The key set is pinned by HealthControllerTest as a unit test. What THIS adds is that the
        // pinned shape is what the wire actually carries once Jackson, the WebFlux encoder and the
        // real HTTP stack have had it - a field can be pinned in a unit test and still be absent
        // here because it serialized to null and was dropped.
        assertThat(fieldNames(health))
                .containsExactlyInAnyOrder("status", "edition", "version", "platform",
                        "uptimeSeconds", "uptime");
    }

    @Test
    @DisplayName("the SPA is served from inside the jar, and its assets load")
    void spaIsServed() {
        Http.Response index = api().get("/").ok();
        assertThat(index.header("content-type")).contains("text/html");
        assertThat(index.body()).contains("id=\"root\"");

        // THE ASSERTION THAT MATTERS. A jar built with -Dskip.frontend.build=true still serves
        // index.html, still answers every API call in this suite, and shows a blank page - the
        // whole SPA is two entries instead of thirty-one and disappears into the rounding of a
        // 342 MB jar. Env.requireJar counts the entries; this proves the server will hand one over.
        var script = java.util.regex.Pattern
                .compile("<script[^>]*\\ssrc=\"([^\"]+)\"")
                .matcher(index.body());
        assertThat(script.find()).as("index.html references a bundle").isTrue();
        String src = script.group(1);

        Http.Response bundle = api().get(src);
        assertThat(bundle.status()).as("the bundle %s is actually in the jar", src).isEqualTo(200);
        assertThat(bundle.body()).isNotEmpty();
    }

    @Test
    @DisplayName("an unknown client route falls through to the SPA rather than 404ing")
    void clientRoutingFallsThrough() {
        // React Router owns /settings, /sources and the rest; the server has no such endpoints.
        // A deep link or a refresh on one of them must return index.html, or every bookmark into
        // the app is broken while the app itself looks fine from the home page.
        Http.Response deepLink = api().get("/settings");
        assertThat(deepLink.status()).isEqualTo(200);
        assertThat(deepLink.header("content-type")).contains("text/html");
    }

    @Test
    @DisplayName("an unknown API route 404s instead of being answered with the SPA")
    void unknownApiRoutesDoNotFallThrough() {
        // The other half of the rule above, and the one that goes wrong quietly: a catch-all that
        // is too greedy answers a mistyped API path with index.html and HTTP 200, so a client
        // parsing JSON gets "Unexpected token <" and nobody sees a 404 anywhere.
        Http.Response missing = api().get("/api/v1/there-is-no-such-endpoint");
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.body()).doesNotContain("<div id=\"root\"");
    }

    @Test
    @DisplayName("config advertises desktop's feature set, and no credential")
    void configDescribesTheEdition() {
        JsonNode config = api().get("/api/v1/config").ok().json();

        // The vestigial org-mode switch is gone, not merely off: stored, it once turned the Host
        // check and CORS off on a single-user edition.
        assertThat(config.has("orgMode")).as("no org-mode switch on a single-user edition").isFalse();
        assertThat(config.path("version").asText()).isNotBlank();

        // /api/v1/config is the SPA's feature-flag feed and it is unauthenticated on desktop.
        // Nothing resembling a secret may ride along with it.
        assertThat(config.toString().toLowerCase())
                .doesNotContain("apikey")
                .doesNotContain("password")
                .doesNotContain("secret");
    }

    @Test
    @DisplayName("the DPA ships inside the jar, so an offline desktop can read it")
    void legalDocumentsAreEmbedded() {
        // The DPA is a classpath resource under proxyma-core-system, deliberately: a correction to
        // it reaches customers on the next RELEASE, not on a deploy. If this 404s, the document
        // did not make it into the artifact and an installed copy has no legal text at all.
        Http.Response dpa = api().get("/api/v1/legal/data-processing-agreement");
        assertThat(dpa.status()).isEqualTo(200);
        assertThat(dpa.body()).isNotBlank();
    }

    @Test
    @DisplayName("the MCP surface is up and lists tools")
    void mcpIsAvailable() {
        assertThat(api().get("/api/v1/mcp/health").status()).isEqualTo(200);
        JsonNode tools = api().get("/api/v1/mcp/tools").ok().json();
        assertThat(tools.isArray() || tools.has("tools")).isTrue();
    }

    @Test
    @DisplayName("nothing was sent to the telemetry or release endpoints")
    void theSuiteNeverPhonesHome() {
        // Both URLs are blanked when the harness boots the app, which is a supported
        // configuration rather than a test hook - an air-gapped install runs the same way. This
        // asserts the app AGREED: an update check that found a URL anyway would show up as a
        // status other than the up-to-date-and-stop one that a blank URL produces.
        JsonNode update = api().get("/api/v1/update/status").ok().json();
        assertThat(update.path("status").asText()).isNotEqualTo("UPDATE_AVAILABLE");
        assertThat(app.process().logged("telemetry.proxyma.ai")).isFalse();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
