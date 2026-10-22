package ai.proxyma.core.tools.jira;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.jira.JiraConnector;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The agent's Jira tools on an Atlassian Cloud site, chosen by the connection's product line the way
 * the indexing session decides it (an email and an API token is Cloud). Search goes to
 * {@code POST /rest/api/3/search/jql}, and the site is the address even when the connection is a
 * Cloud one made as Confluence and typed with {@code /wiki} - one Cloud connection serves both.
 */
class JiraCloudToolsTest {

    private LoopbackApi api;
    private ToolConnectorResolver resolver;
    private JiraToolService tools;

    @BeforeEach
    void start() {
        api = LoopbackApi.start();
        resolver = mock(ToolConnectorResolver.class);
        tools = new JiraToolService(resolver, new JiraConnector(RestClient.builder()), mock(McpGuardService.class));
    }

    @AfterEach
    void stop() {
        api.close();
    }

    @Test
    @DisplayName("a Cloud connection searches with search/jql at the site, even when typed with /wiki")
    void cloudSearch() {
        when(resolver.target(eq(ConnectorType.JIRA), any(), any(), eq(Access.READ))).thenReturn(
                new ToolConnectorResolver.Target(api.baseUrl() + "/wiki", "ana@example.com:api-token", AuthKind.BASIC));
        api.on("POST", "/rest/api/3/search/jql").json("{'issues':[{'key':'HELP-1','fields':{'summary':'Printer jams',"
                + "'status':{'name':'Open'}}}],'isLast':true}");

        List<Map<String, Object>> found = tools.searchJiraIssues("project = HELP", 20);

        assertThat(found).extracting(m -> m.get("key")).containsExactly("HELP-1");
        assertThat(found.get(0).get("url")).isEqualTo(api.baseUrl() + "/browse/HELP-1");
        assertThat(api.requests()).extracting(LoopbackApi.Recorded::path).containsExactly("/rest/api/3/search/jql");
    }

    @Test
    @DisplayName("a Cloud connection typed with /jira, as the browser shows it, searches at the site")
    void cloudSearchTypedWithJira() {
        when(resolver.target(eq(ConnectorType.JIRA), any(), any(), eq(Access.READ))).thenReturn(
                new ToolConnectorResolver.Target(api.baseUrl() + "/jira/", "ana@example.com:api-token", AuthKind.BASIC));
        api.on("POST", "/rest/api/3/search/jql").json("{'issues':[{'key':'HELP-1','fields':{'summary':'Printer jams'}}],"
                + "'isLast':true}");

        assertThat(tools.searchJiraIssues("project = HELP", 20)).extracting(m -> m.get("key")).containsExactly("HELP-1");
        assertThat(api.requests()).extracting(LoopbackApi.Recorded::path).containsExactly("/rest/api/3/search/jql");
    }

    @Test
    @DisplayName("a Data Center connection keeps the GET search on the server as typed")
    void dataCenterSearch() {
        when(resolver.target(eq(ConnectorType.JIRA), any(), any(), eq(Access.READ))).thenReturn(
                new ToolConnectorResolver.Target(api.baseUrl() + "/jira", "pat", AuthKind.TOKEN));
        api.on("GET", "/jira/rest/api/2/search").json("{'issues':[{'key':'HELP-2','fields':{'summary':'Laptops'}}],'total':1}");

        assertThat(tools.searchJiraIssues("project = HELP", 20)).extracting(m -> m.get("key")).containsExactly("HELP-2");
    }

    @Test
    @DisplayName("a Data Center address pasted from an issue page searches at the server's root, its context path kept")
    void dataCenterPastedFromAPage() {
        when(resolver.target(eq(ConnectorType.JIRA), any(), any(), eq(Access.READ))).thenReturn(
                new ToolConnectorResolver.Target(api.baseUrl() + "/jira/browse/HELP-2?focus=1", "pat", AuthKind.TOKEN));
        api.on("GET", "/jira/rest/api/2/search").json("{'issues':[{'key':'HELP-2','fields':{'summary':'Laptops'}}],'total':1}");

        List<Map<String, Object>> found = tools.searchJiraIssues("project = HELP", 20);

        assertThat(found).extracting(m -> m.get("key")).containsExactly("HELP-2");
        assertThat(found.get(0).get("url")).isEqualTo(api.baseUrl() + "/jira/browse/HELP-2");
        assertThat(api.requests()).extracting(LoopbackApi.Recorded::path).containsExactly("/jira/rest/api/2/search");
    }
}
