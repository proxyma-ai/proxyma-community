package ai.proxyma.core.connector.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An Atlassian Cloud address is its site, whatever page of it was pasted - a Jira Cloud address
 * copied from the browser ends in /jira, and that path serves the web app to every API call
 * (0.36.3 feedback: "Cannot connect to atlassian jira cloud").
 */
class AtlassianCloudTest {

    @Test
    @DisplayName("on atlassian.net the site is the scheme and host, whatever path was pasted")
    void atlassianNet() {
        assertThat(AtlassianCloud.site("https://acme.atlassian.net")).isEqualTo("https://acme.atlassian.net");
        assertThat(AtlassianCloud.site(" https://acme.atlassian.net/ ")).isEqualTo("https://acme.atlassian.net");
        assertThat(AtlassianCloud.site("https://acme.atlassian.net/jira")).isEqualTo("https://acme.atlassian.net");
        assertThat(AtlassianCloud.site("https://acme.atlassian.net/wiki/")).isEqualTo("https://acme.atlassian.net");
        assertThat(AtlassianCloud.site("https://ACME.Atlassian.NET/jira/software/projects/RH/boards/1"))
                .isEqualTo("https://ACME.Atlassian.NET");
        assertThat(AtlassianCloud.site("https://acme.atlassian.net/wiki/spaces/OPS/overview"))
                .isEqualTo("https://acme.atlassian.net");
    }

    @Test
    @DisplayName("on any other host only a trailing /wiki or /jira goes")
    void otherHosts() {
        assertThat(AtlassianCloud.site("https://help.example.com/jira")).isEqualTo("https://help.example.com");
        assertThat(AtlassianCloud.site("http://127.0.0.1:8080/WIKI/")).isEqualTo("http://127.0.0.1:8080");
        assertThat(AtlassianCloud.site("https://help.example.com/support")).isEqualTo("https://help.example.com/support");
        assertThat(AtlassianCloud.site("https://help.example.com/jiraweb")).isEqualTo("https://help.example.com/jiraweb");
    }

    @Test
    @DisplayName("an atlassian.net address is told apart from any other")
    void cloudHost() {
        assertThat(AtlassianCloud.isCloudHost("https://acme.atlassian.net/jira")).isTrue();
        assertThat(AtlassianCloud.isCloudHost("https://jira.corp.example")).isFalse();
        assertThat(AtlassianCloud.isCloudHost(null)).isFalse();
    }
}
