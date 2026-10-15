package ai.proxyma.core.registry.ingest;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.ConfigJson;
import ai.proxyma.core.connector.spi.ConnectorCatalog;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.graph.service.KnowledgeGraphService;
import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.indexstore.VectorStoreService;
import ai.proxyma.core.ingest.service.ChunkingService;
import ai.proxyma.core.ingest.service.DocumentFetcher;
import ai.proxyma.core.ingest.service.EmbeddingService;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.registry.event.DocumentIndexedEvent;
import ai.proxyma.core.registry.files.upload.repository.FileMetadataRepository;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.model.TrackedDocument;
import ai.proxyma.core.registry.repository.IngestTokenRepository;
import ai.proxyma.core.registry.repository.IngestedDocumentRepository;
import ai.proxyma.core.registry.repository.RegisteredConnectorRepository;
import ai.proxyma.core.registry.repository.TrackedDocumentRepository;
import ai.proxyma.core.registry.service.ConnectorEndpointResolver;
import ai.proxyma.core.registry.service.SourceContextFactory;
import ai.proxyma.core.registry.service.SyncOrchestratorService;
import ai.proxyma.core.registry.service.SyncService;
import ai.proxyma.core.settings.connection.UserRoles;
import ai.proxyma.core.settings.dto.StorageSettings;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.system.util.AppPaths;
import ai.proxyma.core.telemetryclient.TelemetryShipper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The custom source's whole path with the parts that ship: the four registry repositories on an H2
 * database, the real sync engine (SyncService, SyncOrchestratorService), the real EmbeddingService
 * and ChunkingService, the real plugin, store, services and controllers. Only what lies beyond the
 * index is a mock - the vector store, the keyword index, the knowledge graph - so a test can see
 * exactly what reached each.
 */
final class IngestHarness implements AutoCloseable {

    @Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = RegisteredConnectorRepository.class)
    static class Jpa {
        @Bean
        DataSource dataSource() {
            DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:ingest-" + UUID.randomUUID()
                    + ";DB_CLOSE_DELAY=-1", "sa", "");
            ds.setDriverClassName("org.h2.Driver");
            return ds;
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
            emf.setDataSource(dataSource);
            emf.setPackagesToScan("ai.proxyma.core.registry.model");
            emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            Properties p = new Properties();
            p.setProperty("hibernate.hbm2ddl.auto", "create-drop");
            emf.setJpaProperties(p);
            return emf;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
            return new JpaTransactionManager(emf);
        }
    }

    final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Jpa.class);
    final RegisteredConnectorRepository sources = context.getBean(RegisteredConnectorRepository.class);
    final TrackedDocumentRepository tracked = context.getBean(TrackedDocumentRepository.class);
    final IngestedDocumentRepository documents = context.getBean(IngestedDocumentRepository.class);
    final IngestTokenRepository tokenRows = context.getBean(IngestTokenRepository.class);

    final VectorStoreService vectors = mock(VectorStoreService.class);
    final BM25IndexService keywords = mock(BM25IndexService.class);
    final KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
    final UserRoles users = mock(UserRoles.class);
    final Path dataDir;
    final IngestStore store;
    final SyncService sync;
    final SyncOrchestratorService orchestrator;
    final IngestService ingest;
    final IngestTokenService tokens;
    final ConnectorCatalog catalog;

    IngestHarness() throws Exception {
        dataDir = Files.createTempDirectory("proxyma-ingest-test-");
        store = new IngestStore(dataDir.resolve("ingest"));
        CustomSourcePlugin plugin = new CustomSourcePlugin(documents, store);
        catalog = ConnectorCatalog.of(List.of(plugin), List.of());

        AppPaths paths = mock(AppPaths.class);
        when(paths.tempDir()).thenReturn(dataDir.resolve("temp"));
        SettingsService settings = mock(SettingsService.class);
        when(settings.getSettings(eq(StorageSettings.PREFIX), eq(StorageSettings.class))).thenReturn(new StorageSettings());
        // What the drain's conversion does to a text: reads it. A file's bytes would go to markitdown.
        DocumentFetcher fetcher = (in, type) -> {
            try (in) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        EmbeddingService embedding = new EmbeddingService(new ChunkingService(), vectors, keywords);
        // Events as Spring would deliver them to the one listener this feature adds.
        IngestService[] listener = new IngestService[1];
        sync = new SyncService(tracked, sources, fetcher, embedding, graph, event -> {
            if (event instanceof DocumentIndexedEvent indexed && listener[0] != null) listener[0].onDocumentIndexed(indexed);
        }, paths, settings, null, vectors, keywords, mock(FileMetadataRepository.class), null,
                mock(TelemetryShipper.class));
        ConnectorEndpointResolver endpoints = mock(ConnectorEndpointResolver.class);
        orchestrator = new SyncOrchestratorService(catalog, new SourceContextFactory(endpoints, catalog,
                ConnectorHttp.direct(), null), sync, sources, tracked, settings, endpoints,
                mock(FeatureGateService.class), null);
        ingest = new IngestService(sources, documents, tokenRows, tracked, store, orchestrator, sync, catalog);
        listener[0] = ingest;
        @SuppressWarnings("unchecked")
        ObjectProvider<UserRoles> roles = mock(ObjectProvider.class);
        when(roles.getIfAvailable()).thenReturn(users);
        when(users.isActive(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        tokens = new IngestTokenService(tokenRows, ingest, roles);
    }

    /** A custom source owned by {@code owner} (null: the single local user). */
    RegisteredConnector source(String name, String owner) {
        RegisteredConnector r = new RegisteredConnector();
        r.setConnectorType(ConnectorType.CUSTOM);
        r.setName(name);
        r.setUserId(owner);
        r.setConfig(ConfigJson.write(Map.of("name", name)));
        // As its first pass leaves it: listed whole, nothing in it.
        r.setAllItemsFetched(true);
        r.setLastSyncedAt(java.time.Instant.now());
        r.setLastFullSyncAt(java.time.Instant.now());
        return sources.save(r);
    }

    TrackedDocument trackedItem(RegisteredConnector source, String externalId) {
        return tracked.findBySourceIdAndConnectorId("doc:" + externalId, source.getId()).orElse(null);
    }

    /** Runs the drain until {@code done} holds - what the two-second scheduler tick does. */
    void drainUntil(BooleanSupplier done) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("the drain did not finish in time");
            orchestrator.drainQueuedConnectors();
            Thread.sleep(50);
        }
    }

    static String collectionOf(RegisteredConnector source) {
        return source.getId().toString().replace("-", "");
    }

    @Override
    public void close() throws Exception {
        context.close();
        try (var walk = Files.walk(dataDir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }
}
