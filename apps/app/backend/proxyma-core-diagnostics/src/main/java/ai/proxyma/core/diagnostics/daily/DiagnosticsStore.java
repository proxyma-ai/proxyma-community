package ai.proxyma.core.diagnostics.daily;

import ai.proxyma.core.common.audit.AuditCategory;
import ai.proxyma.core.common.audit.AuditEntry;
import ai.proxyma.contracts.telemetry.diagnostics.DiagnosticsDay;
import ai.proxyma.core.common.diagnostics.DiagnosticsAggregator;
import ai.proxyma.core.common.diagnostics.Diagnostics;
import ai.proxyma.core.settings.event.DataCollectionChangedEvent;
import ai.proxyma.core.telemetryclient.TelemetryConsent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * The local half of diagnostics: moves what the in-memory aggregator recorded onto disk, and only
 * while collection is allowed.
 *
 * <p>LOCAL FIRST. Nothing is written unless {@link TelemetryConsent#mayCollect()} - a configured
 * endpoint, a tier that allows it, and "Send diagnostics to Proxyma" on. While that is false a
 * flush forgets every day but today (today stays in memory only, so Settings can show what would be
 * sent) and deletes anything already on disk. Turning the switch off deletes at once, through
 * {@link DataCollectionChangedEvent}, rather than at the next flush.
 *
 * <p>Nothing on disk is kept past {@link #KEEP_DAYS} days: an installation that cannot reach the
 * endpoint for a month loses the oldest day each day rather than growing without bound.
 */
@Service
public class DiagnosticsStore {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticsStore.class);

    public static final int KEEP_DAYS = 30;

    @Value("${spring.application.version:0.0.0}")
    private String appVersion = "0.0.0";

    private final DiagnosticChunkRepository repository;
    private final TelemetryConsent consent;
    private final ObjectMapper objectMapper;
    private final DiagnosticsSnapshot snapshot;
    private final DiagnosticsAggregator aggregator;
    private final Clock clock;
    // The first flush of each day carries that day's environment and feature snapshot; later ones
    // do not repeat it. Per process, so a restart takes a fresh one - which is the moment settings
    // are most likely to have changed.
    private volatile LocalDate snapshotTakenFor;

    @Autowired
    public DiagnosticsStore(DiagnosticChunkRepository repository, TelemetryConsent consent,
                            ObjectMapper objectMapper, DiagnosticsSnapshot snapshot) {
        this(repository, consent, objectMapper, snapshot, Diagnostics.aggregator(), Clock.systemUTC());
    }

    DiagnosticsStore(DiagnosticChunkRepository repository, TelemetryConsent consent, ObjectMapper objectMapper,
                     DiagnosticsSnapshot snapshot, DiagnosticsAggregator aggregator, Clock clock) {
        this.repository = repository;
        this.consent = consent;
        this.objectMapper = objectMapper;
        this.snapshot = snapshot;
        this.aggregator = aggregator;
        this.clock = clock;
    }

    /** Writes what is in memory to disk, or discards it when collection is not allowed. */
    public synchronized void flush() {
        try {
            if (!consent.mayCollect()) {
                aggregator.discardBeforeToday();
                deleteStored();
                return;
            }
            LocalDate today = today();
            Instant now = clock.instant();
            for (DiagnosticsDay day : aggregator.drain()) {
                if (!day.getAppVersions().contains(appVersion)) day.getAppVersions().add(appVersion);
                if (today.toString().equals(day.getDay()) && !today.equals(snapshotTakenFor)) {
                    day.setEnvironment(snapshot.environment());
                    day.setFeatures(snapshot.features());
                    snapshotTakenFor = today;
                }
                DiagnosticChunk chunk = new DiagnosticChunk();
                chunk.setDay(LocalDate.parse(day.getDay()));
                chunk.setAppVersion(appVersion);
                chunk.setCreatedAt(now);
                chunk.setPayload(objectMapper.writeValueAsString(day));
                repository.save(chunk);
            }
            int expired = repository.deleteOlderThan(today.minusDays(KEEP_DAYS));
            if (expired > 0) {
                log.info("[Diagnostics] Deleted {} unsent record(s) older than {} days", expired, KEEP_DAYS);
                AuditEntry.of(AuditCategory.DIAGNOSTICS, "diagnostics.expired")
                        .system()
                        .summary("Deleted " + expired + " unsent record(s) older than " + KEEP_DAYS + " days")
                        .detail("records", expired)
                        .record();
            }
        } catch (Exception e) {
            // Logged with the exception: a flush that fails silently every ten minutes is the
            // shape of failure this project has already paid for (see recurring failure classes).
            log.warn("[Diagnostics] Could not store diagnostics locally", e);
        }
    }

    /** Ordered last, so lifecycle counters recorded on shutdown are in memory before this runs. */
    @EventListener
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onShutdown(ContextClosedEvent event) {
        flush();
    }

    @EventListener
    public void onDataCollectionChanged(DataCollectionChangedEvent event) {
        AuditEntry.of(AuditCategory.DIAGNOSTICS, event.allowed() ? "diagnostics.switched-on" : "diagnostics.switched-off")
                .user(null)
                .summary(event.allowed()
                        ? "Send diagnostics to Proxyma switched on - collection starts now"
                        : "Send diagnostics to Proxyma switched off - everything collected was deleted")
                .record();
        if (event.allowed()) {
            // COLLECTION STARTS AT CONSENT. The aggregator records whether or not collection is
            // allowed - today is kept in memory for the "See what would be sent" preview - so
            // without this, switching on stored and sent everything recorded earlier the same day,
            // before anyone agreed to it (found 2026-10-09, auditing the Privacy Policy).
            synchronized (this) {
                aggregator.clear();
                snapshotTakenFor = null;
            }
            return;
        }
        deleteStored();
        aggregator.discardBeforeToday();
        snapshotTakenFor = null;
    }

    /** Everything stored, as partial days, oldest first. */
    public List<DiagnosticsDay> storedDays() {
        return parse(repository.findAllByOrderByIdAsc()).days();
    }

    /** The stored chunks for days before {@code day} - what a send takes. */
    public Pending pendingBefore(LocalDate day) {
        return parse(repository.findByDayLessThanOrderByIdAsc(day));
    }

    public void delete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        repository.deleteByIds(ids);
    }

    /** "Delete collected data": disk and memory both, including today. */
    public synchronized void deleteAll() {
        repository.deleteEverything();
        aggregator.clear();
        snapshotTakenFor = null;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    private void deleteStored() {
        try {
            if (repository.count() > 0) {
                int n = repository.deleteEverything();
                log.info("[Diagnostics] Sending is off - deleted {} unsent local record(s)", n);
            }
        } catch (Exception e) {
            log.warn("[Diagnostics] Could not delete local diagnostics", e);
        }
    }

    private Pending parse(List<DiagnosticChunk> chunks) {
        List<Row> rows = new ArrayList<>(chunks.size());
        for (DiagnosticChunk c : chunks) {
            DiagnosticsDay day = null;
            try {
                day = objectMapper.readValue(c.getPayload(), DiagnosticsDay.class);
            } catch (Exception e) {
                // Kept as a row with no day, so a successful send deletes it rather than tripping
                // over it every day for a month.
                log.warn("[Diagnostics] Skipping an unreadable stored record: {}", e.getClass().getSimpleName());
            }
            rows.add(new Row(c.getId(), day));
        }
        return new Pending(rows);
    }

    /** One stored row: its id and what it holds, or a null day when it could not be read. */
    public record Row(Long id, DiagnosticsDay day) {}

    /** Stored rows and what they hold, loaded together so a send deletes exactly what it sent. */
    public record Pending(List<Row> rows) {

        public List<DiagnosticsDay> days() {
            return rows.stream().map(Row::day).filter(java.util.Objects::nonNull).toList();
        }

        /** Rows holding one of {@code days}, plus every unreadable row. */
        public List<Long> idsFor(java.util.Collection<String> dayKeys) {
            return rows.stream()
                    .filter(r -> r.day() == null || dayKeys.contains(r.day().getDay()))
                    .map(Row::id)
                    .toList();
        }

        public boolean isEmpty() {
            return rows.isEmpty();
        }
    }
}
