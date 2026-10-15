package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.ConfigJson;
import ai.proxyma.core.connector.files.FileSourceMode;
import ai.proxyma.core.graph.service.KnowledgeGraphService;
import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.indexstore.VectorStoreService;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.repository.RegisteredConnectorRepository;
import ai.proxyma.core.registry.repository.TrackedDocumentRepository;
import ai.proxyma.core.settings.connection.ConnectorConnectionService;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.system.util.AppPaths;
import ai.proxyma.core.telemetryclient.TelemetryShipper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A website source's config, written and read.
 *
 * <p>When it was newline key=value lines, a value with a line break in it wrote keys of its own -
 * the LAST occurrence wins - so an include pattern of ".*" + LF + "maxPages=20000" could set any bound
 * it liked. An include pattern that did not compile was stored and read as "no filter". It is JSON
 * now, and a row in the old lines still reads. The crawl bounds stored on a row are capped again
 * when a session opens - by the product's own ceiling, the same on every tier since 2026-10-14 -
 * which WebCrawlConfigStateTest pins beside the crawler.
 */
class WebPageConfigTest {

    private RegisteredConnectorRepository connectors;
    private FeatureGateService gate;
    private SyncOrchestratorService sync;
    private ConnectorService service;

    @BeforeEach
    void setUp() {
        connectors = mock(RegisteredConnectorRepository.class);
        gate = mock(FeatureGateService.class);
        sync = mock(SyncOrchestratorService.class);
        when(sync.verifyAccess(any())).thenReturn(AccessCheck.verified());
        service = new ConnectorService(connectors, mock(TrackedDocumentRepository.class), sync,
                mock(VectorStoreService.class), mock(BM25IndexService.class), mock(KnowledgeGraphService.class),
                mock(ApplicationEventPublisher.class), mock(AppPaths.class), mock(SettingsService.class),
                mock(ConnectorEndpointResolver.class), mock(ConnectorConnectionService.class), null, gate,
                mock(RestClient.Builder.class), mock(TelemetryShipper.class), mock(FileSourceMode.class));
    }

    @Test
    @DisplayName("an include pattern carrying a line break, or one that does not compile, is refused")
    void badIncludePatternsAreRefused() {
        IllegalArgumentException injected = assertThrows(IllegalArgumentException.class, () -> service.registerWebPage(
                "https://docs.example.test", "Docs", null, null, null, null, ".*\nmaxPages=20000\nmaxDepth=50", "ana"));
        assertThat(injected.getMessage()).contains("one line");

        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class, () -> service.registerWebPage(
                "https://docs.example.test", "Docs", null, null, null, null, "/docs/(v2|v3", "ana"));
        assertThat(invalid.getMessage()).contains("not a valid regular expression");
        verify(connectors, never()).save(any());
    }

    @Test
    @DisplayName("an edit stores what each value means, never the text it arrived as")
    void editsStoreParsedValues() {
        RegisteredConnector site = new RegisteredConnector();
        site.setId(UUID.randomUUID());
        site.setUserId("ana");
        site.setConnectorType(ConnectorType.WEB_PAGE);
        site.setConfig("seedUrl=https://docs.example.test\nmaxDepth=2\nmaxPages=50\nsameDomainOnly=true\nrenderJs=false");
        when(connectors.findById(site.getId())).thenReturn(Optional.of(site));

        service.updateConnectorConfig(site.getId(),
                Map.of("sameDomainOnly", "true\nmaxPages=20000", "renderJs", "false\nseedUrl=https://other.test"), "ana");

        assertThat(site.getConfig()).doesNotContain("20000").doesNotContain("other.test");
        assertThat(ConfigJson.parse(site.getConfig())).containsEntry("maxPages", "50")
                .containsEntry("seedUrl", "https://docs.example.test");
        assertThrows(IllegalArgumentException.class, () -> service.updateConnectorConfig(site.getId(),
                Map.of("includePattern", "["), "ana"));
    }
}
