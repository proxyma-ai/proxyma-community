package ai.proxyma.core.connector.jira;

import ai.proxyma.core.connector.testing.LoopbackApi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The agent's Jira search on each product line. Atlassian removed Cloud's {@code /rest/api/2/search}
 * (410 Gone) for {@code POST /rest/api/3/search/jql}, which takes the JQL in a body, answers only ids
 * unless fields are named, and writes descriptions in Atlassian Document Format; Data Center keeps
 * the GET. A Cloud search sent the Data Center way failed every time.
 */
class JiraCloudSearchTest {

    private LoopbackApi api;
    private final JiraConnector connector = new JiraConnector(RestClient.builder());

    @BeforeEach
    void start() {
        api = LoopbackApi.start();
    }

    @AfterEach
    void stop() {
        api.close();
    }

    @Test
    @DisplayName("on Cloud the JQL is POSTed to /rest/api/3/search/jql with the fields named, and an ADF description reads as text")
    void cloudSearch() throws Exception {
        api.on("GET", "/rest/api/2/search").status(410).json("{'errorMessages':['Use /search/jql.']}");
        api.on("POST", "/rest/api/3/search/jql").json("{'issues':[{'id':'10001','key':'HELP-1','fields':{"
                + "'summary':'Printer jams','status':{'name':'Open'},'issuetype':{'name':'Bug'},"
                + "'description':{'type':'doc','version':1,'content':[{'type':'paragraph','content':"
                + "[{'type':'text','text':'The codeword is BERGAMOT.'}]}]}}}],'isLast':true}");

        List<Map<String, Object>> found = connector.searchIssuesSummary(api.baseUrl(), "ana@example.com:api-token",
                "project = HELP AND text ~ \"C++\"", 5, true);

        assertThat(found).hasSize(1);
        assertThat(found.get(0)).containsEntry("key", "HELP-1").containsEntry("status", "Open")
                .containsEntry("url", api.baseUrl() + "/browse/HELP-1");
        assertThat((String) found.get(0).get("description")).contains("BERGAMOT");
        LoopbackApi.Recorded sent = api.last();
        assertThat(sent.path()).isEqualTo("/rest/api/3/search/jql");
        assertThat(sent.header("Authorization")).isEqualTo("Basic " + Base64.getEncoder()
                .encodeToString("ana@example.com:api-token".getBytes(StandardCharsets.UTF_8)));
        JsonNode body = new ObjectMapper().readTree(sent.body());
        // The JQL travels whole in the body - nothing to encode, so a '+' stays a '+'.
        assertThat(body.path("jql").asText()).isEqualTo("project = HELP AND text ~ \"C++\"");
        assertThat(body.path("maxResults").asInt()).isEqualTo(5);
        assertThat(body.path("fields").toString()).contains("summary", "status", "description");
        assertThat(api.requests("/rest/api/2/search")).isEmpty();
    }

    @Test
    @DisplayName("Data Center keeps the GET on /rest/api/2/search")
    void dataCenterSearch() {
        api.on("GET", "/rest/api/2/search").json("{'issues':[{'key':'HELP-2','fields':{'summary':'Laptops',"
                + "'status':{'name':'Done'},'description':'Wiki text'}}],'total':1}");

        List<Map<String, Object>> found = connector.searchIssuesSummary(api.baseUrl(), "pat", "project = HELP", 5, false);

        assertThat(found).extracting(m -> m.get("key")).containsExactly("HELP-2");
        assertThat(api.last().header("Authorization")).isEqualTo("Bearer pat");
        assertThat(api.requests("/rest/api/3/search/jql")).isEmpty();
    }
}
