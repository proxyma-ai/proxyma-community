package ai.proxyma.core.evaluation.controller;

import ai.proxyma.core.evaluation.model.ChatTurnMetric;
import ai.proxyma.core.evaluation.model.SearchStageMetric;
import ai.proxyma.core.evaluation.repository.ChatTurnMetricRepository;
import ai.proxyma.core.evaluation.repository.SearchStageMetricRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.JWTClaimsSet;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * System > Performance - the overview the page reads, against a real H2.
 *
 * <p>It never loaded: the endpoint answered 403 unless the retired evaluation.enabled toggle was on,
 * which nothing sets, and on Enterprise Server it refused an ADMIN, who is shown the page. Its
 * figures are now two aggregate queries, one per table, and these cases run them.
 */
class PerformanceOverviewTest {

    private SessionFactory sessions;

    @BeforeEach
    void setUp() {
        sessions = new Configuration()
                .addAnnotatedClass(ChatTurnMetric.class)
                .addAnnotatedClass(SearchStageMetric.class)
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:perf-" + UUID.randomUUID())
                .setProperty("hibernate.connection.username", "sa")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .buildSessionFactory();
    }

    @AfterEach
    void tearDown() {
        sessions.close();
    }

    private <T> T tx(Function<AdminEvaluationController, T> work, Function<EntityManager, Void> seed,
                     boolean multiUser) {
        EntityManager em = sessions.createEntityManager();
        try {
            em.getTransaction().begin();
            if (seed != null) seed.apply(em);
            JpaRepositoryFactory factory = new JpaRepositoryFactory(em);
            AdminEvaluationController controller = new AdminEvaluationController(
                    factory.getRepository(ChatTurnMetricRepository.class),
                    factory.getRepository(SearchStageMetricRepository.class),
                    new ObjectMapper());
            ReflectionTestUtils.setField(controller, "multiUser", multiUser);
            T result = work.apply(controller);
            em.getTransaction().commit();
            return result;
        } finally {
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
            em.close();
        }
    }

    private static MockServerWebExchange as(String role) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/admin/evaluation/overview"));
        if (role != null) {
            exchange.getAttributes().put("PROXYMA_USER_CLAIMS", new JWTClaimsSet.Builder().claim("role", role).build());
        }
        return exchange;
    }

    @Test
    @DisplayName("the overview answers with no toggle, and its figures are the rows in the window")
    void figures() {
        Instant now = Instant.now();
        Instant old = now.minus(Duration.ofDays(40));
        ResponseEntity<Map<String, Object>> response = tx(c -> c.overview("30d", as(null)).block(), em -> {
            em.persist(new ChatTurnMetric("p", "m", 100, 1000, 0, true, 10, now));
            em.persist(new ChatTurnMetric("p", "m", 300, 3000, 2, false, 10, now));
            em.persist(new ChatTurnMetric("p", "m", 9999, 99999, 9, false, 10, old));
            em.persist(new SearchStageMetric(5, 1, 10, 20, 30, 40, 100, 3, now).withGraph(true, 50));
            em.persist(new SearchStageMetric(5, 1, 30, 40, 50, 60, 300, 3, now).withGraph(false, 0));
            em.persist(new SearchStageMetric(5, 1, 30, 40, 50, 60, 500, 3, now));
            em.persist(new SearchStageMetric(5, 1, 999, 999, 999, 999, 9999, 3, old));
            return null;
        }, false);

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertEquals(2L, body.get("chatTurnCount"));
        assertEquals(0.5, (double) body.get("errorRate"), 1e-9);
        assertEquals(200.0, (double) body.get("avgTimeToFirstTokenMs"), 1e-9);
        assertEquals(2000.0, (double) body.get("avgTotalDurationMs"), 1e-9);
        assertEquals(1.0, (double) body.get("avgRetryCount"), 1e-9);
        assertEquals(3L, body.get("searchCount"));
        assertEquals(300.0, (double) body.get("avgSearchTotalMs"), 1e-9);
        assertEquals(1L, body.get("graphSearchCount"));
        assertEquals(50.0, (double) body.get("avgGraphMs"), 1e-9);
        assertEquals(100.0, (double) body.get("avgSearchTotalMsWithGraph"), 1e-9);
        // Without the graph: the one that said false and the one that predates the column.
        assertEquals(400.0, (double) body.get("avgSearchTotalMsWithoutGraph"), 1e-9);
    }

    @Test
    @DisplayName("an empty window is zeros, not an error")
    void empty() {
        ResponseEntity<Map<String, Object>> response = tx(c -> c.overview("24h", as(null)).block(), null, false);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(0L, response.getBody().get("chatTurnCount"));
        assertEquals(0L, response.getBody().get("searchCount"));
        assertEquals(0.0, (double) response.getBody().get("avgSearchTotalMs"), 1e-9);
    }

    @Test
    @DisplayName("on Enterprise Server an ADMIN and a SUPER_ADMIN read it, and nobody else does")
    void administratorsOnly() {
        assertEquals(200, tx(c -> c.overview("7d", as("ADMIN")).block(), null, true).getStatusCode().value());
        assertEquals(200, tx(c -> c.overview("7d", as("SUPER_ADMIN")).block(), null, true).getStatusCode().value());
        assertEquals(403, tx(c -> c.overview("7d", as("MEMBER")).block(), null, true).getStatusCode().value());
        assertEquals(403, tx(c -> c.overview("7d", as(null)).block(), null, true).getStatusCode().value());
    }
}
