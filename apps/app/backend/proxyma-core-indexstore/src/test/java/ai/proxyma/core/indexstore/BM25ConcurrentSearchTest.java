package ai.proxyma.core.indexstore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Searches of one collection run side by side; only a write holds them up. Every search took the
 * collection's one exclusive lock, so on a source shared across an organization each user's
 * keyword search waited for every other's.
 */
class BM25ConcurrentSearchTest {

    private static final String COLLECTION = "shared-source";

    private BM25IndexService index;
    private final ExecutorService other = Executors.newCachedThreadPool();

    @BeforeEach
    void setUp(@TempDir Path dir) {
        index = new BM25IndexService(dir.toString(), null);
        index.addDocuments(COLLECTION, "handbook.md", List.of("payment terms are thirty days"), null);
    }

    private CompletableFuture<List<BM25IndexService.BM25Result>> searchElsewhere() {
        return CompletableFuture.supplyAsync(() -> index.search(List.of(COLLECTION), "payment terms", 5), other);
    }

    @Test
    @DisplayName("a search runs while another reader is in the same collection")
    void searchesShareACollection() throws Exception {
        Lock reading = index.lockFor(COLLECTION).readLock();
        reading.lock();
        try {
            // Under one exclusive lock this waited for the reader above - sixty seconds, then
            // skipped the collection.
            assertThat(searchElsewhere().get(10, TimeUnit.SECONDS)).hasSize(1);
            assertThat(CompletableFuture.supplyAsync(() -> index.getChunkTexts(COLLECTION, "handbook.md"), other)
                    .get(10, TimeUnit.SECONDS)).containsExactly("payment terms are thirty days");
        } finally {
            reading.unlock();
        }
    }

    @Test
    @DisplayName("a search waits for a write on its collection, and runs once the write is done")
    void aSearchWaitsForAWrite() throws Exception {
        Lock writing = index.lockFor(COLLECTION).writeLock();
        writing.lock();
        CompletableFuture<List<BM25IndexService.BM25Result>> search;
        try {
            search = searchElsewhere();
            assertThatThrownBy(() -> search.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        } finally {
            writing.unlock();
        }
        assertThat(search.get(10, TimeUnit.SECONDS)).hasSize(1);
    }

    @Test
    @DisplayName("searches running through a rename, an add and a delete never fail or lose the index")
    void searchesSurviveConcurrentWrites() throws Exception {
        CompletableFuture<Integer> searcher = CompletableFuture.supplyAsync(() -> {
            int found = 0;
            for (int i = 0; i < 300; i++) found += index.search(List.of(COLLECTION), "payment terms", 5).size();
            return found;
        }, other);
        for (int i = 0; i < 30; i++) {
            index.addDocuments(COLLECTION, "extra-" + i + ".md", List.of("payment terms vary by region " + i), null);
            index.renameSource(COLLECTION, "extra-" + i + ".md", "renamed-" + i + ".md");
            index.deleteDocuments(COLLECTION, "renamed-" + i + ".md");
        }

        // The handbook's chunk is there throughout, so every search finds at least it.
        assertThat(searcher.get(60, TimeUnit.SECONDS)).isGreaterThanOrEqualTo(300);
        assertThat(index.search(List.of(COLLECTION), "payment terms", 5)).hasSize(1);
    }
}
