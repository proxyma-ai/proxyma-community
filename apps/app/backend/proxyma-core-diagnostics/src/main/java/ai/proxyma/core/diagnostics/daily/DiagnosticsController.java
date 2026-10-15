package ai.proxyma.core.diagnostics.daily;

import ai.proxyma.core.common.audit.AuditCategory;
import ai.proxyma.core.common.audit.AuditEntry;
import ai.proxyma.contracts.telemetry.diagnostics.DiagnosticsDay;
import ai.proxyma.contracts.telemetry.diagnostics.DiagnosticsReport;
import ai.proxyma.core.common.diagnostics.Diagnostics;
import ai.proxyma.core.telemetryclient.TelemetryConsent;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings > About > Diagnostics: "See what would be sent" and "Delete collected data".
 *
 * <p>The preview is built by {@link DiagnosticsReportBuilder#build} - the call the real send makes -
 * over everything stored plus what is still in memory. It shows today as well, which a real send
 * never includes until the day is over; the page says so rather than hiding the day being collected.
 *
 * <p>Deployment-wide, like the switch it belongs to: on Enterprise Server SecurityConfig restricts
 * {@code /api/v1/diagnostics/**} to SUPER_ADMIN. Desktop and the home server have no accounts.
 */
@RestController
@RequestMapping("/api/v1/diagnostics")
public class DiagnosticsController {

    private final DiagnosticsStore store;
    private final DiagnosticsReportBuilder builder;
    private final DiagnosticsShipper shipper;
    private final TelemetryConsent consent;

    public DiagnosticsController(DiagnosticsStore store, DiagnosticsReportBuilder builder,
                                 DiagnosticsShipper shipper, TelemetryConsent consent) {
        this.store = store;
        this.builder = builder;
        this.shipper = shipper;
        this.consent = consent;
    }

    @GetMapping("/preview")
    public Mono<ResponseEntity<Map<String, Object>>> preview() {
        return Mono.fromCallable(() -> {
            List<DiagnosticsDay> stored = store.storedDays();
            List<DiagnosticsDay> partials = new ArrayList<>(stored);
            partials.addAll(Diagnostics.aggregator().peek());
            DiagnosticsReport report = builder.build(partials);
            String today = store.today().toString();
            long waitingDays = stored.stream().map(DiagnosticsDay::getDay)
                    .filter(d -> d.compareTo(today) < 0).distinct().count();

            Map<String, Object> status = new LinkedHashMap<>();
            status.put("sending", consent.mayCollect());
            status.put("switchedOn", consent.switchedOn());
            status.put("blockedByEdition", consent.blockedByEdition());
            status.put("blockedByTier", consent.blockedByTier());
            status.put("configured", consent.isConfigured());
            status.put("daysWaiting", waitingDays);
            status.put("today", today);
            status.put("lastSentAt", toString(shipper.lastSentAt()));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", status);
            body.put("report", report);
            return ResponseEntity.ok(body);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/collected")
    public Mono<ResponseEntity<Void>> deleteCollected() {
        return Mono.fromRunnable(() -> {
                    store.deleteAll();
                    AuditEntry.of(AuditCategory.DIAGNOSTICS, "diagnostics.deleted")
                            .user(null)
                            .summary("Collected diagnostics deleted before they were sent")
                            .record();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    private static String toString(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
