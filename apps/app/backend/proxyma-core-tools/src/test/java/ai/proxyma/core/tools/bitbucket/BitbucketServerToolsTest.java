package ai.proxyma.core.tools.bitbucket;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.bitbucket.BitbucketConnector;
import ai.proxyma.core.connector.spi.AuthKind;
import ai.proxyma.core.connector.testing.LoopbackApi;
import ai.proxyma.core.settings.service.McpGuardService;
import ai.proxyma.core.tools.ToolConnectorResolver;
import ai.proxyma.core.tools.ToolConnectorResolver.Access;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The agent's Bitbucket Data Center tools speak to the server's root, whatever page of it the
 * connection's address was pasted from - and keep the path the server is installed under.
 */
class BitbucketServerToolsTest {

    private LoopbackApi api;
    private ToolConnectorResolver resolver;
    private BitbucketToolService tools;

    @BeforeEach
    void start() {
        api = LoopbackApi.start();
        resolver = mock(ToolConnectorResolver.class);
        tools = new BitbucketToolService(resolver, new BitbucketConnector(RestClient.builder()), mock(McpGuardService.class),
                null);
    }

    @AfterEach
    void stop() {
        api.close();
    }

    @Test
    @DisplayName("an address pasted from a repository's page lists branches under the server's context path")
    void pastedFromARepositoryPage() {
        when(resolver.target(eq(ConnectorType.BITBUCKET), any(), any(), eq(Access.READ))).thenReturn(
                new ToolConnectorResolver.Target(api.baseUrl() + "/bitbucket/projects/PROJ/repos/demo/browse", "pat",
                        AuthKind.TOKEN));
        api.on("GET", "/bitbucket/rest/api/1.0/projects/PROJ/repos/demo/branches")
                .json("{'values':[{'displayId':'main'}],'isLastPage':true}");

        assertThat(tools.listBranches("PROJ", "demo", null)).extracting(m -> m.get("name")).containsExactly("main");
        assertThat(api.requests()).extracting(LoopbackApi.Recorded::path)
                .containsExactly("/bitbucket/rest/api/1.0/projects/PROJ/repos/demo/branches");
    }
}
