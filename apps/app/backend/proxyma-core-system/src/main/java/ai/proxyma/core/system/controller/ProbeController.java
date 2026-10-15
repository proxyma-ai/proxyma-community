package ai.proxyma.core.system.controller;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The two probes an orchestrator and the desktop launcher ask, beside {@link HealthController}'s
 * "what is this" - which stays exactly as it is, because the SPA and every monitor already read it.
 *
 * <ul>
 *   <li>{@code /api/v1/health/live} - 200 while the JVM answers HTTP, and nothing else. A database
 *       outage must not restart the process; that is a readiness question.</li>
 *   <li>{@code /api/v1/health/ready} - 200 once the application has finished starting (Spring's
 *       readiness state, which turns ACCEPTING_TRAFFIC only after every startup listener has run)
 *       and every database answers within two seconds; 503 with the reasons otherwise. The
 *       reasons are words, never an exception's message: this answers anyone who can reach it.</li>
 * </ul>
 *
 * <p>Both are reachable with no credential in every edition: AccessGate's open list on Desktop and
 * the Home Server, SecurityConfig's permitAll on Enterprise Server.
 */
@RestController
@RequestMapping("/api/v1/health")
public class ProbeController {

    private static final int DATABASE_TIMEOUT_SECONDS = 2;

    private final ApplicationAvailability availability;
    private final ObjectProvider<DataSource> dataSources;

    public ProbeController(ApplicationAvailability availability, ObjectProvider<DataSource> dataSources) {
        this.availability = availability;
        this.dataSources = dataSources;
    }

    @GetMapping("/live")
    public Map<String, Object> live() {
        return Map.of("status", "ok");
    }

    @GetMapping("/ready")
    public Mono<ResponseEntity<Map<String, Object>>> ready() {
        // JDBC blocks, and a probe that held an event-loop thread for two seconds would slow the
        // very requests it is asking about.
        return Mono.fromCallable(this::notReadyBecause)
                .subscribeOn(Schedulers.boundedElastic())
                .map(reasons -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("status", reasons.isEmpty() ? "ready" : "not ready");
                    if (!reasons.isEmpty()) body.put("reasons", reasons);
                    return ResponseEntity.status(reasons.isEmpty() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
                });
    }

    List<String> notReadyBecause() {
        List<String> reasons = new ArrayList<>();
        if (availability.getReadinessState() != ReadinessState.ACCEPTING_TRAFFIC) reasons.add("starting");
        dataSources.orderedStream().forEach(dataSource -> {
            if (!answers(dataSource) && !reasons.contains("database")) reasons.add("database");
        });
        return reasons;
    }

    private static boolean answers(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(DATABASE_TIMEOUT_SECONDS);
        } catch (Exception e) {
            return false;
        }
    }
}
