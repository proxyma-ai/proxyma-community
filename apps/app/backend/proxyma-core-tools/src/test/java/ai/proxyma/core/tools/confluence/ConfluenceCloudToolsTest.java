package ai.proxyma.core.tools.confluence;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.confluence.ConfluenceConnector;
import ai.proxyma.core.connector.spi.AuthKind;
import ai.proxyma.core.connector.testing.LoopbackApi;
import ai.proxyma.core.settings.service.McpGuardService;
import ai.proxyma.core.tools.ToolConnectorResolver;
import ai.proxyma.core.tools.ToolConnectorResolver.Access;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The agent's Confluence tools on an Atlassian Cloud site live under the site's {@code /wiki} -
 * whether the connection was typed with it or not, and a Cloud connection made as Jira never is -
 * while a Data Center server is spoken to at the address as typed.
 */
class ConfluenceCloudToolsTest {

    @Test
    @DisplayName("Cloud: the site's /wiki, typed or not; Data Center: the server as typed")
    void apiBase() {
        assertThat(ConfluenceToolService.apiBase(new ToolConnectorResolver.Target("https://acme.atlassian.net", "a:b",
                AuthKind.BASIC))).isEqualTo("https://acme.atlassian.net/wiki");
        assertThat(ConfluenceToolService.apiBase(new ToolConnectorResolver.Target("https://acme.atlassian.net/wiki/",
                "a:b", AuthKind.BASIC))).isEqualTo("https://acme.atlassian.net/wiki");
        assertThat(ConfluenceToolService.apiBase(new ToolConnectorResolver.Target("https://wiki.corp/confluence", "pat",
                AuthKind.TOKEN))).isEqualTo("https://wiki.corp/confluence");
    }

    @Test
    @DisplayName("a Cloud connection made as Jira lists the site's spaces under /wiki")
    void cloudListsUnderWiki() {
        try (LoopbackApi api = LoopbackApi.start()) {
            ToolConnectorResolver resolver = mock(ToolConnectorResolver.class);
            when(resolver.target(eq(ConnectorType.CONFLUENCE), any(), any(), eq(Access.READ))).thenReturn(
                    new ToolConnectorResolver.Target(api.baseUrl(), "ana@example.com:api-token", AuthKind.BASIC));
            api.on("GET", "/wiki/rest/api/space").json("{'results':[{'key':'TEAM','name':'Team handbook'}],'size':1}");
            ConfluenceToolService tools = new ConfluenceToolService(resolver, new ConfluenceConnector(RestClient.builder()),
                    mock(McpGuardService.class));

            assertThat(tools.listConfluenceSpaces()).extracting(m -> m.get("key")).containsExactly("TEAM");
            assertThat(api.last().path()).isEqualTo("/wiki/rest/api/space");
            assertThat(api.last().header("Authorization")).startsWith("Basic ");
        }
    }
}
