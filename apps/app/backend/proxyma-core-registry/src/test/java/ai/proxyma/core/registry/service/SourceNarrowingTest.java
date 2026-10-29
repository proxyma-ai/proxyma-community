package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.files.FileSourceMode;
import ai.proxyma.core.graph.service.KnowledgeGraphService;
import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.indexstore.VectorStoreService;
import ai.proxyma.core.license.FeatureGateService;
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
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a source narrowing does to "which sources may this caller see" (listVisibleTo and
 * visibleCollectionIds, the answer every tool and search asks for): it intersects, so it can take a
 * source away and never give one - and one that fails to answer leaves nothing visible rather than
 * everything.
 */
class SourceNarrowingTest {

    private final UUID mine = UUID.randomUUID();
    private final UUID alsoMine = UUID.randomUUID();
    private final UUID shared = UUID.randomUUID();
    private final UUID somebodyElses = UUID.randomUUID();
    private ConnectorService service;

    private static RegisteredConnector source(UUID id, String owner) {
        RegisteredConnector r = new RegisteredConnector();
        r.setId(id);
        r.setUserId(owner);
        return r;
    }

    private static String collection(UUID id) {
        return id.toString().replace("-", "");
    }

    @BeforeEach
    void setUp() {
        RegisteredConnectorRepository connectors = mock(RegisteredConnectorRepository.class);
        when(connectors.findByUserId("u-1")).thenReturn(List.of(source(mine, "u-1"), source(alsoMine, "u-1")));
        when(connectors.findBySharedTrue()).thenReturn(List.of(source(shared, "u-9")));
        service = new ConnectorService(connectors, mock(TrackedDocumentRepository.class),
                mock(SyncOrchestratorService.class), mock(VectorStoreService.class), mock(BM25IndexService.class),
                mock(KnowledgeGraphService.class), mock(ApplicationEventPublisher.class), mock(AppPaths.class),
                mock(SettingsService.class), mock(ConnectorEndpointResolver.class),
                mock(ai.proxyma.core.settings.connection.ConnectorConnectionService.class), null,
                mock(FeatureGateService.class), mock(RestClient.Builder.class), mock(TelemetryShipper.class),
                mock(FileSourceMode.class));
    }

    private static List<UUID> ids(List<RegisteredConnector> connectors) {
        return connectors.stream().map(RegisteredConnector::getId).toList();
    }

    @Test
    @DisplayName("with no narrowing, everything the user can see")
    void noNarrowing() {
        assertThat(ids(service.listVisibleTo("u-1"))).containsExactlyInAnyOrder(mine, alsoMine, shared);

        service.setSourceNarrowings(List.of(Optional::empty));
        assertThat(ids(service.listVisibleTo("u-1"))).as("a narrowing with nothing to say")
                .containsExactlyInAnyOrder(mine, alsoMine, shared);
    }

    @Test
    @DisplayName("narrowed to the named sources, and never to one the user cannot see")
    void narrowedNeverWidened() {
        service.setSourceNarrowings(List.of(() -> Optional.of(Set.of(mine, shared, somebodyElses))));

        assertThat(ids(service.listVisibleTo("u-1"))).containsExactlyInAnyOrder(mine, shared);
        assertThat(service.visibleCollectionIds("u-1")).containsExactlyInAnyOrder(collection(mine), collection(shared));
    }

    @Test
    @DisplayName("two narrowings both apply")
    void twoNarrowingsIntersect() {
        service.setSourceNarrowings(List.of(() -> Optional.of(Set.of(mine, alsoMine)),
                () -> Optional.of(Set.of(alsoMine, shared))));

        assertThat(ids(service.listVisibleTo("u-1"))).containsExactly(alsoMine);
    }

    @Test
    @DisplayName("a narrowing that fails leaves nothing visible, not everything")
    void aFailureIsNothing() {
        service.setSourceNarrowings(List.of(() -> {
            throw new IllegalStateException("database down");
        }));

        assertThat(service.listVisibleTo("u-1")).isEmpty();
        assertThat(service.visibleCollectionIds("u-1")).isEmpty();
    }
}
