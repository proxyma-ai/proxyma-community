package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A source that finds its items slowly hands them over as it finds them.
 *
 * <p>The failure this pins: a web crawl finds one page per fetch, and the sync took a full hundred
 * from a source before recording the first - which, at the default limit of a hundred pages, was
 * the whole crawl. The source sat at "Discovering items..." with a count of zero until it ended,
 * while a Confluence space, whose API answers a hundred at a time, listed its items at once.
 */
class SyncPullTest {

    private static ItemMetadata item(int n) {
        return new ItemMetadata("id-" + n, "Item " + n, ConnectorType.WEB_PAGE, Instant.EPOCH, "text/markdown");
    }

    /** A source of {@code size} items, each costing {@code secondsEach} on the given clock to find. */
    private static Iterator<ItemMetadata> source(int size, long secondsEach, AtomicLong clock) {
        return new Iterator<>() {
            int next = 0;
            @Override public boolean hasNext() {
                if (next >= size) return false;
                clock.addAndGet(TimeUnit.SECONDS.toNanos(secondsEach));
                return true;
            }
            @Override public ItemMetadata next() { return item(next++); }
        };
    }

    @Test
    @DisplayName("a slow source's first items are handed on after a couple of seconds, not after a hundred")
    void aSlowSourceIsNotWaitedOnForAFullBatch() {
        AtomicLong clock = new AtomicLong();
        List<ItemMetadata> pulled = new ArrayList<>();

        boolean exhausted = SyncService.pull(source(100, 1, clock), pulled, 100, clock::get);

        assertThat(pulled).hasSize(2);
        assertThat(exhausted).as("it stopped on the clock; the source was not asked whether it is done").isFalse();
    }

    @Test
    @DisplayName("a page that takes longer than the whole budget is still handed on, alone")
    void oneVerySlowItemIsABatch() {
        AtomicLong clock = new AtomicLong();
        List<ItemMetadata> pulled = new ArrayList<>();

        boolean exhausted = SyncService.pull(source(100, 30, clock), pulled, 100, clock::get);

        assertThat(pulled).hasSize(1);
        assertThat(exhausted).isFalse();
    }

    @Test
    @DisplayName("a fast source still fills the batch")
    void aFastSourceFillsTheBatch() {
        AtomicLong clock = new AtomicLong();
        Iterator<ItemMetadata> source = source(250, 0, clock);
        List<ItemMetadata> pulled = new ArrayList<>();

        assertThat(SyncService.pull(source, pulled, 100, clock::get)).isFalse();
        assertThat(pulled).hasSize(100);
    }

    @Test
    @DisplayName("exhausted is said only when the source said so - and then with whatever it gave")
    void exhaustionIsTheSourcesWord() {
        AtomicLong clock = new AtomicLong();
        Iterator<ItemMetadata> source = source(30, 0, clock);
        List<ItemMetadata> pulled = new ArrayList<>();

        assertThat(SyncService.pull(source, pulled, 100, clock::get)).isTrue();
        assertThat(pulled).hasSize(30);
        // Asked again, it has nothing and says so: the caller's loop ends on this, never on a
        // batch that merely came back short.
        assertThat(SyncService.pull(source, pulled, 100, clock::get)).isTrue();
        assertThat(pulled).hasSize(30);
    }
}
