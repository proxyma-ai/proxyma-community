package ai.proxyma.core.tools.confluence;

import ai.proxyma.core.common.net.HttpVerb;
import ai.proxyma.core.tools.ToolConnectorResolver;
import ai.proxyma.core.tools.ToolConnectorResolver.Access;
import ai.proxyma.core.tools.ToolIds;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.confluence.ConfluenceConnector;
import ai.proxyma.core.connector.confluence.ConfluenceConnector.ConfluencePageDto;
import ai.proxyma.core.connector.spi.AtlassianCloud;
import ai.proxyma.core.connector.spi.AtlassianServer;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.settings.service.McpGuardService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

@Service
class ConfluenceToolService {

    private static final String LABEL = "Confluence";

    // The connection's Read and Write switches are enforced where the connection is resolved, with
    // the access each call is about to use; see JiraToolService.
    private final ToolConnectorResolver resolver;
    private final ConfluenceConnector confluenceConnector;
    private final McpGuardService mcpGuard;

    ConfluenceToolService(ToolConnectorResolver resolver, ConfluenceConnector confluenceConnector,
                          McpGuardService mcpGuard) {
        this.resolver = resolver;
        this.confluenceConnector = confluenceConnector;
        this.mcpGuard = mcpGuard;
    }

    // The server and the credential are resolved ONCE per call and passed into the connector's
    // explicit-token overloads - no lock, no mutation of the shared connector (same treatment as
    // JiraToolService). A registered space is NOT required: registration and tool access are
    // independent.
    //
    // Every page operation below used to run a candidate loop, trying each known server in turn
    // until one resolved the page - a Confluence page ID does not say which server it came from,
    // the way a Jira issue key at least names its project. With one configured server there is
    // nothing to loop over, so a page that is not found is simply not found, and the error says so
    // on the first attempt instead of after several failed round trips.

    String getConfluencePage(String pageId) {
        ToolIds.number("page id", pageId);
        mcpGuard.checkConfluenceRead(SearchContext.getUserId());
        ConfluencePageDto dto = on(null, Access.READ, (url, token) -> confluenceConnector.fetchPageDetails(url, token, pageId));
        if (dto == null || dto.getContent() == null) {
            throw new IllegalArgumentException("Confluence page not found or not accessible: " + pageId);
        }
        return dto.getContent();
    }

    List<Map<String, String>> listConfluenceSpaces() {
        mcpGuard.checkConfluenceRead(SearchContext.getUserId());
        return on(null, Access.READ, (url, token) -> confluenceConnector.listAllSpaces(url, token)).stream()
                .map(s -> Map.of("key", s.getKey(), "name", s.getName()))
                .toList();
    }

    List<Map<String, Object>> searchConfluencePages(String cql, int maxResults) {
        mcpGuard.checkConfluenceRead(SearchContext.getUserId());
        return on(null, Access.READ, (url, token) -> confluenceConnector.searchPagesSummary(url, token, cql, maxResults));
    }

    String createConfluencePage(String spaceKey, String title, String bodyHtml, String parentPageId) {
        ToolIds.key("space key", spaceKey);
        if (parentPageId != null && !parentPageId.isBlank()) ToolIds.number("parent page id", parentPageId);
        return on(spaceKey, Access.WRITE, (url, token) -> confluenceConnector.createPage(url, token, spaceKey, title, bodyHtml, parentPageId));
    }

    void updateConfluencePage(String pageId, String title, String bodyHtml) {
        ToolIds.number("page id", pageId);
        run(null, Access.WRITE, (url, token) -> confluenceConnector.updatePage(url, token, pageId, title, bodyHtml));
    }

    String addConfluenceComment(String pageId, String bodyHtml) {
        ToolIds.number("page id", pageId);
        return on(null, Access.WRITE, (url, token) -> confluenceConnector.addComment(url, token, pageId, bodyHtml));
    }

    void updateConfluenceComment(String commentId, String bodyHtml) {
        ToolIds.number("comment id", commentId);
        run(null, Access.WRITE, (url, token) -> confluenceConnector.updateComment(url, token, commentId, bodyHtml));
    }

    void deleteConfluenceComment(String commentId) {
        ToolIds.number("comment id", commentId);
        run(null, Access.WRITE, (url, token) -> confluenceConnector.deleteComment(url, token, commentId));
    }

    void moveConfluencePage(String pageId, String parentPageId) {
        ToolIds.number("page id", pageId);
        ToolIds.number("parent page id", parentPageId);
        run(null, Access.WRITE, (url, token) -> confluenceConnector.movePage(url, token, pageId, parentPageId));
    }

    List<String> updateConfluenceLabels(String pageId, List<String> add, List<String> remove) {
        ToolIds.number("page id", pageId);
        return on(null, Access.WRITE, (url, token) -> confluenceConnector.updateLabels(url, token, pageId, add, remove));
    }

    Map<String, Object> attachConfluenceFile(String pageId, java.nio.file.Path file, String comment)
            throws java.io.IOException {
        ToolIds.number("page id", pageId);
        byte[] content = java.nio.file.Files.readAllBytes(file);
        return on(null, Access.WRITE, (url, token) -> confluenceConnector.attachFile(url, token, pageId,
                file.getFileName().toString(), content, comment));
    }

    void deleteConfluencePage(String pageId) {
        ToolIds.number("page id", pageId);
        run(null, Access.WRITE, (url, token) -> confluenceConnector.deletePage(url, token, pageId));
    }

    // A Confluence SOURCE is a space, and that is what registration stores as the connector's name -
    // so a space key decides which connection a call uses (see ConnectorEndpointResolver).
    //
    // ONLY createConfluencePage() has one. Every other call here identifies its target by PAGE ID,
    // and a page id does not reveal its space - or its server - without fetching the page first.
    // Those calls therefore use the conversation's useServer pick, or else the caller's default
    // connection. Search resolves the same way. With two Confluence connections a page id taken
    // from the other server would reach whatever page has that id on this one, which is why every
    // page tool's description tells the model to switch with useServer first.
    //
    // ONE RESOLUTION PER CALL - see JiraToolService.on.
    private <T> T on(String spaceKey, Access access, BiFunction<String, String, T> call) {
        ToolConnectorResolver.Target target = resolver.target(ConnectorType.CONFLUENCE, LABEL, spaceKey, access);
        return call.apply(apiBase(target), target.credential());
    }

    private void run(String spaceKey, Access access, BiConsumer<String, String> call) {
        ToolConnectorResolver.Target target = resolver.target(ConnectorType.CONFLUENCE, LABEL, spaceKey, access);
        call.accept(apiBase(target), target.credential());
    }

    /**
     * What the connector's paths ({@code /rest/api/...}) are joined to: a Data Center server's root,
     * cut before any page the address was pasted from and keeping the path it is installed under;
     * on an Atlassian Cloud site, the site's {@code /wiki}, where Confluence lives - typed with it or
     * not, and a Cloud connection made as Jira (which serves Confluence too) never is. The pages'
     * web addresses the connector builds from it land under /wiki as well, as Cloud serves them.
     */
    static String apiBase(ToolConnectorResolver.Target target) {
        String base = target.baseUrl().replaceAll("/+$", "");
        if (!target.atlassianCloud()) return AtlassianServer.confluenceRoot(base);
        return AtlassianCloud.site(base) + "/wiki";
    }

    Map<String, Object> callConfluenceApi(String method, String path, String body) {
        // Only GET is a read - the one rule HttpVerb holds.
        return on(null, HttpVerb.isRead(method) ? Access.READ : Access.WRITE,
                (url, token) -> confluenceConnector.callApi(url, token, method, path, body));
    }
}
