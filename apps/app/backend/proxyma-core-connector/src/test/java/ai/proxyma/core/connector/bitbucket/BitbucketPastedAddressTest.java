package ai.proxyma.core.connector.bitbucket;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import ai.proxyma.core.connector.spi.SourceContext;
import ai.proxyma.core.connector.testing.LoopbackApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A Bitbucket Data Center address pasted from one of its pages is spoken to at the server's root,
 * the path it is installed under kept - and a page answering where the API should is said as that,
 * not as a server that could not be reached.
 */
class BitbucketPastedAddressTest {

    private LoopbackApi api;
    private final BitbucketConnector connector = new BitbucketConnector(RestClient.builder());

    @BeforeEach
    void start() {
        api = LoopbackApi.start();
    }

    @AfterEach
    void stop() {
        api.close();
    }

    private ConnectorSession session(String serverUrl) {
        return connector.open(SourceContext.of(ConnectorType.BITBUCKET, Map.of("projectKey", "PROJ", "repoSlug", "demo"),
                ResolvedCredentials.token(serverUrl, "test-token"), null));
    }

    @Test
    @DisplayName("an address pasted from a repository's page reaches the API under the server's context path")
    void pastedFromARepositoryPage() {
        api.on("GET", "/bitbucket/rest/api/1.0/projects/PROJ/repos/demo").json("{'slug':'demo','name':'Demo'}");

        try (ConnectorSession s = session(api.baseUrl() + "/bitbucket/projects/PROJ/repos/demo/browse?at=main")) {
            assertThat(s.verifyAccess().ok()).isTrue();
        }
        assertThat(api.requests()).extracting(LoopbackApi.Recorded::path)
                .containsExactly("/bitbucket/rest/api/1.0/projects/PROJ/repos/demo");
        assertThat(connector.probeRepoAs(api.baseUrl() + "/bitbucket/dashboard", "test-token", "PROJ", "demo").slug)
                .isEqualTo("demo");
        assertThat(connector.webUrl(Map.of("projectKey", "PROJ", "repoSlug", "demo"), "https://bb.corp/users/ana/repos/x"))
                .isEqualTo("https://bb.corp/projects/PROJ/repos/demo");
    }

    @Test
    @DisplayName("a sign-in page answering 200 is a page instead of the API, not an unreachable server")
    void aPageInsteadOfTheApi() {
        api.on("GET", "/rest/api/1.0/projects/PROJ/repos/demo")
                .body("<!DOCTYPE html><html><body>Sign in</body></html>".getBytes(StandardCharsets.UTF_8), "text/html");

        String sentence = "The server answered with a page instead of its API. Check the URL is the server's own address.";
        try (ConnectorSession s = session(api.baseUrl())) {
            assertThat(s.verifyAccess().detail()).isEqualTo(sentence);
        }
        assertThatThrownBy(() -> connector.probeRepoAs(api.baseUrl(), "test-token", "PROJ", "demo"))
                .hasMessage(sentence);
    }
}
