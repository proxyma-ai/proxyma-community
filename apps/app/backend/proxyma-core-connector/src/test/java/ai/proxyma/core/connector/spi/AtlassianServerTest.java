package ai.proxyma.core.connector.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Data Center address pasted from a page of the server is spoken to at the server's root - and a
 * context path the server is installed under is part of that root, so it survives.
 */
class AtlassianServerTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("Jira: cut at the first web-UI segment, keeping a context path")
    @CsvSource({
            "https://host, https://host",
            "' https://host/ ', https://host",
            "https://host/jira, https://host/jira",
            "https://host/jira/, https://host/jira",
            "https://host:8443/jira, https://host:8443/jira",
            "https://host/browse/PROJ-1, https://host",
            "https://host/secure/Dashboard.jspa, https://host",
            "https://host/SECURE/RapidBoard.jspa?rapidView=1, https://host",
            "https://host/projects/PROJ, https://host",
            "https://host/projects/PROJ/issues/PROJ-1, https://host",
            "https://host/jira/browse/PROJ-1, https://host/jira",
            "https://host/jira/issues/?jql=project%3DPROJ, https://host/jira",
            "https://host/plugins/servlet/mobile, https://host",
            "https://host/servicedesk/customer/portals, https://host",
            "https://host/jira/rest/api/2/myself, https://host/jira",
            "https://host/login.jsp?os_destination=%2Fbrowse, https://host",
            "https://host/jira?filter=1#top, https://host/jira",
            "https://host/projectsite/browse/X-1, https://host/projectsite",
            "https://host/plugins, https://host/plugins",
    })
    void jira(String pasted, String root) {
        assertThat(AtlassianServer.jiraRoot(pasted)).isEqualTo(root);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("Confluence: cut at the first web-UI segment, keeping a context path")
    @CsvSource({
            "https://host, https://host",
            "https://host/confluence, https://host/confluence",
            "https://host/wiki/, https://host/wiki",
            "https://host/display/SPACE/Page, https://host",
            "https://host/confluence/spaces/SPACE/overview, https://host/confluence",
            "https://host/pages/viewpage.action?pageId=1, https://host",
            "https://host/dashboard.action, https://host",
            "https://host/wiki/dashboard.action#all-updates, https://host/wiki",
            "https://host/x/AbCd, https://host",
            "https://host/confluence/rest/api/space, https://host/confluence",
            "https://host/xwiki, https://host/xwiki",
    })
    void confluence(String pasted, String root) {
        assertThat(AtlassianServer.confluenceRoot(pasted)).isEqualTo(root);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("Bitbucket: cut at the first web-UI segment, keeping a context path")
    @CsvSource({
            "https://bb, https://bb",
            "https://bb/bitbucket/, https://bb/bitbucket",
            "https://bb/projects/P/repos/r/browse, https://bb",
            "https://bb/bitbucket/projects/P, https://bb/bitbucket",
            "https://bb/users/ana/repos/x, https://bb",
            "https://bb/dashboard, https://bb",
            "https://bb/plugins/servlet/applinks/whoami, https://bb",
            "https://bb/scm/p/r.git, https://bb",
            "https://bb/account, https://bb",
            "https://bb/admin/users, https://bb",
            "https://bb/rest/api/1.0/projects, https://bb",
            "https://bb/bitbucket/login?next=/projects, https://bb/bitbucket",
            "https://bb/dashboards, https://bb/dashboards",
    })
    void bitbucket(String pasted, String root) {
        assertThat(AtlassianServer.bitbucketRoot(pasted)).isEqualTo(root);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("nothing in, nothing out")
    void nullStaysNull() {
        assertThat(AtlassianServer.jiraRoot(null)).isNull();
    }
}
