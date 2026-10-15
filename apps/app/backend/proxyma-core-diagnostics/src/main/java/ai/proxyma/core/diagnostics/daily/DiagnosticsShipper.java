package ai.proxyma.core.diagnostics.daily;

import ai.proxyma.core.common.audit.AuditCategory;
import ai.proxyma.core.common.audit.AuditEntry;
import ai.proxyma.contracts.telemetry.diagnostics.DiagnosticsDay;
import ai.proxyma.contracts.telemetry.diagnostics.DiagnosticsReport;
import ai.proxyma.core.telemetryclient.TelemetryConsent;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/**
 * Sends whole days of diagnostics, compressed, in as few requests as possible - normally one a day.
 *
 * <p>SCHEDULE. The local flush runs every {@link #FLUSH_MINUTES} minutes. The send check runs every
 * hour, first a random 2-15 minutes after startup so installations do not all call in at the same
 * moment; it sends only when a COMPLETE day (before today, UTC) is waiting. After a success nothing
 * is waiting until the next day ends, so the hourly check is a no-op 23 hours out of 24 - and an
 * installation that was off at midnight sends on its next start.
 *
 * <p>FAILURE. The days stay on disk and the next attempt waits 1, 2, 4... hours, up to a day. After
 * {@link DiagnosticsStore#KEEP_DAYS} days unsent, a day is deleted rather than kept. A failure is
 * logged at WARN with the status only - never the body, and never what was being sent.
 *
 * <p>SUCCESS deletes exactly the rows that were in the report, by id, so a row flushed while the
 * request was in flight is not lost.
 */
@Component
public class DiagnosticsShipper {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticsShipper.class);

    static final int FLUSH_MINUTES = 10;
    /** Days per request. A backlog after a long outage goes in a few requests, never one per day. */
    static final int MAX_DAYS_PER_REQUEST = 10;
    static final Duration MAX_BACKOFF = Duration.ofHours(24);

    /** Where a compressed report goes. An interface so the retry and deletion rules are testable offline. */
    interface Transport {
        void send(byte[] gzippedJson, String instanceId) throws Exception;
    }

    private final DiagnosticsStore store;
    private final DiagnosticsReportBuilder builder;
    private final TelemetryConsent consent;
    private final ObjectMapper objectMapper;
    private final Transport transport;
    private final Clock clock;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "diagnostics");
        t.setDaemon(true);
        return t;
    });

    private volatile Instant nextAttemptAt = Instant.EPOCH;
    private volatile int consecutiveFailures;
    private volatile Instant lastSentAt;

    @Autowired
    public DiagnosticsShipper(DiagnosticsStore store, DiagnosticsReportBuilder builder, TelemetryConsent consent,
                              ObjectMapper objectMapper,
                              @Qualifier("externalRestClientBuilder") RestClient.Builder restClientBuilder) {
        this(store, builder, consent, objectMapper, httpTransport(restClientBuilder.build(), consent), Clock.systemUTC());
    }

    DiagnosticsShipper(DiagnosticsStore store, DiagnosticsReportBuilder builder, TelemetryConsent consent,
                       ObjectMapper objectMapper, Transport transport, Clock clock) {
        this.store = store;
        this.builder = builder;
        this.consent = consent;
        this.objectMapper = objectMapper;
        this.transport = transport;
        this.clock = clock;
    }

    @EventListener
    public void onReady(ApplicationReadyEvent event) {
        long firstSendSeconds = ThreadLocalRandom.current().nextLong(120, 900);
        scheduler.scheduleWithFixedDelay(this::flushQuietly, FLUSH_MINUTES, FLUSH_MINUTES, TimeUnit.MINUTES);
        scheduler.scheduleWithFixedDelay(this::tickQuietly, firstSendSeconds, 3600, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }

    /**
     * One send check: flush, then send every complete day waiting, oldest first.
     *
     * @return how many days were sent
     */
    synchronized int tick() {
        store.flush();
        if (!consent.mayCollect()) return 0;
        Instant now = clock.instant();
        if (now.isBefore(nextAttemptAt)) return 0;

        DiagnosticsStore.Pending pending = store.pendingBefore(store.today());
        if (pending.isEmpty()) return 0;
        List<DiagnosticsDay> days = DiagnosticsReportBuilder.mergeByDay(pending.days());
        if (days.isEmpty()) {
            // Only unreadable rows - nothing to send, and nothing worth keeping.
            store.delete(pending.idsFor(List.of()));
            return 0;
        }
        int sent = 0;
        for (int from = 0; from < days.size(); from += MAX_DAYS_PER_REQUEST) {
            List<DiagnosticsDay> slice = days.subList(from, Math.min(days.size(), from + MAX_DAYS_PER_REQUEST));
            try {
                DiagnosticsReport report = builder.build(new ArrayList<>(slice));
                byte[] body = gzip(objectMapper.writeValueAsBytes(report));
                transport.send(body, report.getInstanceId());
                sent += slice.size();
                auditSent(slice, body.length, report);
            } catch (Exception e) {
                onFailure(now, e);
                // The slices that did go are deleted below; the rest wait for the next attempt.
                break;
            }
        }
        if (sent > 0) {
            List<String> sentDays = days.subList(0, sent).stream().map(DiagnosticsDay::getDay).toList();
            store.delete(pending.idsFor(sentDays));
            lastSentAt = now;
            if (sent == days.size()) {
                consecutiveFailures = 0;
                nextAttemptAt = Instant.EPOCH;
            }
            log.info("[Diagnostics] Sent {} day(s) of diagnostics", sent);
        }
        return sent;
    }

    private void onFailure(Instant now, Exception e) {
        consecutiveFailures++;
        long hours = 1L << Math.min(consecutiveFailures - 1, 5);
        Duration wait = Duration.ofHours(hours).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : Duration.ofHours(hours);
        nextAttemptAt = now.plus(wait);
        String why = e instanceof RestClientResponseException r ? "HTTP " + r.getStatusCode().value()
                : e.getClass().getSimpleName();
        log.warn("[Diagnostics] Could not send diagnostics ({}); keeping them and retrying in {} hour(s)",
                why, wait.toHours());
        AuditEntry.of(AuditCategory.DIAGNOSTICS, "diagnostics.send-failed")
                .system()
                .outcome(AuditEntry.FAILED)
                .summary("Could not send diagnostics (" + why + "); kept, retrying in " + wait.toHours() + " hour(s)")
                .detail("reason", why)
                .detail("retryInHours", wait.toHours())
                .record();
    }

    // The Audit Logs page's record of exactly what left this machine: the report as it was sent,
    // before compression - the same JSON "See what would be sent" shows.
    private void auditSent(List<DiagnosticsDay> days, int bytes, DiagnosticsReport report) {
        try {
            List<String> names = days.stream().map(DiagnosticsDay::getDay).toList();
            AuditEntry.of(AuditCategory.DIAGNOSTICS, "diagnostics.sent")
                    .system()
                    .target(consent.serverUrl())
                    .summary("Sent " + names.size() + " day(s) of diagnostics ("
                            + (names.size() == 1 ? names.get(0) : names.get(0) + " to " + names.get(names.size() - 1))
                            + "), " + Math.max(1, bytes / 1024) + " KB compressed")
                    .detail("days", names)
                    .detail("compressedBytes", bytes)
                    .detail("report", objectMapper.convertValue(report, java.util.Map.class))
                    .record();
        } catch (RuntimeException ignored) {
            // Auditing must never fail a send.
        }
    }

    public Instant lastSentAt() {
        return lastSentAt;
    }

    public Instant nextAttemptAt() {
        return nextAttemptAt;
    }

    private void flushQuietly() {
        try {
            store.flush();
        } catch (RuntimeException e) {
            log.warn("[Diagnostics] Flush failed", e);
        }
    }

    private void tickQuietly() {
        try {
            tick();
        } catch (RuntimeException e) {
            log.warn("[Diagnostics] Send check failed", e);
        }
    }

    static byte[] gzip(byte[] json) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(256, json.length / 4));
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(json);
        }
        return out.toByteArray();
    }

    private static Transport httpTransport(RestClient restClient, TelemetryConsent consent) {
        return (body, instanceId) -> restClient.post()
                // The receiver is DiagnosticsIngestController's "/api/v1/telemetry/daily" - the per-event
                // path had the wrong prefix for its whole life (recurring failure class 19), so the
                // path is spelled out here and in the receiver the same way.
                .uri(consent.serverUrl() + "/api/v1/telemetry/daily")
                .header("X-Instance-Id", instanceId)
                .header(HttpHeaders.CONTENT_ENCODING, "gzip")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }
}
