package ai.proxyma.core.tools.youtube;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.testing.LoopbackApi;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.model.TrackedDocument;
import ai.proxyma.core.registry.repository.RegisteredConnectorRepository;
import ai.proxyma.core.registry.repository.TrackedDocumentRepository;
import ai.proxyma.core.registry.service.SourceContextFactory;
import ai.proxyma.core.settings.connection.ResolvedConnection;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A VIDEO IS NAMED BY ITS TITLE (2026-11-06, the maintainer, on the Questions page with YouTube's daily quota spent since
 * half past midnight: every comment's video read "Open video" - "It must always the video title"). The order a title is
 * found in, against H2 with the repositories that ship and a loopback YouTube Data API: the channel SOURCE's index first,
 * with no call (a live row before a deleted one, "(untitled video)" never), then what YouTube said before, then
 * videos.list for the rest only, fifty a call; a spent quota leaves every title already held in place, and a known title
 * is never dropped by a later failure; another person's source lends nothing.
 */
class YouTubeVideoTitlesTest {

    private SessionFactory sessions;
    private EntityManager em;
    private RegisteredConnectorRepository sources;
    private TrackedDocumentRepository documents;
    private LoopbackApi api;
    private UUID connectionId;
    private ApiClient client;
    private YouTubeVideoTitles titles;

    @BeforeEach
    void setUp() {
        sessions = new Configuration()
                .addAnnotatedClass(RegisteredConnector.class)
                .addAnnotatedClass(TrackedDocument.class)
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:yt-titles-" + UUID.randomUUID())
                .setProperty("hibernate.connection.username", "sa")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .buildSessionFactory();
        em = sessions.createEntityManager();
        em.getTransaction().begin();
        JpaRepositoryFactory factory = new JpaRepositoryFactory(em);
        sources = factory.getRepository(RegisteredConnectorRepository.class);
        documents = factory.getRepository(TrackedDocumentRepository.class);
        titles = new YouTubeVideoTitles(sources, documents);
        api = LoopbackApi.start();
        connectionId = UUID.randomUUID();
        ResolvedConnection connection = new ResolvedConnection(connectionId, "u-1", "My channel", ConnectionProtocol.YOUTUBE,
                api.baseUrl(), null, "ya29.test", true, true, false, null, null, null);
        client = YouTubeTools.client(ConnectorHttp.direct(), new SourceContextFactory(null, null, null, null).credentials(connection), true);
    }

    @AfterEach
    void tearDown() {
        api.close();
        if (em.getTransaction().isActive()) em.getTransaction().rollback();
        em.close();
        sessions.close();
    }

    private UUID source(UUID connection, String owner) {
        RegisteredConnector c = new RegisteredConnector();
        c.setConnectorType(ConnectorType.YOUTUBE_CHANNEL);
        c.setName("Flute lessons " + UUID.randomUUID());
        c.setUserId(owner);
        c.setConnectionId(connection);
        c.setStatus(RegisteredConnector.Status.ACTIVE);
        return sources.save(c).getId();
    }

    private void video(UUID source, String videoId, String title, TrackedDocument.Status status) {
        TrackedDocument d = new TrackedDocument();
        d.setConnectorId(source);
        d.setSourceId("video:" + videoId);
        d.setTitle(title);
        d.setConnectorType(ConnectorType.YOUTUBE_CHANNEL);
        d.setStatus(status);
        d.setSourceLastModified(Instant.now());
        documents.save(d);
    }

    private void quotaSpent() {
        api.on("GET", "/youtube/v3/videos").status(403).json("{'error':{'code':403,'message':'The request cannot be "
                + "completed because you have exceeded your quota.','errors':[{'reason':'quotaExceeded'}]}}");
    }

    @Test
    @DisplayName("the channel source's index first with no call, YouTube only for the rest - fifty a call - and what it said remembered")
    void indexFirstThenYouTube() {
        UUID mine = source(connectionId, "u-1");
        video(mine, "A43oBHyslkQ", "Biet Tri Ky - dizi Bb", TrackedDocument.Status.COMPLETED);
        api.on("GET", "/youtube/v3/videos").json("{'items':[{'id':'NcQxTxoPMsM','snippet':{'title':'Con tieng sao thi sao'}}]}");

        assertThat(titles.held("u-1", connectionId, List.of("A43oBHyslkQ", "NcQxTxoPMsM")))
                .containsExactlyEntriesOf(Map.of("A43oBHyslkQ", "Biet Tri Ky - dizi Bb"));
        assertThat(api.requests()).as("what is held is read with no call").isEmpty();

        int[] calls = {0};
        Map<String, String> found = titles.titles(client, "u-1", connectionId, List.of("A43oBHyslkQ", "NcQxTxoPMsM"), calls);
        assertThat(found).containsEntry("A43oBHyslkQ", "Biet Tri Ky - dizi Bb").containsEntry("NcQxTxoPMsM", "Con tieng sao thi sao");
        assertThat(calls[0]).isEqualTo(1);
        assertThat(api.requests("/youtube/v3/videos")).singleElement()
                .satisfies(r -> assertThat(r.param("id")).as("only the one the index does not hold").isEqualTo("NcQxTxoPMsM"));

        // Asked again: both held, nothing asked.
        titles.titles(client, "u-1", connectionId, List.of("A43oBHyslkQ", "NcQxTxoPMsM"), null);
        assertThat(api.requests("/youtube/v3/videos")).hasSize(1);
        assertThat(titles.held("u-1", connectionId, List.of("NcQxTxoPMsM"))).containsEntry("NcQxTxoPMsM", "Con tieng sao thi sao");
    }

    @Test
    @DisplayName("YouTube's quota spent: every title the index or an earlier answer holds is still given, and a known one is never dropped")
    void quotaSpentKeepsTitles() {
        UUID mine = source(connectionId, "u-1");
        video(mine, "A43oBHyslkQ", "Biet Tri Ky - dizi Bb", TrackedDocument.Status.COMPLETED);
        Instant t = Instant.parse("2026-11-06T00:00:00Z");
        titles.setClock(Clock.fixed(t, ZoneOffset.UTC));
        api.on("GET", "/youtube/v3/videos").json("{'items':[{'id':'NcQxTxoPMsM','snippet':{'title':'Con tieng sao thi sao'}}]}");
        titles.titles(client, "u-1", connectionId, List.of("NcQxTxoPMsM"), null);

        // Past the time a title is asked again, with the quota spent.
        api.reset();
        quotaSpent();
        titles.setClock(Clock.fixed(t.plus(YouTubeVideoTitles.TTL).plusSeconds(60), ZoneOffset.UTC));
        Map<String, String> found = titles.titles(client, "u-1", connectionId,
                List.of("A43oBHyslkQ", "NcQxTxoPMsM", "zzzzzzzzzz1"), null);
        assertThat(found).containsExactlyInAnyOrderEntriesOf(Map.of("A43oBHyslkQ", "Biet Tri Ky - dizi Bb",
                "NcQxTxoPMsM", "Con tieng sao thi sao"));
        assertThat(api.requests("/youtube/v3/videos")).as("the stale one and the unknown one asked, in one call").hasSize(1);
        assertThat(titles.held("u-1", connectionId, List.of("NcQxTxoPMsM"))).containsEntry("NcQxTxoPMsM", "Con tieng sao thi sao");
    }

    @Test
    @DisplayName("a deleted upload still names its video when nothing live does; \"(untitled video)\" is no title; a video YouTube does not return is not asked again for hours")
    void deletedUntitledAndGone() {
        UUID mine = source(connectionId, "u-1");
        video(mine, "takenDown01", "Hoa Tau Sao Truc", TrackedDocument.Status.DELETED);
        video(mine, "untitled001", YouTubeVideoTitles.UNTITLED, TrackedDocument.Status.COMPLETED);
        UUID older = source(null, "u-1");
        video(older, "renamedVid1", "Old name", TrackedDocument.Status.DELETED);
        video(mine, "renamedVid1", "New name", TrackedDocument.Status.COMPLETED);
        api.on("GET", "/youtube/v3/videos").json("{'items':[]}");

        Map<String, String> found = titles.titles(client, "u-1", connectionId,
                List.of("takenDown01", "untitled001", "renamedVid1"), null);
        assertThat(found).containsExactlyInAnyOrderEntriesOf(Map.of("takenDown01", "Hoa Tau Sao Truc", "renamedVid1", "New name"));
        assertThat(api.requests("/youtube/v3/videos").get(0).param("id")).isEqualTo("untitled001");
        assertThat(titles.gone(connectionId, "untitled001")).isTrue();

        titles.titles(client, "u-1", connectionId, List.of("untitled001"), null);
        assertThat(api.requests("/youtube/v3/videos")).as("gone: not asked again meanwhile").hasSize(1);
    }

    @Test
    @DisplayName("another person's source on another connection lends no title; a source of the owner's own on another connection does")
    void scopedToTheOwner() {
        UUID theirs = source(UUID.randomUUID(), "u-2");
        video(theirs, "A43oBHyslkQ", "Someone else's", TrackedDocument.Status.COMPLETED);
        assertThat(titles.held("u-1", connectionId, List.of("A43oBHyslkQ"))).isEmpty();

        UUID mineElsewhere = source(UUID.randomUUID(), "u-1");
        video(mineElsewhere, "A43oBHyslkQ", "Biet Tri Ky - dizi Bb", TrackedDocument.Status.COMPLETED);
        assertThat(titles.held("u-1", connectionId, List.of("A43oBHyslkQ"))).containsEntry("A43oBHyslkQ", "Biet Tri Ky - dizi Bb");
        assertThat(api.requests()).isEmpty();
    }

    @Test
    @DisplayName("no client: nothing is asked of YouTube, and what is held is still given")
    void noClientAsksNothing() {
        UUID mine = source(connectionId, "u-1");
        video(mine, "A43oBHyslkQ", "Biet Tri Ky - dizi Bb", TrackedDocument.Status.COMPLETED);
        assertThat(titles.titles(null, "u-1", connectionId, List.of("A43oBHyslkQ", "NcQxTxoPMsM"), null))
                .containsExactlyEntriesOf(Map.of("A43oBHyslkQ", "Biet Tri Ky - dizi Bb"));
        assertThat(api.requests()).isEmpty();
    }
}
