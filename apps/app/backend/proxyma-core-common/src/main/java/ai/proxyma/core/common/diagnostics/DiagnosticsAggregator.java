package ai.proxyma.core.common.diagnostics;

import ai.proxyma.contracts.telemetry.diagnostics.DiagnosticsCounter;
import ai.proxyma.contracts.telemetry.diagnostics.DiagnosticsDay;
import ai.proxyma.contracts.telemetry.diagnostics.DiagnosticsError;
import ai.proxyma.contracts.telemetry.diagnostics.DiagnosticsTiming;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The in-memory half of diagnostics: per UTC day, a histogram per timing series, a sum per counter
 * series and a count per error fingerprint.
 *
 * <p>ALWAYS ON AND ALWAYS CHEAP. Recording does not look at the consent setting - it is a map
 * lookup and a few additions, and it is what lets Settings show a person exactly what WOULD be sent
 * before they agree to send it. Whether anything is kept past the day, written to disk or sent is
 * decided by the store that drains this, not here.
 *
 * <p>BOUNDED. At most {@link #MAX_SERIES_PER_DAY} timing plus counter series and
 * {@link #MAX_ERRORS_PER_DAY} error kinds per day; anything past a cap is counted in
 * {@code dropped} and not itemized. A connector failing on every item, or a warning logged in a
 * loop, costs one counter increment per occurrence and never a new entry.
 *
 * <p>Never throws and never logs: it is fed from a log appender, where either would recurse.
 */
public final class DiagnosticsAggregator {

    public static final int MAX_SERIES_PER_DAY = 1000;
    public static final int MAX_ERRORS_PER_DAY = 200;

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC);

    private final Clock clock;
    // Recording takes the READ lock - many recorders at once, never blocked by one another. A drain
    // takes the write lock so that a record racing it lands wholly before or wholly after the swap,
    // rather than into a day object that has already been read out and discarded.
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ConcurrentHashMap<LocalDate, Day> days = new ConcurrentHashMap<>();

    public DiagnosticsAggregator() {
        this(Clock.systemUTC());
    }

    public DiagnosticsAggregator(Clock clock) {
        this.clock = clock;
    }

    // -- recording ------------------------------------------------------------------------------

    /** Records one duration. {@code labels} are key, value pairs; values go through {@link DiagLabels#clean}. */
    public void time(DiagOp op, long ms, String... labels) {
        if (op == null) return;
        try {
            Map<String, String> l = labels(labels);
            String key = op.wireName() + l;
            lock.readLock().lock();
            try {
                Day d = today();
                TimingSeries s = d.timings.get(key);
                if (s == null) {
                    // Reserved inside the mapping function, so two threads racing to create the
                    // same series claim one slot between them, not two.
                    s = d.timings.computeIfAbsent(key, k -> d.reserveSeries()
                            ? new TimingSeries(op.wireName(), l, new LatencyHistogram()) : null);
                    if (s == null) return;
                }
                s.histogram.record(ms);
            } finally {
                lock.readLock().unlock();
            }
        } catch (RuntimeException ignored) {
            // Measurement must never fail the thing it measures.
        }
    }

    public void count(DiagCounter counter, String... labels) {
        count(counter, 1, labels);
    }

    public void count(DiagCounter counter, long n, String... labels) {
        if (counter == null || n <= 0) return;
        try {
            Map<String, String> l = labels(labels);
            String key = counter.wireName() + l;
            lock.readLock().lock();
            try {
                Day d = today();
                CounterSeries s = d.counters.get(key);
                if (s == null) {
                    s = d.counters.computeIfAbsent(key, k -> d.reserveSeries()
                            ? new CounterSeries(counter.wireName(), l, new LongAdder()) : null);
                    if (s == null) return;
                }
                s.count.add(n);
            } finally {
                lock.readLock().unlock();
            }
        } catch (RuntimeException ignored) {
            // As above.
        }
    }

    public void error(ErrorFingerprint fingerprint) {
        if (fingerprint == null) return;
        try {
            lock.readLock().lock();
            try {
                Day d = today();
                Instant now = clock.instant();
                ErrorSeries s = d.errors.get(fingerprint.fingerprint());
                if (s == null) {
                    s = d.errors.computeIfAbsent(fingerprint.fingerprint(), k -> {
                        if (d.errorKinds.get() >= MAX_ERRORS_PER_DAY) return null;
                        d.errorKinds.incrementAndGet();
                        return new ErrorSeries(fingerprint, now);
                    });
                    if (s == null) {
                        d.dropped.incrementAndGet();
                        return;
                    }
                }
                s.count.increment();
                s.lastSeen = now;
            } finally {
                lock.readLock().unlock();
            }
        } catch (RuntimeException ignored) {
            // As above - and doubly so here, since this is called from inside logging.
        }
    }

    // -- reading out ----------------------------------------------------------------------------

    /** Everything recorded so far, oldest day first, without removing it. */
    public List<DiagnosticsDay> peek() {
        lock.writeLock().lock();
        try {
            return snapshot(days);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Everything recorded so far, oldest day first, and forgets it. */
    public List<DiagnosticsDay> drain() {
        lock.writeLock().lock();
        try {
            List<DiagnosticsDay> out = snapshot(days);
            days.clear();
            return out;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Forgets every day before today and keeps today - what happens while sending is off. Today
     * stays so the preview in Settings has something to show; older days go, so an installation
     * that never opts in holds at most one day in memory.
     */
    public void discardBeforeToday() {
        lock.writeLock().lock();
        try {
            LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
            days.keySet().removeIf(d -> d.isBefore(today));
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void clear() {
        lock.writeLock().lock();
        try {
            days.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private Day today() {
        LocalDate day = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        return days.computeIfAbsent(day, k -> new Day());
    }

    private static List<DiagnosticsDay> snapshot(Map<LocalDate, Day> source) {
        List<LocalDate> keys = new ArrayList<>(source.keySet());
        keys.sort(Comparator.naturalOrder());
        List<DiagnosticsDay> out = new ArrayList<>(keys.size());
        for (LocalDate key : keys) {
            Day d = source.get(key);
            DiagnosticsDay day = new DiagnosticsDay(key.toString());
            for (TimingSeries s : d.timings.values()) {
                if (s.histogram.count() > 0) day.getTimings().add(s.histogram.toTiming(s.op, s.labels));
            }
            for (CounterSeries s : d.counters.values()) {
                long n = s.count.sum();
                if (n > 0) day.getCounters().add(new DiagnosticsCounter(s.name, new LinkedHashMap<>(s.labels), n));
            }
            for (ErrorSeries s : d.errors.values()) {
                day.getErrors().add(s.toError());
            }
            day.getTimings().sort(Comparator.comparing(DiagnosticsTiming::getOp)
                    .thenComparing(t -> t.getLabels().toString()));
            day.getCounters().sort(Comparator.comparing(DiagnosticsCounter::getName)
                    .thenComparing(c -> c.getLabels().toString()));
            day.getErrors().sort(Comparator.comparingLong(DiagnosticsError::getCount).reversed()
                    .thenComparing(DiagnosticsError::getFingerprint));
            day.setDropped(d.dropped.get());
            out.add(day);
        }
        return out;
    }

    /**
     * Key, value pairs as a sorted map, both sides cleaned. Sorted so the same labels in a different
     * order are the same series.
     */
    static Map<String, String> labels(String... kv) {
        if (kv == null || kv.length == 0) return Map.of();
        TreeMap<String, String> out = new TreeMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out.put(DiagLabels.clean(kv[i]), DiagLabels.clean(kv[i + 1]));
        }
        return out;
    }

    private static final class Day {
        final ConcurrentHashMap<String, TimingSeries> timings = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, CounterSeries> counters = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, ErrorSeries> errors = new ConcurrentHashMap<>();
        final AtomicInteger series = new AtomicInteger();
        final AtomicInteger errorKinds = new AtomicInteger();
        final AtomicLong dropped = new AtomicLong();

        /** Claims room for one new series, or counts it as dropped when the day is full. */
        boolean reserveSeries() {
            if (series.incrementAndGet() > MAX_SERIES_PER_DAY) {
                series.decrementAndGet();
                dropped.incrementAndGet();
                return false;
            }
            return true;
        }
    }

    private record TimingSeries(String op, Map<String, String> labels, LatencyHistogram histogram) {}

    private record CounterSeries(String name, Map<String, String> labels, LongAdder count) {}

    private static final class ErrorSeries {
        final ErrorFingerprint fingerprint;
        final Instant firstSeen;
        final LongAdder count = new LongAdder();
        volatile Instant lastSeen;

        ErrorSeries(ErrorFingerprint fingerprint, Instant firstSeen) {
            this.fingerprint = fingerprint;
            this.firstSeen = firstSeen;
            this.lastSeen = firstSeen;
        }

        DiagnosticsError toError() {
            DiagnosticsError e = new DiagnosticsError();
            e.setFingerprint(fingerprint.fingerprint());
            e.setLevel(fingerprint.level());
            e.setLogger(fingerprint.logger());
            e.setException(fingerprint.exception());
            e.setRootCause(fingerprint.rootCause());
            e.setFrames(new ArrayList<>(fingerprint.frames()));
            e.setTrace(new ArrayList<>(fingerprint.trace()));
            e.setCauses(new ArrayList<>(fingerprint.causes()));
            e.setCount(count.sum());
            e.setFirstSeen(HH_MM.format(firstSeen));
            e.setLastSeen(HH_MM.format(lastSeen));
            return e;
        }
    }
}
