package ai.proxyma.core.tools;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.ConnectorCatalog;
import ai.proxyma.core.ingest.service.DocumentFetcher;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.service.ConnectorService;
import ai.proxyma.core.registry.service.SourceContextFactory;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.settings.service.McpGuardService;
import ai.proxyma.core.tools.sources.SourceTools;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What removeSource removes, and what it says it did.
 *
 * <p>The failure this pins: ConnectorService.removeConnector returns quietly for an unknown id, so
 * a remove tool answered "removed" for a mistyped id. And the teardown is asynchronous, so the
 * answer must say it started rather than that it is done. A source somebody else shared is visible
 * to the caller but not theirs to remove, and a live read of it is refused - it lends the caller
 * nothing, not even through the agent.
 */
class SourceRemovalTest {

    private static final String ME = "user-1";

    private final ConnectorService connectors = mock(ConnectorService.class);
    private final SourceTools tools = new SourceTools(connectors, ConnectorCatalog.descriptorsOnly(),
            mock(McpGuardService.class), mock(ActiveServers.class), mock(SourceContextFactory.class),
            mock(DocumentFetcher.class), mock(FeatureGateService.class), false);

    @BeforeEach
    void signIn() {
        SearchContext.setUserId(ME);
    }

    @AfterEach
    void signOut() {
        SearchContext.clear();
    }

    private static RegisteredConnector source(ConnectorType type, String owner) {
        RegisteredConnector r = new RegisteredConnector();
        r.setId(UUID.randomUUID());
        r.setConnectorType(type);
        r.setName("PROJ");
        r.setUserId(owner);
        return r;
    }

    @Test
    @DisplayName("an id that is none of the caller's sources removes nothing and says so")
    void unknownId() {
        when(connectors.listVisibleTo(ME)).thenReturn(List.of());

        Map<String, Object> out = tools.removeSource(UUID.randomUUID().toString());

        assertThat(out.get("success")).isEqualTo(false);
        verify(connectors, never()).removeConnector(any(), any());
    }

    @Test
    @DisplayName("something that is not an id removes nothing")
    void notAnId() {
        assertThat(tools.removeSource("PROJ").get("success")).isEqualTo(false);
        verify(connectors, never()).removeConnector(any(), any());
    }

    @Test
    @DisplayName("a source somebody else shared is visible but not the caller's to remove, nor to read live")
    void sharedByAnotherUser() {
        RegisteredConnector shared = source(ConnectorType.JIRA, "user-2");
        when(connectors.listVisibleTo(ME)).thenReturn(List.of(shared));

        assertThat(tools.removeSource(shared.getId().toString()).get("success")).isEqualTo(false);
        verify(connectors, never()).removeConnector(any(), any());
        assertThat(tools.readSourceItem(shared.getId().toString(), "PROJ-1").get("success")).isEqualTo(false);
    }

    @Test
    @DisplayName("the caller's own source of any type is removed, and the answer says removal STARTED")
    void ownSource() {
        RegisteredConnector project = source(ConnectorType.JIRA, ME);
        RegisteredConnector space = source(ConnectorType.CONFLUENCE, ME);
        when(connectors.listVisibleTo(ME)).thenReturn(List.of(project, space));

        Map<String, Object> out = tools.removeSource(project.getId().toString());
        tools.removeSource(space.getId().toString());

        assertThat(out.get("success")).isEqualTo(true);
        assertThat((String) out.get("message")).startsWith("Removal started");
        verify(connectors).removeConnector(project.getId(), ME);
        verify(connectors).removeConnector(space.getId(), ME);
    }
}
