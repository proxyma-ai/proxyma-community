package ai.proxyma.core.diagnostics.daily;

import ai.proxyma.contracts.telemetry.diagnostics.DiagnosticsDay;
import ai.proxyma.contracts.telemetry.diagnostics.DiagnosticsEnvironment;
import ai.proxyma.core.common.diagnostics.DiagCounter;
import ai.proxyma.core.common.diagnostics.DiagOp;
import ai.proxyma.core.common.diagnostics.DiagnosticsAggregator;
import ai.proxyma.core.settings.event.DataCollectionChangedEvent;
import ai.proxyma.core.telemetryclient.TelemetryConsent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LOCAL FIRST, AND ONLY WITH CONSENT. Nothing reaches disk while collection is not allowed - which
 * covers the switch being off, an Enterprise tier and a missing endpoint, all folded into
 * TelemetryConsent.mayCollect() (pinned separately in DiagnosticsConsentTest) - and switching off
 * deletes what was waiting.
 */
class DiagnosticsStoreGatingTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-21T10:00:00Z"), ZoneOffset.UTC);

    private final DiagnosticChunkRepository repository = mock(DiagnosticChunkRepository.class);
    private final TelemetryConsent consent = mock(TelemetryConsent.class);
    private final DiagnosticsSnapshot snapshot = mock(DiagnosticsSnapshot.class);
    private final DiagnosticsAggregator aggregator = new DiagnosticsAggregator(CLOCK);
    private final DiagnosticsStore store =
            new DiagnosticsStore(repository, consent, new ObjectMapper(), snapshot, aggregator, CLOCK);

    @Test
    void nothingIsWrittenWhileCollectionIsNotAllowed() {
        when(consent.mayCollect()).thenReturn(false);
        when(repository.count()).thenReturn(4L);
        aggregator.count(DiagCounter.CHAT_TURN);
        aggregator.time(DiagOp.SEARCH_TOTAL, 90);

        store.flush();

        verify(repository, never()).save(any());
        // And what was already waiting is gone.
        verify(repository).deleteEverything();
        // Today stays in memory only, for the "what would be sent" preview.
        assertThat(aggregator.peek()).extracting(DiagnosticsDay::getDay).containsExactly("2026-09-21");
    }

    @Test
    void withConsentADayIsWrittenWithItsSnapshotOnce() throws Exception {
        when(consent.mayCollect()).thenReturn(true);
        DiagnosticsEnvironment env = new DiagnosticsEnvironment();
        env.setOs("Windows");
        when(snapshot.environment()).thenReturn(env);
        when(snapshot.features()).thenReturn(Map.of("reranking", true));
        aggregator.count(DiagCounter.CHAT_TURN);

        store.flush();
        aggregator.count(DiagCounter.CHAT_TURN);
        store.flush();

        ArgumentCaptor<DiagnosticChunk> saved = ArgumentCaptor.forClass(DiagnosticChunk.class);
        verify(repository, atLeastOnce()).save(saved.capture());
        List<DiagnosticChunk> chunks = saved.getAllValues();
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).getDay()).isEqualTo(LocalDate.parse("2026-09-21"));
        DiagnosticsDay first = new ObjectMapper().readValue(chunks.get(0).getPayload(), DiagnosticsDay.class);
        DiagnosticsDay second = new ObjectMapper().readValue(chunks.get(1).getPayload(), DiagnosticsDay.class);
        assertThat(first.getEnvironment().getOs()).isEqualTo("Windows");
        assertThat(first.getFeatures()).containsEntry("reranking", true);
        assertThat(second.getEnvironment()).isNull();
        assertThat(aggregator.peek()).isEmpty();
        // Unsent days past the keep window are dropped on every flush.
        verify(repository, atLeastOnce()).deleteOlderThan(LocalDate.parse("2026-09-21").minusDays(DiagnosticsStore.KEEP_DAYS));
    }

    @Test
    void switchingOffDeletesAtOnceRatherThanAtTheNextFlush() {
        when(repository.count()).thenReturn(2L);
        store.onDataCollectionChanged(new DataCollectionChangedEvent(false));
        verify(repository).deleteEverything();
    }

    @Test
    void switchingOnDeletesNothing() {
        store.onDataCollectionChanged(new DataCollectionChangedEvent(true));
        verify(repository, never()).deleteEverything();
    }

    @Test
    void whatWasRecordedBeforeConsentIsNeverStored() throws Exception {
        // Off: recorded in memory for the preview, never written.
        when(consent.mayCollect()).thenReturn(false);
        aggregator.count(DiagCounter.CHAT_TURN);
        aggregator.count(DiagCounter.CHAT_TURN);
        store.flush();

        // On, later the same day: only what happens from now on is collected.
        store.onDataCollectionChanged(new DataCollectionChangedEvent(true));
        when(consent.mayCollect()).thenReturn(true);
        when(snapshot.environment()).thenReturn(new DiagnosticsEnvironment());
        when(snapshot.features()).thenReturn(Map.of());
        aggregator.count(DiagCounter.CHAT_TURN);
        store.flush();

        ArgumentCaptor<DiagnosticChunk> saved = ArgumentCaptor.forClass(DiagnosticChunk.class);
        verify(repository).save(saved.capture());
        DiagnosticsDay day = new ObjectMapper().readValue(saved.getValue().getPayload(), DiagnosticsDay.class);
        assertThat(day.getCounters()).hasSize(1);
        assertThat(day.getCounters().get(0).getCount()).isEqualTo(1);
    }

    @Test
    void deleteCollectedDataClearsDiskAndMemoryIncludingToday() {
        aggregator.count(DiagCounter.APP_START);
        store.deleteAll();
        verify(repository).deleteEverything();
        assertThat(aggregator.peek()).isEmpty();
    }
}
