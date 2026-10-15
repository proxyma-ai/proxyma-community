package ai.proxyma.core.graph.service;

import ai.proxyma.core.graph.model.KgAlias;
import ai.proxyma.core.graph.model.KgDocumentState;
import ai.proxyma.core.graph.model.KgEntity;
import ai.proxyma.core.graph.model.KgMention;
import ai.proxyma.core.graph.model.KgRelation;
import ai.proxyma.core.graph.repository.KgAliasRepository;
import ai.proxyma.core.graph.repository.KgDocumentStateRepository;
import ai.proxyma.core.graph.repository.KgEntityRepository;
import ai.proxyma.core.graph.repository.KgMentionRepository;
import ai.proxyma.core.graph.repository.KgRelationRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The graph written and read for real, on H2 - every finding of the 2026-10-13 review that lives in
 * a query or in what a write leaves behind.
 *
 * <p>Not mocks, because most of what went wrong was a WHERE clause: an edge query with no source
 * filter (SEC-51), a unique key that NULLs walk through, a purge over the whole table. The
 * repositories are Spring Data's own, built from the interfaces that ship, so these run the JPQL the
 * product runs. Each step is its own transaction, as each document's write is in the product.
 *
 * <p>Two sources throughout: OPEN, which the asker can read, and PRIVATE, which they cannot.
 */
class GraphStoreTest {

    private static final UUID OPEN = UUID.randomUUID();
    private static final UUID PRIVATE = UUID.randomUUID();

    private SessionFactory sessions;

    /** One transaction's worth of the graph module, wired as Spring wires it. */
    record Graph(KgEntityRepository entities, KgAliasRepository aliases, KgMentionRepository mentions,
                 KgRelationRepository relations, KnowledgeGraphWriter writer, KnowledgeGraphService service) {}

    @BeforeEach
    void setUp() {
        sessions = new Configuration()
                .addAnnotatedClass(KgEntity.class)
                .addAnnotatedClass(KgAlias.class)
                .addAnnotatedClass(KgMention.class)
                .addAnnotatedClass(KgRelation.class)
                .addAnnotatedClass(KgDocumentState.class)
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:graph-" + UUID.randomUUID())
                .setProperty("hibernate.connection.username", "sa")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .buildSessionFactory();
    }

    @AfterEach
    void tearDown() {
        sessions.close();
    }

    private <T> T tx(Function<Graph, T> work) {
        EntityManager em = sessions.createEntityManager();
        try {
            em.getTransaction().begin();
            JpaRepositoryFactory factory = new JpaRepositoryFactory(em);
            KgEntityRepository entities = factory.getRepository(KgEntityRepository.class);
            KgAliasRepository aliases = factory.getRepository(KgAliasRepository.class);
            KgMentionRepository mentions = factory.getRepository(KgMentionRepository.class);
            KgRelationRepository relations = factory.getRepository(KgRelationRepository.class);
            KgDocumentStateRepository states = factory.getRepository(KgDocumentStateRepository.class);
            KnowledgeGraphWriter writer = new KnowledgeGraphWriter(entities, aliases, mentions, relations, states,
                    new EntityResolver(entities, aliases));
            KnowledgeGraphService service = new KnowledgeGraphService(entities, aliases, mentions, relations,
                    mock(GraphExtractionService.class), writer, states);
            T result = work.apply(new Graph(entities, aliases, mentions, relations, writer, service));
            em.getTransaction().commit();
            return result;
        } finally {
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
            em.close();
        }
    }

    private void run(Consumer<Graph> work) {
        tx(g -> { work.accept(g); return null; });
    }

    // -- a tiny extraction DSL ------------------------------------------------------------------------

    private static ExtractedGraph.Entity entity(String name, String type) {
        return new ExtractedGraph.Entity(name, type, null, null);
    }

    private static ExtractedGraph.Entity entity(String name, String type, String description, List<String> aliases) {
        return new ExtractedGraph.Entity(name, type, description, aliases);
    }

    private static ExtractedGraph.Relation relation(String subject, String predicate, String object, String description) {
        return new ExtractedGraph.Relation(subject, predicate, object, description);
    }

    /** A document of one chunk. */
    private UUID document(UUID connector, List<ExtractedGraph.Entity> entities, List<ExtractedGraph.Relation> relations) {
        UUID doc = UUID.randomUUID();
        write(connector, doc, entities, relations);
        return doc;
    }

    private void write(UUID connector, UUID doc, List<ExtractedGraph.Entity> entities,
                       List<ExtractedGraph.Relation> relations) {
        run(g -> g.writer().replaceDocument(connector, doc,
                List.of(new KnowledgeGraphWriter.ChunkGraph("0", new ExtractedGraph(entities, relations)))));
    }

    private GraphNeighbourhood around(List<String> names, int hops, UUID... readable) {
        return tx(g -> g.service().neighbourhood(names, hops, List.of(readable)));
    }

    private static List<String> names(GraphNeighbourhood n) {
        return n.entities().stream().map(GraphNeighbourhood.Entity::name).toList();
    }

    // -- SEC-51 -------------------------------------------------------------------------------------

    @Test
    @DisplayName("SEC-51: an edge stated only in a private source is not returned, though both its ends are visible")
    void privateEdgeIsNotReturned() {
        document(OPEN, List.of(entity("Auth Service", "service"), entity("Billing", "service")), List.of());
        document(PRIVATE, List.of(entity("Auth Service", "service"), entity("Billing", "service")),
                List.of(relation("Auth Service", "depends on", "Billing", "Auth calls Billing for the secret plan.")));

        GraphNeighbourhood open = around(List.of("Auth Service"), 2, OPEN);
        assertTrue(open.edges().isEmpty(), "a private source's edge reached a reader of the open one: " + open.edges());
        assertEquals(List.of("Auth Service"), names(open));

        GraphNeighbourhood both = around(List.of("Auth Service"), 1, OPEN, PRIVATE);
        assertEquals(1, both.edges().size());
        assertEquals("Billing", both.edges().get(0).object());
    }

    @Test
    @DisplayName("a name, type and description coined in a private source are never shown to a reader of another")
    void namesComeFromReadableSources() {
        // The private document is read FIRST, so its spelling is the entity's canonical name - and
        // with the same type, so both documents resolve to ONE entity and only the display decides
        // what the reader is shown. (With a type of its own it was a second entity, and the test
        // could not fail.)
        document(PRIVATE, List.of(entity("AUTH SERVICE", "service", "Being sold to Acme.", null)),
                List.of());
        document(OPEN, List.of(entity("Auth Service", "service", "Signs users in.", null)), List.of());

        GraphNeighbourhood.Entity seen = around(List.of("auth service"), 1, OPEN).entities().get(0);
        assertEquals("Auth Service", seen.name());
        assertEquals("service", seen.type());
        assertEquals("Signs users in.", seen.description());
    }

    @Test
    @DisplayName("an alias is the source's own: one coined privately neither renames nor finds another source's entity")
    void aliasesArePerSource() {
        document(PRIVATE, List.of(entity("Project Falcon", "project", null, List.of("PF"))), List.of());
        document(OPEN, List.of(entity("PF", "")), List.of());

        assertEquals(List.of("PF"), names(around(List.of("PF"), 1, OPEN)));
        assertEquals(2L, (long) tx(g -> g.entities().count()), "the open source's PF was folded into Project Falcon");
        // The private source's own reader still finds Project Falcon by its alias.
        assertTrue(names(around(List.of("PF"), 1, PRIVATE)).contains("Project Falcon"));
    }

    // -- resolution ----------------------------------------------------------------------------------

    @Test
    @DisplayName("an untyped name is one entity however many documents write it; an untyped mention joins the one typed entity")
    void untypedIsUnique() {
        document(OPEN, List.of(entity("Zeta", "")), List.of());
        document(OPEN, List.of(entity("Zeta", null)), List.of());
        assertEquals(1, tx(g -> g.entities().findByNormalizedName("zeta")).size());

        document(OPEN, List.of(entity("Kappa", "Service")), List.of());
        document(OPEN, List.of(entity("Kappa", "")), List.of());
        List<KgEntity> kappa = tx(g -> g.entities().findByNormalizedName("kappa"));
        assertEquals(1, kappa.size(), "an untyped Kappa beside the typed one is the fragmentation this removes");
        assertEquals("service", kappa.get(0).getType());
    }

    @Test
    @DisplayName("a query typed without diacritics finds the name written with them; tone marks keep words apart")
    void vietnameseNames() {
        document(OPEN, List.of(entity("\u0110\u00e0 N\u1eb5ng", "place"), entity("b\u00e1n", ""),
                entity("b\u00e0n", "")), List.of());

        assertEquals(List.of("\u0110\u00e0 N\u1eb5ng"), names(around(List.of("da nang"), 1, OPEN)));
        assertEquals(3L, (long) tx(g -> g.entities().count()));
    }

    @Test
    @DisplayName("a name holding % or _ is matched as written, not as a wildcard")
    void likeIsEscaped() {
        document(OPEN, List.of(entity("a_b test", ""), entity("axb test", "")), List.of());

        assertEquals(List.of("a_b test"), names(around(List.of("a_b"), 1, OPEN)));
    }

    // -- writes and deletes --------------------------------------------------------------------------

    @Test
    @DisplayName("weight is the number of documents stating an edge - a sentence repeated across chunks counts once")
    void weightCountsDocuments() {
        UUID doc = UUID.randomUUID();
        ExtractedGraph stated = new ExtractedGraph(List.of(entity("Ana", "person"), entity("Payments", "team")),
                List.of(relation("Ana", "leads", "Payments", "Ana leads Payments.")));
        // The same sentence in two overlapping chunks of one document.
        run(g -> g.writer().replaceDocument(OPEN, doc, List.of(
                new KnowledgeGraphWriter.ChunkGraph("0", stated), new KnowledgeGraphWriter.ChunkGraph("1", stated))));
        assertEquals(1, around(List.of("Ana"), 1, OPEN).edges().get(0).weight());

        document(OPEN, stated.entities(), stated.relations());
        GraphNeighbourhood.Edge edge = around(List.of("Ana"), 1, OPEN).edges().get(0);
        assertEquals(2, edge.weight());
        assertEquals(2, edge.documentIds().size());
        assertEquals("0", edge.passageChunkRef());
    }

    @Test
    @DisplayName("a re-read replaces the document's graph; the purge takes only what that delete orphaned")
    void purgeIsScopedToWhatWasTouched() {
        // An orphan nothing will touch: no mention at all.
        run(g -> {
            KgEntity stray = new KgEntity();
            stray.setCanonicalName("Stray");
            stray.setNormalizedName("stray");
            stray.setType(GraphNames.UNTYPED);
            g.entities().save(stray);
        });
        UUID doc = document(OPEN, List.of(entity("Old Name", "")), List.of());
        document(OPEN, List.of(entity("Kept", "")), List.of());

        write(OPEN, doc, List.of(entity("New Name", "")), List.of());

        List<String> left = tx(g -> g.entities().findAll().stream().map(KgEntity::getNormalizedName).sorted().toList());
        assertEquals(List.of("kept", "new name", "stray"), left);
    }

    @Test
    @DisplayName("an entity resolved after a purge began is not taken by it")
    void purgeLeavesARecentlyResolvedEntity() {
        UUID doc = document(OPEN, List.of(entity("Busy", "")), List.of());
        UUID busy = tx(g -> g.entities().findByNormalizedName("busy").get(0).getId());
        Instant before = Instant.now().minusSeconds(60);

        // The mention is gone, but the entity was stamped after the purge's start: another document
        // resolved it and has not written its mention yet.
        run(g -> g.mentions().deleteByDocumentId(doc));
        assertEquals(0, (int) tx(g -> g.writer().purgeOrphans(List.of(busy), before)));
        assertEquals(1, (int) tx(g -> g.writer().purgeOrphans(List.of(busy), Instant.now().plusSeconds(1))));
    }

    @Test
    @DisplayName("deleting a source takes its aliases and every entity only it mentioned")
    void deletingASource() {
        document(PRIVATE, List.of(entity("Falcon", "project", null, List.of("FLC"))), List.of());
        document(OPEN, List.of(entity("Shared", "")), List.of());
        document(PRIVATE, List.of(entity("Shared", "")), List.of());

        run(g -> g.writer().deleteConnector(PRIVATE));

        assertEquals(List.of("shared"), tx(g -> g.entities().findAll().stream().map(KgEntity::getNormalizedName).toList()));
        assertEquals(0L, (long) tx(g -> g.aliases().count()));
    }

    // -- retrieval -----------------------------------------------------------------------------------

    @Test
    @DisplayName("two names asked about together come back with the path between them")
    void pathsBetweenNames() {
        document(OPEN, List.of(entity("Ana", "person"), entity("Payments", "team"), entity("Ledger", "service")),
                List.of(relation("Ana", "leads", "Payments", null), relation("Payments", "owns", "Ledger", null)));

        GraphNeighbourhood found = around(List.of("Ana", "Ledger"), 2, OPEN);
        assertEquals(1, found.paths().size());
        assertEquals(List.of("Ana leads Payments", "Payments owns Ledger"), found.paths().get(0).steps().stream()
                .map(s -> s.subject() + " " + s.predicate() + " " + s.object()).toList());
    }

    // -- matching names that are not spelled as asked ---------------------------------------------------

    @Test
    @DisplayName("spacing and punctuation are not a different name, and each spelling brings the others as synonyms")
    void spellingVariantsAndSynonyms() {
        document(OPEN, List.of(entity("auth-service", "service")), List.of());
        document(OPEN, List.of(entity("AuthService", "service")), List.of());

        assertEquals(List.of("AuthService", "auth-service"),
                names(around(List.of("Auth Service"), 1, OPEN)).stream().sorted().toList());
        // Synonyms only - the two stay two entities.
        assertEquals(2L, (long) tx(g -> g.entities().count()));
    }

    @Test
    @DisplayName("an acronym finds the name it abbreviates; a typo finds a long name, never a short one")
    void acronymsAndTypos() {
        document(OPEN, List.of(entity("Single Sign-On", "service"), entity("Kubernetes", "system"),
                entity("Redis", "system")), List.of());

        assertEquals(List.of("Single Sign-On"), names(around(List.of("SSO"), 1, OPEN)));
        assertEquals(List.of("Kubernetes"), names(around(List.of("Kubernets"), 1, OPEN)));
        assertEquals(List.of("Kubernetes"), names(around(List.of("Kuberentes"), 1, OPEN)));
        assertTrue(around(List.of("Rdis"), 1, OPEN).isEmpty(), "four letters is another word, not a typo");
        assertEquals(1, KnowledgeGraphService.editDistance("kubernetes", "kubernets", 3));
    }

    // -- meaning ---------------------------------------------------------------------------------

    @Test
    @DisplayName("with no name matched, the passages relevant to the question say where to start")
    void questionLinksThroughPassages() {
        UUID doc = UUID.randomUUID();
        run(g -> g.writer().replaceDocument(OPEN, doc, List.of(
                new KnowledgeGraphWriter.ChunkGraph("0", new ExtractedGraph(
                        List.of(entity("Ana", "person"), entity("Payments", "team")),
                        List.of(relation("Ana", "leads", "Payments", "Ana leads Payments.")))),
                new KnowledgeGraphWriter.ChunkGraph("1", new ExtractedGraph(
                        List.of(entity("Ledger", "service")), List.of())))));

        GraphNeighbourhood found = tx(g -> g.service().neighbourhood(List.of(), 1, List.of(OPEN),
                List.of(new RelevantPassage(doc, "0", 0.9))));

        assertTrue(names(found).containsAll(List.of("Ana", "Payments")), names(found).toString());
        assertFalse(names(found).contains("Ledger"), "a passage nobody found relevant is not where the question starts");
    }

    @Test
    @DisplayName("the edge drawn from the passage most relevant to the question comes first")
    void relevanceRanksEdges() {
        UUID doc = UUID.randomUUID();
        run(g -> g.writer().replaceDocument(OPEN, doc, List.of(
                new KnowledgeGraphWriter.ChunkGraph("0", new ExtractedGraph(
                        List.of(entity("Ana", "person"), entity("Payments", "team")),
                        List.of(relation("Ana", "leads", "Payments", null)))),
                new KnowledgeGraphWriter.ChunkGraph("1", new ExtractedGraph(
                        List.of(entity("Ana", "person"), entity("Ledger", "service")),
                        List.of(relation("Ana", "maintains", "Ledger", null)))))));

        GraphNeighbourhood plain = around(List.of("Ana"), 1, OPEN);
        GraphNeighbourhood asked = tx(g -> g.service().neighbourhood(List.of("Ana"), 1, List.of(OPEN),
                List.of(new RelevantPassage(doc, "1", 0.8))));

        assertEquals("leads", plain.edges().get(0).predicate());
        assertEquals("maintains", asked.edges().get(0).predicate());
        assertEquals("1", asked.edges().get(0).passageChunkRef());
    }

    @Test
    @DisplayName("an edge from the source's metadata is marked so, and cites no passage")
    void metadataEdges() {
        UUID doc = UUID.randomUUID();
        run(g -> g.writer().replaceDocument(OPEN, doc, List.of(new KnowledgeGraphWriter.ChunkGraph(
                KnowledgeGraphService.METADATA_REF, new ExtractedGraph(
                        List.of(entity("PAY-12", "issue"), entity("Ana Lima", "person")),
                        List.of(relation("PAY-12", "assigned to", "Ana Lima", null)))))));

        GraphNeighbourhood.Edge edge = around(List.of("PAY-12"), 1, OPEN).edges().get(0);
        assertTrue(edge.fromMetadata());
        assertEquals(null, edge.passageChunkRef());
    }

    @Test
    @DisplayName("a metadata refresh replaces only the metadata rows - what a model read stays")
    void metadataRefreshKeepsTheModelsRead() {
        UUID doc = UUID.randomUUID();
        run(g -> g.writer().replaceDocument(OPEN, doc, List.of(
                new KnowledgeGraphWriter.ChunkGraph("0", new ExtractedGraph(
                        List.of(entity("Ana", "person"), entity("Payments", "team")),
                        List.of(relation("Ana", "leads", "Payments", null)))),
                new KnowledgeGraphWriter.ChunkGraph(KnowledgeGraphService.METADATA_REF, new ExtractedGraph(
                        List.of(entity("PAY-12", "issue"), entity("Ana", "person")),
                        List.of(relation("PAY-12", "assigned to", "Ana", null)))))));

        run(g -> g.service().refreshMetadata(OPEN, doc, Instant.now(), new ExtractedGraph(
                List.of(entity("PAY-12", "issue"), entity("Bo", "person")),
                List.of(relation("PAY-12", "assigned to", "Bo", null))), 1));

        List<String> statements = tx(g -> g.relations().findAll().stream()
                .map(r -> r.getPredicate() + (KnowledgeGraphService.METADATA_REF.equals(r.getChunkRef()) ? "*" : ""))
                .sorted().toList());
        assertEquals(List.of("assigned to*", "leads"), statements);
        assertEquals(List.of("Bo"), names(around(List.of("PAY-12"), 1, OPEN)).stream().filter(n -> !n.equals("PAY-12")).toList());
        assertEquals(Integer.valueOf(1), tx(g -> g.service().documentStates(OPEN).get(doc).metadataVersion()));
    }

    @Test
    @DisplayName("entities written before the derived name keys existed get them, a batch at a time")
    void derivedKeysAreBackfilled() {
        run(g -> {
            KgEntity old = new KgEntity();
            old.setCanonicalName("Auth Service");
            old.setNormalizedName("auth service");
            old.setType("service");
            g.entities().save(old);
        });
        assertEquals(1, (int) tx(g -> g.service().backfillDerivedKeys(10)));
        assertEquals(0, (int) tx(g -> g.service().backfillDerivedKeys(10)));
        assertEquals("authservice", tx(g -> g.entities().findByNormalizedName("auth service").get(0).getSquashedName()));
    }

    // -- names by meaning, and the search channel ------------------------------------------------

    @Test
    @DisplayName("a name close in meaning matches when nothing else does, and a very close one comes along as a synonym")
    void similarNames() {
        document(OPEN, List.of(entity("Auth Service", "service"), entity("Identity Provider", "service"),
                entity("Billing", "service")), List.of());
        UUID auth = tx(g -> g.entities().findByNormalizedName("auth service").get(0).getId());
        UUID identity = tx(g -> g.entities().findByNormalizedName("identity provider").get(0).getId());

        GraphNeighbourhood byMeaning = tx(g -> g.service().neighbourhood(List.of("authentication"), 1, List.of(OPEN),
                List.of(), Map.of("authentication", List.of(new SimilarEntity(auth, 0.8)))));
        assertEquals(List.of("Auth Service"), names(byMeaning));

        GraphNeighbourhood withSynonym = tx(g -> g.service().neighbourhood(List.of("Auth Service"), 1, List.of(OPEN),
                List.of(), Map.of("Auth Service", List.of(new SimilarEntity(identity, 0.93), new SimilarEntity(auth, 1.0)))));
        assertEquals(List.of("Auth Service", "Identity Provider"), names(withSynonym).stream().sorted().toList());

        GraphNeighbourhood weak = tx(g -> g.service().neighbourhood(List.of("Auth Service"), 1, List.of(OPEN),
                List.of(), Map.of("Auth Service", List.of(new SimilarEntity(identity, 0.8)))));
        assertEquals(List.of("Auth Service"), names(weak), "0.8 matches a term nothing else did - it is not a synonym");
    }

    @Test
    @DisplayName("the search channel ranks the passages about what the query names, through the graph around it")
    void searchChannelRanksPassages() {
        UUID doc = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        run(g -> g.writer().replaceDocument(OPEN, doc, List.of(
                new KnowledgeGraphWriter.ChunkGraph("0", new ExtractedGraph(
                        List.of(entity("Payments", "team"), entity("Ledger", "service")),
                        List.of(relation("Payments", "owns", "Ledger", null)))),
                new KnowledgeGraphWriter.ChunkGraph("1", new ExtractedGraph(
                        List.of(entity("Ledger", "service"), entity("Postgres", "system")),
                        List.of(relation("Ledger", "stores in", "Postgres", null)))))));
        run(g -> g.writer().replaceDocument(OPEN, other, List.of(new KnowledgeGraphWriter.ChunkGraph("0",
                new ExtractedGraph(List.of(entity("Kim", "person"), entity("Search", "team")),
                        List.of(relation("Kim", "leads", "Search", null)))))));

        List<RelevantPassage> ranked = tx(g -> g.service().graphPassages("who owns the ledger?", List.of(), List.of(OPEN), 10));

        assertFalse(ranked.isEmpty());
        assertTrue(ranked.stream().allMatch(p -> p.documentId().equals(doc)), "a passage about nothing the query names ranked");
        assertTrue(tx(g -> g.service().graphPassages("what is the weather", List.of(), List.of(OPEN), 10)).isEmpty());
        assertTrue(tx(g -> g.service().graphPassages("who owns the ledger?", List.of(), List.of(PRIVATE), 10)).isEmpty());
    }

    // -- time ------------------------------------------------------------------------------------

    @Test
    @DisplayName("a former assignee and a status change are not walked, and the timeline dates them all")
    void pastAndEventsAreTimelineOnly() {
        Instant march = Instant.parse("2026-03-01T09:00:00Z");
        Instant april = Instant.parse("2026-04-01T09:00:00Z");
        Instant may = Instant.parse("2026-05-02T09:00:00Z");
        UUID doc = UUID.randomUUID();
        run(g -> g.writer().replaceDocument(OPEN, doc, List.of(new KnowledgeGraphWriter.ChunkGraph(
                KnowledgeGraphService.METADATA_REF, new ExtractedGraph(
                        List.of(entity("PAY-12", "issue"), entity("Ana", "person"), entity("Bo", "person"),
                                entity("Done", "status")),
                        List.of(new ExtractedGraph.Relation("PAY-12", "assigned to", "Ana", null, march, april, null),
                                new ExtractedGraph.Relation("PAY-12", "assigned to", "Bo", null, april, null, null),
                                new ExtractedGraph.Relation("PAY-12", "moved to", "Done", null, null, null, may)))))));

        assertEquals(List.of("Bo"), names(around(List.of("PAY-12"), 1, OPEN)).stream()
                .filter(n -> !n.equals("PAY-12")).toList());
        List<String> history = tx(g -> g.service().timeline(List.of("PAY-12"), List.of(OPEN))).stream()
                .map(e -> e.at().toString().substring(0, 10) + " " + e.predicate() + " " + e.object() + " " + e.change())
                .toList();
        assertEquals(List.of(
                "2026-03-01 assigned to Ana STARTED",
                "2026-04-01 assigned to Ana ENDED",
                "2026-04-01 assigned to Bo STARTED",
                "2026-05-02 moved to Done HAPPENED"), history);
        assertTrue(tx(g -> g.service().timeline(List.of("PAY-12"), List.of(PRIVATE))).isEmpty());
    }

    @Test
    @DisplayName("themes group what the corpus ties together, the largest first")
    void themes() {
        document(OPEN, List.of(entity("Ana", "person"), entity("Payments", "team"), entity("Ledger", "service"),
                entity("Payouts", "service")), List.of(relation("Ana", "leads", "Payments", null),
                relation("Payments", "owns", "Ledger", null), relation("Payments", "owns", "Payouts", null),
                relation("Ledger", "feeds", "Payouts", null)));
        document(OPEN, List.of(entity("Kim", "person"), entity("Search", "team"), entity("Indexer", "service")),
                List.of(relation("Kim", "leads", "Search", null), relation("Search", "owns", "Indexer", null)));

        List<GraphTheme> themes = tx(g -> g.service().themes(List.of(OPEN), List.of(), 5));

        assertEquals(2, themes.size());
        assertEquals(4, themes.get(0).size());
        assertTrue(themes.get(0).entities().containsAll(List.of("Ana", "Payments", "Ledger", "Payouts")));
        assertTrue(themes.get(1).entities().containsAll(List.of("Kim", "Search", "Indexer")));
        assertTrue(tx(g -> g.service().themes(List.of(PRIVATE), List.of(), 5)).isEmpty());
    }

    @Test
    @DisplayName("whether someone has graph data to traverse is asked of the sources they can read")
    void hasDataIsPerReader() {
        document(PRIVATE, List.of(entity("Falcon", "project")), List.of());

        assertTrue((boolean) tx(g -> g.service().hasDataIn(List.of(PRIVATE))));
        assertFalse((boolean) tx(g -> g.service().hasDataIn(List.of(OPEN))));
        assertFalse((boolean) tx(g -> g.service().hasDataIn(List.of())));
    }

    @Test
    @DisplayName("nothing readable, nothing returned - an empty source list is not 'unfiltered'")
    void emptyVisibilityIsEmpty() {
        document(OPEN, List.of(entity("Ana", "person")), List.of());

        assertTrue(tx(g -> g.service().neighbourhood(List.of("Ana"), 1, List.of())).isEmpty());
        assertTrue(around(List.of("Ana"), 1, PRIVATE).isEmpty());
    }

    // -- the overhaul review's findings (2026-10-14) --------------------------------------------------

    @Test
    @DisplayName("graph-01: a hub entity names a few of its documents and says how many there are")
    void hubEntitiesAreBounded() {
        for (int i = 0; i < 20; i++) {
            document(OPEN, List.of(entity("Ana", "person"), entity("PAY-" + i, "issue")),
                    List.of(relation("PAY-" + i, "assigned to", "Ana", null)));
        }

        GraphNeighbourhood.Entity ana = around(List.of("Ana"), 1, OPEN).entities().stream()
                .filter(e -> e.name().equals("Ana")).findFirst().orElseThrow();
        assertEquals(KnowledgeGraphService.DOCUMENTS_PER_ENTITY, ana.documentIds().size());
        assertEquals(20, ana.documentCount());
    }

    @Test
    @DisplayName("graph-03: other readers' matches do not use up a tier's page and hide the asker's own")
    void invisibleMatchesDoNotHideVisibleOnes() {
        for (String name : List.of("Pay", "Payx", "Payroll", "Paypal", "Payment", "Payee")) {
            document(PRIVATE, List.of(entity(name, "thing")), List.of());
        }
        document(OPEN, List.of(entity("Payments Team", "team")), List.of());

        assertEquals(List.of("Payments Team"), names(around(List.of("pay"), 1, OPEN)));
    }

    @Test
    @DisplayName("graph-08: the view draws one edge per triple, as heavy as the documents stating it")
    void viewGroupsEdges() {
        for (int i = 0; i < 3; i++) {
            document(OPEN, List.of(entity("Ana", "person"), entity("Payments", "team")),
                    List.of(relation("Ana", "leads", "Payments", null)));
        }

        GraphView view = tx(g -> g.service().viewForConnector(OPEN));
        assertEquals(1, view.edges().size(), view.edges().toString());
        assertEquals(3, view.edges().get(0).weight());
        assertTrue(view.nodes().stream().allMatch(n -> n.degree() == 1), view.nodes().toString());
        assertTrue(view.nodes().stream().allMatch(n -> n.mentions() == 3), view.nodes().toString());
    }

    @Test
    @DisplayName("graph-09: a hub's past does not crowd out what holds now")
    void currentEdgesAreNotCrowdedOut() {
        // Seven hundred ended spells first - more than one hop reads - then the current one.
        Instant from = Instant.parse("2025-01-01T00:00:00Z");
        List<ExtractedGraph.Entity> entities = new java.util.ArrayList<>(List.of(entity("Ana", "person")));
        List<ExtractedGraph.Relation> past = new java.util.ArrayList<>();
        for (int i = 0; i < 700; i++) {
            entities.add(entity("OLD-" + i, "issue"));
            past.add(new ExtractedGraph.Relation("OLD-" + i, "assigned to", "Ana", null, from, from.plusSeconds(60), null));
        }
        UUID history = UUID.randomUUID();
        run(g -> g.writer().replaceDocument(OPEN, history, List.of(new KnowledgeGraphWriter.ChunkGraph(
                KnowledgeGraphService.METADATA_REF, new ExtractedGraph(entities, past)))));
        document(OPEN, List.of(entity("Ana", "person"), entity("NOW-1", "issue")),
                List.of(relation("NOW-1", "assigned to", "Ana", null)));

        GraphNeighbourhood found = around(List.of("Ana"), 1, OPEN);
        assertTrue(found.edges().stream().anyMatch(e -> e.subject().equals("NOW-1")), found.edges().toString());
    }

    @Test
    @DisplayName("graph-07: a direct edge is found however many longer paths come first, for every pair asked about")
    void shortestPathsPerPair() {
        UUID ana = UUID.randomUUID();
        UUID payments = UUID.randomUUID();
        UUID ledger = UUID.randomUUID();
        Map<UUID, List<KgRelation>> adjacent = new java.util.HashMap<>();
        java.util.function.BiConsumer<UUID, UUID> link = (a, b) -> {
            KgRelation r = new KgRelation();
            r.setId(UUID.randomUUID());
            r.setSubjectId(a);
            r.setObjectId(b);
            r.setPredicate("relates to");
            adjacent.computeIfAbsent(a, k -> new java.util.ArrayList<>()).add(r);
            adjacent.computeIfAbsent(b, k -> new java.util.ArrayList<>()).add(r);
        };
        // Fifty two-step paths from Ana to Payments, listed BEFORE the direct edge.
        for (int i = 0; i < 50; i++) {
            UUID issue = UUID.randomUUID();
            link.accept(ana, issue);
            link.accept(issue, payments);
        }
        link.accept(ana, payments);
        link.accept(payments, ledger);

        List<List<KgRelation>> anaToPayments = KnowledgeGraphService.pathsBetween(ana, payments, adjacent);
        assertEquals(1, anaToPayments.get(0).size(), "the direct edge was not found first");
        assertFalse(KnowledgeGraphService.pathsBetween(payments, ledger, adjacent).isEmpty());
        assertFalse(KnowledgeGraphService.pathsBetween(ana, ledger, adjacent).isEmpty());
    }

    @Test
    @DisplayName("graph-19: re-adding a chunk a read already wrote does not mention its entities twice")
    void resumedChunksAreNotDoubled() {
        UUID doc = UUID.randomUUID();
        List<KnowledgeGraphWriter.ChunkGraph> chunk = List.of(new KnowledgeGraphWriter.ChunkGraph("3",
                new ExtractedGraph(List.of(entity("Ana", "person")), List.of())));
        run(g -> g.writer().addChunks(OPEN, doc, chunk));
        run(g -> g.writer().addChunks(OPEN, doc, chunk));

        assertEquals(1L, (long) tx(g -> g.mentions().countByConnectorId(OPEN)));
    }
}
