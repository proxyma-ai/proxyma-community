package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.ConnectorPlugin;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.SourceLabel;
import ai.proxyma.core.connector.spi.StoredConfig;
import ai.proxyma.core.graph.service.KnowledgeGraphService;
import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.indexstore.VectorStoreService;
import ai.proxyma.core.ingest.service.EmbeddingService;
import ai.proxyma.core.registry.event.ConnectorSyncedEvent;
import ai.proxyma.core.registry.files.upload.repository.FileMetadataRepository;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.repository.RegisteredConnectorRepository;
import ai.proxyma.core.registry.repository.TrackedDocumentRepository;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.system.util.AppPaths;
import ai.proxyma.core.telemetryclient.TelemetryShipper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A source named after what the service calls it (0.40.2 feedback: a YouTube channel listed as
 * "YouTube channel", linking to YouTube Studio). The session's label names the source at
 * registration and after every pass - only for a type whose name is a label - and keeps its page in
 * the config for the plugin's link; a label that says nothing, or a session that throws, changes
 * nothing and fails nothing.
 */
class SourceLabelTest {

    private static final String CONFIG = "{\"maxVideos\":\"200\",\"maxThreads\":\"500\"}";
    private static final String PAGE = "https://www.youtube.com/@camamtieudao";

    private RegisteredConnectorRepository connectors;
    private ApplicationEventPublisher events;
    private SyncService sync;
    private RegisteredConnector channel;
    private ConnectorPlugin labelled;

    @BeforeEach
    void setUp() {
        connectors = mock(RegisteredConnectorRepository.class);
        events = mock(ApplicationEventPublisher.class);
        sync = new SyncService(mock(TrackedDocumentRepository.class), connectors, null, mock(EmbeddingService.class),
                mock(KnowledgeGraphService.class), events, mock(AppPaths.class), mock(SettingsService.class), null,
                mock(VectorStoreService.class), mock(BM25IndexService.class), mock(FileMetadataRepository.class),
                null, mock(TelemetryShipper.class));
        channel = new RegisteredConnector();
        channel.setId(UUID.randomUUID());
        channel.setConnectorType(ConnectorType.YOUTUBE_CHANNEL);
        channel.setName("YouTube channel");
        channel.setConfig(CONFIG);
        when(connectors.findById(channel.getId())).thenReturn(Optional.of(channel));
        when(connectors.updateLabel(any(), anyString(), any(), anyString())).thenReturn(1);
        labelled = mock(ConnectorPlugin.class);
        when(labelled.identifiedByConfig()).thenReturn(true);
    }

    private static ConnectorSession saying(SourceLabel label) {
        ConnectorSession session = mock(ConnectorSession.class);
        when(session.label()).thenReturn(label);
        return session;
    }

    @Test
    @DisplayName("after a pass the source takes the channel's title, and its page is kept beside its fields")
    void passNamesTheSource() {
        sync.applyLabel(channel.getId(), labelled, saying(new SourceLabel("  Cam Am\nTieu Dao ", PAGE)));

        verify(connectors).updateLabel(eq(channel.getId()), eq("Cam Am Tieu Dao"),
                eq("{\"maxVideos\":\"200\",\"maxThreads\":\"500\",\"webUrl\":\"" + PAGE + "\"}"), eq(CONFIG));
        verify(events).publishEvent(any(ConnectorSyncedEvent.class));
    }

    @Test
    @DisplayName("a source already under its own name and page is not written again")
    void unchangedIsNotWritten() {
        channel.setName("Cam Am Tieu Dao");
        channel.setConfig("{\"maxVideos\":\"200\",\"maxThreads\":\"500\",\"webUrl\":\"" + PAGE + "\"}");

        sync.applyLabel(channel.getId(), labelled, saying(new SourceLabel("Cam Am Tieu Dao", PAGE)));

        verify(connectors, never()).updateLabel(any(), any(), any(), any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("a type whose name is its identity is never renamed, and nothing it says fails the pass")
    void neverRenamesAnIdentityOrFails() {
        ConnectorPlugin named = mock(ConnectorPlugin.class); // identifiedByConfig() false: a Jira project, a repository
        sync.applyLabel(channel.getId(), named, saying(new SourceLabel("Something else", PAGE)));
        sync.applyLabel(channel.getId(), labelled, saying(null));
        ConnectorSession throwing = mock(ConnectorSession.class);
        when(throwing.label()).thenThrow(new IllegalStateException("boom"));
        sync.applyLabel(channel.getId(), labelled, throwing);

        verify(connectors, never()).updateLabel(any(), any(), any(), any());
    }

    @Test
    @DisplayName("no title keeps the generic name; an address that is not http(s) is not kept as the page")
    void blankNameAndBadPage() {
        assertThat(SourceLabels.apply(labelled, ConnectorType.YOUTUBE_CHANNEL, "YouTube channel", CONFIG,
                new SourceLabel(" ", "javascript:alert(1)"))).isEmpty();

        var next = SourceLabels.apply(labelled, ConnectorType.YOUTUBE_CHANNEL, "YouTube channel", CONFIG,
                new SourceLabel(null, PAGE)).orElseThrow();
        assertThat(next.name()).isEqualTo("YouTube channel");
        assertThat(StoredConfig.fields(ConnectorType.YOUTUBE_CHANNEL, next.config())).containsEntry(SourceLabel.WEB_URL, PAGE)
                .containsEntry("maxVideos", "200");

        // A registration's candidate is named by the same rule.
        assertThat(SourceLabels.apply(labelled, ConnectorType.YOUTUBE_CHANNEL, "YouTube channel", CONFIG,
                new SourceLabel("x".repeat(400), null)).orElseThrow().name()).hasSize(SourceLabels.MAX_NAME_CHARS);
    }
}
