package ai.proxyma.core.registry.files.upload.service;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.repository.RegisteredConnectorRepository;
import ai.proxyma.core.registry.service.ConnectorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A file added to a source is indexed whatever its file-type filter said (2026-10-10): a CSV uploaded
 * into a source filtered to markdown was stored and skipped by every sync. The type is added to the
 * filter of the source reading that folder - and only a type Proxyma knows, only where a filter
 * exists, only for the uploader's own source.
 */
class UploadFilterWidenerTest {

    private static final String USER = "user-1";

    @TempDir Path root;
    private RegisteredConnectorRepository connectors;
    private ConnectorService connectorService;
    private StorageService storage;
    private UploadFilterWidener widener;

    @BeforeEach
    void setUp() {
        connectors = mock(RegisteredConnectorRepository.class);
        connectorService = mock(ConnectorService.class);
        storage = mock(StorageService.class);
        when(storage.ensureFolder(any(), any())).thenAnswer(inv ->
                root.resolve(String.valueOf((Object) inv.getArgument(0))).resolve((String) inv.getArgument(1)).toString());
        widener = new UploadFilterWidener(connectors, connectorService, storage);
    }

    private RegisteredConnector source(String owner, String folder, String fileTypes) {
        RegisteredConnector c = new RegisteredConnector();
        c.setId(UUID.randomUUID());
        c.setConnectorType(ConnectorType.UPLOADED_FILES);
        c.setUserId(owner);
        c.setName(folder);
        c.setConfig(root.resolve(String.valueOf(owner)).resolve(folder).toString());
        c.setFileTypes(fileTypes);
        return c;
    }

    @Test
    @DisplayName("a CSV uploaded into a markdown-only source adds csv to its filter")
    void addsTheMissingType() {
        RegisteredConnector brief = source(USER, "Quellmoor account brief", "md");
        when(connectors.findByUserIdAndConnectorType(USER, ConnectorType.UPLOADED_FILES)).thenReturn(List.of(brief));

        assertThat(widener.widenFor(USER, "Quellmoor account brief", "quellmoor-revenue-by-month.csv")).contains("csv");

        verify(connectorService).updateConnectorConfig(brief.getId(), Map.of("fileTypes", "md,csv"), USER);
    }

    @Test
    @DisplayName("a type already in the filter, or a source with no filter, changes nothing")
    void nothingToAdd() {
        RegisteredConnector filtered = source(USER, "Docs", "md,csv");
        RegisteredConnector everything = source(USER, "All", null);
        when(connectors.findByUserIdAndConnectorType(USER, ConnectorType.UPLOADED_FILES)).thenReturn(List.of(filtered, everything));

        assertThat(widener.widenFor(USER, "Docs", "sheet.CSV")).isEmpty();
        assertThat(widener.widenFor(USER, "All", "sheet.xlsx")).isEmpty();

        verify(connectorService, never()).updateConnectorConfig(any(), anyMap(), any());
    }

    @Test
    @DisplayName("an extension Proxyma does not know is never switched on by being uploaded")
    void unknownTypeIsNotAdded() {
        RegisteredConnector brief = source(USER, "Docs", "md");
        when(connectors.findByUserIdAndConnectorType(USER, ConnectorType.UPLOADED_FILES)).thenReturn(List.of(brief));

        assertThat(widener.widenFor(USER, "Docs", "setup.exe")).isEmpty();
        assertThat(widener.widenFor(USER, "Docs", "README")).isEmpty();

        verify(connectorService, never()).updateConnectorConfig(any(), anyMap(), any());
    }

    @Test
    @DisplayName("only the source reading THAT folder is widened")
    void onlyTheFoldersSource() {
        RegisteredConnector other = source(USER, "Other folder", "md");
        when(connectors.findByUserIdAndConnectorType(USER, ConnectorType.UPLOADED_FILES)).thenReturn(List.of(other));

        assertThat(widener.widenFor(USER, "Docs", "sheet.csv")).isEmpty();

        verify(connectorService, never()).updateConnectorConfig(any(), anyMap(), any());
    }

    @Test
    @DisplayName("on a single-user install the source with no owner is the one widened")
    void singleUser() {
        RegisteredConnector local = source(null, "Docs", "md");
        RegisteredConnector someoneElses = source("someone", "Docs", "md");
        when(connectors.findByConnectorType(ConnectorType.UPLOADED_FILES)).thenReturn(List.of(local, someoneElses));

        assertThat(widener.widenFor(null, "Docs", "sheet.xlsx")).contains("xlsx");

        verify(connectorService).updateConnectorConfig(eq(local.getId()), eq(Map.of("fileTypes", "md,xlsx")), eq(null));
        verify(connectorService, never()).updateConnectorConfig(eq(someoneElses.getId()), anyMap(), any());
    }

    @Test
    @DisplayName("an upload with no folder belongs to no source")
    void noFolder() {
        assertThat(widener.widenFor(USER, null, "sheet.csv")).isEmpty();
        assertThat(widener.widenFor(USER, " ", "sheet.csv")).isEmpty();
        verify(connectorService, never()).updateConnectorConfig(any(), anyMap(), any());
    }
}
