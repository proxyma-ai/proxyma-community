package ai.proxyma.core.diagnostics.daily;

import ai.proxyma.contracts.license.LicenseTier;
import ai.proxyma.contracts.telemetry.diagnostics.DiagnosticsCounter;
import ai.proxyma.contracts.telemetry.diagnostics.DiagnosticsDay;
import ai.proxyma.contracts.telemetry.diagnostics.DiagnosticsEnvironment;
import ai.proxyma.contracts.telemetry.diagnostics.DiagnosticsReport;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.telemetryclient.InstanceIdProvider;
import ai.proxyma.core.telemetryclient.TelemetryConsent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Batched sending: whole days only, one compressed request, delete only on success, back off on
 * failure, never while consent is withheld.
 */
class DiagnosticsShipperTest {

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-21T08:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** Records what it was handed; fails while {@code failing} is set. */
    static final class FakeTransport implements DiagnosticsShipper.Transport {
        final List<byte[]> sent = new ArrayList<>();
        boolean failing;
        int attempts;
        @Override public void send(byte[] gzippedJson, String instanceId) throws Exception {
            attempts++;
            if (failing) throw new java.net.ConnectException("refused");
            sent.add(gzippedJson);
        }
    }

    private final MutableClock clock = new MutableClock();
    private final DiagnosticsStore store = mock(DiagnosticsStore.class);
    private final TelemetryConsent consent = mock(TelemetryConsent.class);
    private final FakeTransport transport = new FakeTransport();
    private final ObjectMapper mapper = new ObjectMapper();
    private DiagnosticsShipper shipper;

    @BeforeEach
    void setUp() {
        InstanceIdProvider ids = mock(InstanceIdProvider.class);
        when(ids.getInstanceId()).thenReturn("3f1c-instance");
        FeatureGateService gate = mock(FeatureGateService.class);
        when(gate.getCurrentTier()).thenReturn(LicenseTier.COMMUNITY);
        DiagnosticsSnapshot snapshot = mock(DiagnosticsSnapshot.class);
        when(snapshot.edition()).thenReturn("desktop");
        when(snapshot.environment()).thenReturn(new DiagnosticsEnvironment());
        when(snapshot.features()).thenReturn(Map.of("reranking", false));
        DiagnosticsReportBuilder builder = new DiagnosticsReportBuilder(ids, gate, snapshot);
        shipper = new DiagnosticsShipper(store, builder, consent, mapper, transport, clock);
        when(store.today()).thenReturn(LocalDate.parse("2026-09-21"));
        when(consent.mayCollect()).thenReturn(true);
    }

    private static DiagnosticsDay day(String date, long chatTurns) {
        DiagnosticsDay d = new DiagnosticsDay(date);
        d.getCounters().add(new DiagnosticsCounter("chat.turn", Map.of(), chatTurns));
        return d;
    }

    private void pending(DiagnosticsStore.Row... rows) {
        when(store.pendingBefore(LocalDate.parse("2026-09-21"))).thenReturn(new DiagnosticsStore.Pending(List.of(rows)));
    }

    @SuppressWarnings("unchecked")
    private List<Long> deletedIds() {
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(store).delete(captor.capture());
        return captor.getValue();
    }

    private DiagnosticsReport decode(byte[] gz) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return mapper.readValue(in.readAllBytes(), DiagnosticsReport.class);
        }
    }

    @Test
    void nothingIsSentOrDeletedWithoutConsent() {
        when(consent.mayCollect()).thenReturn(false);
        pending(new DiagnosticsStore.Row(1L, day("2026-09-20", 3)));
        assertThat(shipper.tick()).isZero();
        assertThat(transport.attempts).isZero();
        verify(store, never()).delete(any());
    }

    @Test
    void completeDaysGoInOneCompressedRequestAndAreThenDeleted() throws Exception {
        // Two partial rows for the 19th and one for the 20th: one request, two merged days.
        pending(new DiagnosticsStore.Row(1L, day("2026-09-19", 2)),
                new DiagnosticsStore.Row(2L, day("2026-09-20", 5)),
                new DiagnosticsStore.Row(3L, day("2026-09-19", 1)));

        assertThat(shipper.tick()).isEqualTo(2);

        assertThat(transport.sent).hasSize(1);
        DiagnosticsReport report = decode(transport.sent.get(0));
        assertThat(report.getInstanceId()).isEqualTo("3f1c-instance");
        assertThat(report.getDays()).extracting(DiagnosticsDay::getDay).containsExactly("2026-09-19", "2026-09-20");
        assertThat(report.getDays().get(0).getCounters().get(0).getCount()).isEqualTo(3);
        assertThat(deletedIds()).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    void aFailedSendKeepsTheDaysAndBacksOff() {
        pending(new DiagnosticsStore.Row(7L, day("2026-09-20", 1)));
        transport.failing = true;

        assertThat(shipper.tick()).isZero();
        verify(store, never()).delete(any());
        assertThat(transport.attempts).isEqualTo(1);

        // An immediate retry is suppressed by the backoff...
        clock.now = clock.now.plus(Duration.ofMinutes(30));
        assertThat(shipper.tick()).isZero();
        assertThat(transport.attempts).isEqualTo(1);

        // ...and the next attempt after it succeeds and deletes.
        transport.failing = false;
        clock.now = clock.now.plus(Duration.ofHours(1));
        assertThat(shipper.tick()).isEqualTo(1);
        assertThat(deletedIds()).containsExactly(7L);
    }

    @Test
    void theBackoffDoublesAndIsCappedAtADay() {
        pending(new DiagnosticsStore.Row(7L, day("2026-09-20", 1)));
        transport.failing = true;
        Instant start = clock.now;
        for (int i = 0; i < 8; i++) {
            shipper.tick();
            clock.now = shipper.nextAttemptAt();
        }
        assertThat(Duration.between(start, shipper.nextAttemptAt())).isLessThanOrEqualTo(Duration.ofHours(1 + 2 + 4 + 8 + 16 + 24 + 24 + 24));
        Instant before = clock.now;
        shipper.tick();
        assertThat(Duration.between(before, shipper.nextAttemptAt())).isEqualTo(DiagnosticsShipper.MAX_BACKOFF);
    }

    @Test
    void aLongBacklogIsSentInAFewRequestsNotOnePerDay() {
        List<DiagnosticsStore.Row> rows = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            rows.add(new DiagnosticsStore.Row((long) i, day(LocalDate.parse("2026-09-08").plusDays(i).toString(), 1)));
        }
        when(store.pendingBefore(LocalDate.parse("2026-09-21"))).thenReturn(new DiagnosticsStore.Pending(rows));
        assertThat(shipper.tick()).isEqualTo(12);
        assertThat(transport.sent).hasSize(2);
    }

    @Test
    void anUnreadableRowIsDeletedWithTheSendRatherThanRetriedForever() {
        pending(new DiagnosticsStore.Row(1L, day("2026-09-20", 1)), new DiagnosticsStore.Row(2L, null));
        shipper.tick();
        assertThat((Collection<Long>) deletedIds()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void nothingPendingMeansNoRequest() {
        pending();
        assertThat(shipper.tick()).isZero();
        assertThat(transport.attempts).isZero();
    }
}
