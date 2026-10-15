package ai.proxyma.core.registry.controller;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.registry.files.upload.repository.FileMetadataRepository;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.model.TrackedDocument;
import ai.proxyma.core.registry.service.ConnectorEndpointResolver;
import ai.proxyma.core.registry.service.ConnectorService;
import ai.proxyma.core.registry.service.SyncOrchestratorService;
import ai.proxyma.core.settings.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A file-system failure names the absolute path on the server - Failures.describeFileSystem puts it
 * in the sentence - and that sentence was sent verbatim as a source's lastSyncError and an item's
 * failureMessage, to its owner and to everyone the source is shared with, while the same responses
 * carefully withhold the path itself. Both are scrubbed where they leave.
 */
class ServerPathsScrubbedTest {

    private static final String PATH_ERROR =
            "/data/users/7f3c1e2a-0000-4000-8000-000000000001/uploads/Docs/a.pdf - it no longer exists";

    private ConnectorService connectors;
    private ConnectorController controller;

    @BeforeEach
    void setUp() {
        connectors = mock(ConnectorService.class);
        ConnectorEndpointResolver endpoints = mock(ConnectorEndpointResolver.class);
        when(endpoints.resolve(any(), any())).thenReturn(ConnectorEndpointResolver.Endpoint.EMPTY);
        controller = new ConnectorController(connectors, mock(SettingsService.class), endpoints,
                mock(SyncOrchestratorService.class), mock(FileMetadataRepository.class));
    }

    @Test
    @DisplayName("a source's error and an item's failure reach the client without the server path")
    void pathsAreScrubbed() {
        RegisteredConnector source = new RegisteredConnector();
        source.setId(UUID.randomUUID());
        source.setConnectorType(ConnectorType.UPLOADED_FILES);
        source.setName("Docs");
        source.setStatus(RegisteredConnector.Status.ERROR);
        source.setLastSyncError(PATH_ERROR);
        when(connectors.listByTypePaged(any(), any(), any(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(new PageImpl<>(List.of(source)));

        TrackedDocument doc = new TrackedDocument();
        doc.setId(UUID.randomUUID());
        doc.setConnectorId(source.getId());
        doc.setConnectorType(ConnectorType.UPLOADED_FILES);
        doc.setSourceId("/data/users/x/uploads/Docs/a.pdf");
        doc.setTitle("a.pdf");
        doc.setStatus(TrackedDocument.Status.FAILED);
        doc.setFailureMessage(PATH_ERROR);
        when(connectors.listInactiveItemsPaged(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(new ConnectorService.ItemPage(List.of(doc), false));
        when(connectors.graphDoneIds(any(), any())).thenReturn(Set.of());

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));
        Map<String, Object> sources = controller.listByType("all", null, 0, 20, false, exchange).block().getBody();
        Map<String, Object> items = controller.listItems(source.getId(), null, null, null, 50, exchange).block().getBody();

        String sourceError = (String) ((List<Map<String, Object>>) sources.get("items")).get(0).get("lastSyncError");
        String itemFailure = (String) ((List<Map<String, Object>>) items.get("items")).get(0).get("failureMessage");
        assertThat(sourceError).doesNotContain("/data/users").contains("a.pdf");
        assertThat(itemFailure).doesNotContain("/data/users").contains("a.pdf");
    }

    /**
     * A folder source's config is its absolute path. Every other type's config is its fields, sent as
     * {@code fields} for the settings dialog - and a folder's must not be, under either name.
     */
    @Test
    @DisplayName("a folder source's path reaches the client neither as config nor as fields")
    void aFolderPathIsNeverSent() {
        RegisteredConnector source = new RegisteredConnector();
        source.setId(UUID.randomUUID());
        source.setConnectorType(ConnectorType.UPLOADED_FILES);
        source.setName("Docs");
        source.setStatus(RegisteredConnector.Status.ACTIVE);
        source.setConfig("/data/users/7f3c1e2a-0000-4000-8000-000000000001/uploads/Docs");
        when(connectors.listByTypePaged(any(), any(), any(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(new PageImpl<>(List.of(source)));

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));
        Map<String, Object> sources = controller.listByType("all", null, 0, 20, false, exchange).block().getBody();

        Map<String, Object> row = ((List<Map<String, Object>>) sources.get("items")).get(0);
        assertThat(row.get("config")).isNull();
        assertThat(row).doesNotContainKey("fields");
        assertThat(row.toString()).doesNotContain("/data/users");
    }
}
