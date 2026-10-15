package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.files.FileSourceMode;
import ai.proxyma.core.graph.service.KnowledgeGraphService;
import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.indexstore.VectorStoreService;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.repository.RegisteredConnectorRepository;
import ai.proxyma.core.registry.repository.TrackedDocumentRepository;
import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.settings.connection.ConnectorConnectionService;
import ai.proxyma.core.settings.connection.ResolvedConnection;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.system.util.AppPaths;
import ai.proxyma.core.telemetryclient.TelemetryShipper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Two registrations of one source at the same moment make ONE source.
 *
 * <p>The 0.27.0 feedback: the Sources page listed the same Jira project twice, one indexing and one
 * stuck at zero items. The "already registered?" lookup ran unlocked, and the access probe between
 * it and the save reaches the remote server - so two overlapping registrations (parallel tool
 * calls, a background sub-agent beside the main agent, the agent and a dialog at once) both found
 * nothing and both saved. Here the probe holds the first registration open long enough for the
 * second to arrive; without the lock both reach it and two rows are saved.
 */
class RegistrationRaceTest {

    private static final String ANA = "ana";
    private static final ResolvedConnection ANA_JIRA = new ResolvedConnection(UUID.randomUUID(), ANA, "Work",
            ConnectionProtocol.JIRA, "https://jira.work.example", null, "pat", true, true, false, null, null, null);
    private static final ResolvedConnection ANA_SVN = new ResolvedConnection(UUID.randomUUID(), ANA, "Repo",
            ConnectionProtocol.SVN, "https://svn.example/svn", "ana", "secret", true, true, false, null, null, null);

    // A repository that remembers what was saved, so a later lookup finds an earlier save.
    private final List<RegisteredConnector> rows = new CopyOnWriteArrayList<>();
    // How many registrations were inside the probe at once - more than one is the race.
    private final CountDownLatch secondInProbe = new CountDownLatch(2);
    private ConnectorService service;

    // Folders are registered by an absolute path, which a temporary directory is on every platform.
    @TempDir
    Path work;

    @BeforeEach
    void setUp() {
        RegisteredConnectorRepository connectors = mock(RegisteredConnectorRepository.class);
        when(connectors.save(any())).thenAnswer(a -> {
            RegisteredConnector r = a.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            if (!rows.contains(r)) rows.add(r);
            return r;
        });
        when(connectors.findAllByUserIdAndConnectorTypeAndNameIgnoreCase(any(), any(), any()))
                .thenAnswer(a -> matching(a.getArgument(0), a.getArgument(1), a.getArgument(2)));
        when(connectors.findByUserIdAndConnectorTypeAndNameIgnoreCase(any(), any(), any()))
                .thenAnswer(a -> matching(a.getArgument(0), a.getArgument(1), a.getArgument(2)).stream().findFirst());
        when(connectors.findById(any())).thenAnswer(a -> rows.stream()
                .filter(r -> r.getId().equals(a.getArgument(0))).findFirst());
        when(connectors.findByUserIdAndConnectorType(any(), any())).thenAnswer(a -> rows.stream()
                .filter(r -> a.getArgument(0).equals(r.getUserId()) && r.getConnectorType() == a.getArgument(1))
                .toList());

        ConnectorConnectionService connections = mock(ConnectorConnectionService.class);
        for (ResolvedConnection c : List.of(ANA_JIRA, ANA_SVN)) {
            when(connections.resolve(c.id())).thenReturn(Optional.of(c));
            when(connections.resolveOwned(ANA, c.id())).thenReturn(Optional.of(c));
        }
        when(connections.defaultFor(ANA, ConnectionProtocol.JIRA)).thenReturn(Optional.of(ANA_JIRA));
        when(connections.defaultFor(ANA, ConnectionProtocol.SVN)).thenReturn(Optional.of(ANA_SVN));
        ConnectorEndpointResolver resolver = new ConnectorEndpointResolver(connectors, connections);

        SyncOrchestratorService sync = mock(SyncOrchestratorService.class);
        // The probe is where a registration waits on the remote server. Each one waits here until a
        // second registration has also arrived, or a short while passes - with the lock, the second
        // cannot arrive until the first has saved, so the first simply times out and goes on.
        when(sync.verifyAccess(any())).thenAnswer(a -> {
            secondInProbe.countDown();
            secondInProbe.await(400, TimeUnit.MILLISECONDS);
            return AccessCheck.verified();
        });

        service = new ConnectorService(connectors, mock(TrackedDocumentRepository.class), sync,
                mock(VectorStoreService.class), mock(BM25IndexService.class), mock(KnowledgeGraphService.class),
                mock(ApplicationEventPublisher.class), mock(AppPaths.class), mock(SettingsService.class),
                resolver, connections, null, mock(FeatureGateService.class),
                mock(RestClient.Builder.class), mock(TelemetryShipper.class), mock(FileSourceMode.class));
    }

    private List<RegisteredConnector> matching(String userId, ConnectorType type, String name) {
        return rows.stream()
                .filter(r -> userId.equals(r.getUserId()) && r.getConnectorType() == type
                        && r.getName().equalsIgnoreCase(name))
                .toList();
    }

    private void bothAtOnce(Supplier<UUID> first, Supplier<UUID> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<UUID> a = pool.submit(first::get);
            Future<UUID> b = pool.submit(second::get);
            assertEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS),
                    "both registrations answer with the same source");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a Jira project registered twice at once is one source, and the second probe never overlaps the first")
    void jiraProjectOnce() throws Exception {
        bothAtOnce(() -> service.registerJiraProject("RH", ANA), () -> service.registerJiraProject("rh", ANA));

        assertEquals(1, rows.size());
        assertFalse(secondInProbe.getCount() == 0, "the second registration reached the probe while the first was in it");
    }

    @Test
    @DisplayName("the same for an SVN repository, whose own name check runs before registerConnector")
    void svnRepositoryOnce() throws Exception {
        bothAtOnce(() -> service.registerSvnRepo("projects/app", "app", ANA),
                () -> service.registerSvnRepo("projects/app", "app", ANA));

        assertEquals(1, rows.size());
    }

    @Test
    @DisplayName("a folder the agent registers by its path is the folder the dialog registered by its name")
    void folderFromTheAgentAndTheDialogIsOneSource() {
        // Found reviewing 0.27.1: the agent named the source by its whole path and the dialog by
        // the folder's name, so neither found the other - not even one after the other.
        String specs = work.resolve("specs").toString();
        UUID fromDialog = service.registerFiles("specs", specs, ANA, false, null);
        UUID fromAgent = service.registerFiles(specs, ANA);

        assertEquals(fromDialog, fromAgent);
        assertEquals(1, rows.size());
        assertEquals("specs", rows.get(0).getName());
    }

    /**
     * A local folder is identified by its path. By its name, D:/B/docs was "already registered" as
     * C:/A/docs - refused from the dialog, and silently the other folder when the agent asked.
     */
    @Test
    @DisplayName("two folders with the same name are two sources, the second named apart")
    void sameNamedFoldersAreTwoSources() {
        UUID first = service.registerFiles(work.resolve("a").resolve("docs").toString(), ANA);
        UUID second = service.registerFiles(work.resolve("b").resolve("docs").toString(), ANA);

        assertNotEquals(first, second);
        assertEquals(2, rows.size());
        assertEquals("docs", rows.get(0).getName());
        assertEquals("docs (2)", rows.get(1).getName());
        // The same folder again, spelled differently, is still the first source.
        assertEquals(first, service.registerFiles(work.resolve("a").resolve(".").resolve("docs").toString(), ANA));
    }

    @Test
    @DisplayName("a relative or missing folder path is refused with a sentence")
    void relativeFolderPathIsRefused() {
        IllegalArgumentException relative = assertThrows(IllegalArgumentException.class,
                () -> service.registerFiles("docs", ANA));
        assertTrue(relative.getMessage().contains("full path"), relative.getMessage());
        assertThrows(IllegalArgumentException.class, () -> service.registerFiles(null, ANA));
        assertEquals(0, rows.size());
    }

    @Test
    @DisplayName("two different projects are not held behind each other's lock")
    void differentProjectsAreTwoSources() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            long start = System.nanoTime();
            Future<UUID> a = pool.submit(() -> service.registerJiraProject("RH", ANA));
            Future<UUID> b = pool.submit(() -> service.registerJiraProject("OPS", ANA));
            a.get(10, TimeUnit.SECONDS);
            b.get(10, TimeUnit.SECONDS);
            // Both were in the probe together, so neither waited out the other's 400ms.
            assertEquals(0, secondInProbe.getCount());
            assertEquals(2, rows.size());
            assertFalse(System.nanoTime() - start > TimeUnit.MILLISECONDS.toNanos(800),
                    "the two registrations ran one after the other");
        } finally {
            pool.shutdownNow();
        }
    }
}
