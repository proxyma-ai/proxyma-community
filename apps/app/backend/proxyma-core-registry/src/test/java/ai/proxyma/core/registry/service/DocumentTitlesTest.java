package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.files.FileSourceMode;
import ai.proxyma.core.graph.service.KnowledgeGraphService;
import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.indexstore.ChunkMetadata;
import ai.proxyma.core.indexstore.VectorStoreService;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.model.TrackedDocument;
import ai.proxyma.core.registry.repository.RegisteredConnectorRepository;
import ai.proxyma.core.registry.repository.TrackedDocumentRepository;
import ai.proxyma.core.settings.connection.ConnectorConnectionService;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.system.util.AppPaths;
import ai.proxyma.core.telemetryclient.TelemetryShipper;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link ConnectorService#resolveDocumentTitles}, run against H2 with the repositories that ship:
 * a search hit's title is its DOCUMENT's - source and sourceId - and never the first visible row
 * that happens to share the sourceId.
 *
 * <p>The SaaS connectors' item ids are short ({@code ticket:1}), so two sources holding the same
 * one is ordinary: a Freshdesk result for {@code ticket:1} came back under the Zendesk ticket's
 * title. And the lookup stays scoped to what the caller can see, so a source somebody else owns
 * lends no title, whatever a hit claims.
 */
class DocumentTitlesTest {

    private SessionFactory sessions;
    private EntityManager em;
    private RegisteredConnectorRepository connectors;
    private TrackedDocumentRepository documents;
    private ConnectorService service;

    @BeforeEach
    void setUp() {
        sessions = new Configuration()
                .addAnnotatedClass(RegisteredConnector.class)
                .addAnnotatedClass(TrackedDocument.class)
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:doc-titles-" + UUID.randomUUID())
                .setProperty("hibernate.connection.username", "sa")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .buildSessionFactory();
        em = sessions.createEntityManager();
        em.getTransaction().begin();
        JpaRepositoryFactory factory = new JpaRepositoryFactory(em);
        connectors = factory.getRepository(RegisteredConnectorRepository.class);
        documents = factory.getRepository(TrackedDocumentRepository.class);
        service = new ConnectorService(connectors, documents, mock(SyncOrchestratorService.class),
                mock(VectorStoreService.class), mock(BM25IndexService.class), mock(KnowledgeGraphService.class),
                mock(ApplicationEventPublisher.class), mock(AppPaths.class), mock(SettingsService.class),
                mock(ConnectorEndpointResolver.class), mock(ConnectorConnectionService.class), null,
                mock(FeatureGateService.class), mock(RestClient.Builder.class), mock(TelemetryShipper.class),
                mock(FileSourceMode.class));
        // Enterprise Server: a source is its owner's unless shared.
        ReflectionTestUtils.setField(service, "multiUser", true);
    }

    @AfterEach
    void tearDown() {
        if (em.getTransaction().isActive()) em.getTransaction().rollback();
        em.close();
        sessions.close();
    }

    private UUID source(ConnectorType type, String name, String owner) {
        RegisteredConnector c = new RegisteredConnector();
        c.setConnectorType(type);
        c.setName(name);
        c.setUserId(owner);
        c.setStatus(RegisteredConnector.Status.ACTIVE);
        return connectors.save(c).getId();
    }

    private void document(UUID source, String sourceId, String title, TrackedDocument.Status status) {
        TrackedDocument d = new TrackedDocument();
        d.setConnectorId(source);
        d.setSourceId(sourceId);
        d.setTitle(title);
        d.setConnectorType(ConnectorType.UPLOADED_FILES);
        d.setStatus(status);
        d.setSourceLastModified(Instant.now());
        documents.save(d);
    }

    private static Document hit(String collection, String sourceId) {
        return Document.builder().id(UUID.randomUUID().toString()).text("x")
                .metadata(Map.of("source", sourceId, ChunkMetadata.COLLECTION, collection)).build();
    }

    @Test
    @DisplayName("two sources holding one item id: each document gets its own title")
    void eachDocumentItsOwnTitle() {
        UUID zendesk = source(ConnectorType.UPLOADED_FILES, "Support", "ana");
        UUID freshdesk = source(ConnectorType.UPLOADED_FILES, "Helpdesk", "ana");
        document(zendesk, "ticket:1", "Refund request", TrackedDocument.Status.COMPLETED);
        document(freshdesk, "ticket:1", "Printer jam", TrackedDocument.Status.COMPLETED);
        // Only in the Zendesk source: asked for under Freshdesk, it names nothing.
        document(zendesk, "ticket:2", "Login loop", TrackedDocument.Status.COMPLETED);

        DocumentKey z1 = new DocumentKey(zendesk, "ticket:1");
        DocumentKey f1 = new DocumentKey(freshdesk, "ticket:1");
        DocumentKey f2 = new DocumentKey(freshdesk, "ticket:2");
        Map<DocumentKey, String> titles = service.resolveDocumentTitles("ana", List.of(z1, f1, f2));

        assertThat(titles).containsExactlyInAnyOrderEntriesOf(Map.of(z1, "Refund request", f1, "Printer jam"));
    }

    @Test
    @DisplayName("a source the caller cannot see lends no title; shared, it does")
    void anUnseenSourceLendsNothing() {
        UUID mine = source(ConnectorType.UPLOADED_FILES, "Mine", "ana");
        UUID bobs = source(ConnectorType.UPLOADED_FILES, "Bob's", "bob");
        document(mine, "ticket:1", "Refund request", TrackedDocument.Status.COMPLETED);
        document(bobs, "ticket:1", "Bob's salary review", TrackedDocument.Status.COMPLETED);
        DocumentKey bobsTicket = new DocumentKey(bobs, "ticket:1");

        assertThat(service.resolveDocumentTitles("ana", List.of(bobsTicket))).isEmpty();
        assertThat(service.resolveDocumentTitles(null, List.of(bobsTicket))).isEmpty();

        RegisteredConnector shared = connectors.findById(bobs).orElseThrow();
        shared.setShared(true);
        connectors.save(shared);
        assertThat(service.resolveDocumentTitles("ana", List.of(bobsTicket)))
                .containsExactlyEntriesOf(Map.of(bobsTicket, "Bob's salary review"));
    }

    @Test
    @DisplayName("a hit's document is read off the collection it was found in, a reindex shadow's being its base source's")
    void aHitsDocument() {
        UUID source = UUID.randomUUID();
        String live = SourceCollections.of(source);

        assertThat(DocumentKey.of(hit(live, "ticket:1"))).isEqualTo(new DocumentKey(source, "ticket:1"));
        assertThat(DocumentKey.of(hit(SourceCollections.shadowOf(live), "ticket:1")))
                .isEqualTo(new DocumentKey(source, "ticket:1"));
        // Guardrail policies, agent memory: collections of no source.
        assertThat(DocumentKey.of(hit("guardrailpolicy", "rule.md"))).isNull();
        assertThat(DocumentKey.of(Document.builder().id("x").text("x").metadata(Map.of("source", "a.md")).build()))
                .isNull();
    }

    @Test
    @DisplayName("a deleted document is not named")
    void deletedIsNotNamed() {
        UUID mine = source(ConnectorType.UPLOADED_FILES, "Mine", "ana");
        document(mine, "ticket:1", "Gone", TrackedDocument.Status.DELETED);

        assertThat(service.resolveDocumentTitles("ana", List.of(new DocumentKey(mine, "ticket:1")))).isEmpty();
    }
}
