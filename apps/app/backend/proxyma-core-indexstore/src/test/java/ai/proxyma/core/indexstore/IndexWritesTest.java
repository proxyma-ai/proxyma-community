package ai.proxyma.core.indexstore;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The keyword index's writes, its swap and its listing (ingest-13, -19, -28), and the pgvector
 * search bounds (ingest-05).
 */
class IndexWritesTest {

    @Test
    @DisplayName("ingest-13: a keyword write that cannot happen throws, rather than completing a document with no keywords")
    void aFailedWriteThrows(@TempDir Path dir) throws Exception {
        // The collection's directory cannot be made: a file holds its name.
        Path root = dir.resolve("lucene");
        Files.createDirectories(root);
        Files.writeString(root.resolve("blocked"), "not a directory");
        BM25IndexService index = new BM25IndexService(root.toString(), null);

        assertThatThrownBy(() -> index.addDocuments("blocked", "doc.md", List.of("text"), null))
                .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    @DisplayName("ingest-19: a swap puts the shadow's index in the live place and leaves nothing else behind")
    void aSwapReplacesTheLiveIndex(@TempDir Path dir) throws Exception {
        BM25IndexService index = new BM25IndexService(dir.toString(), null);
        index.addDocuments("live", "old.md", List.of("the old model's chunk about invoices"), null);
        index.addDocuments("live_reindex", "new.md", List.of("the new model's chunk about invoices"), null);

        index.swapCollection("live", "live_reindex");

        assertThat(index.search(List.of("live"), "invoices", 5))
                .extracting(BM25IndexService.BM25Result::source).containsExactly("new.md");
        assertThat(index.isStale("live_reindex")).isTrue();
        try (var left = Files.list(dir)) {
            assertThat(left.map(p -> p.getFileName().toString()).toList()).containsExactly("live");
        }
    }

    @Test
    @DisplayName("ingest-28: past the listing's ceiling, the newest entries are listed - not the oldest")
    void listAllKeepsTheNewest(@TempDir Path dir) throws Exception {
        BM25IndexService index = new BM25IndexService(dir.toString(), null);
        Path collection = dir.resolve("memory");
        Files.createDirectories(collection);
        // Written straight to Lucene in one commit, oldest first, as memory entries accumulate.
        try (FSDirectory directory = FSDirectory.open(collection);
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
            for (int i = 0; i < 2100; i++) {
                String source = String.format("%019d-k-entry", i);
                org.apache.lucene.document.Document doc = new org.apache.lucene.document.Document();
                doc.add(new StringField(BM25IndexService.FIELD_SOURCE, source, Field.Store.YES));
                doc.add(new TextField(BM25IndexService.FIELD_CONTENT, "fact " + i, Field.Store.NO));
                doc.add(new StoredField(BM25IndexService.FIELD_TEXT, "fact " + i));
                writer.addDocument(doc);
            }
            writer.commit();
        }

        List<BM25IndexService.BM25Result> newest = index.listAll("memory", 3);

        assertThat(newest).extracting(BM25IndexService.BM25Result::text).containsExactly("fact 2099", "fact 2098", "fact 2097");
    }

    @Test
    @DisplayName("ingest-05: ef_search stays inside pgvector's 1..1000, however many candidates a search asks for")
    void efSearchIsBounded() {
        assertThat(PgVectorStoreService.efSearchFor(5)).isEqualTo(100);
        assertThat(PgVectorStoreService.efSearchFor(300)).isEqualTo(600);
        assertThat(PgVectorStoreService.efSearchFor(510)).isEqualTo(PgVectorStoreService.MAX_EF_SEARCH);
        assertThat(PgVectorStoreService.efSearchFor(100_000)).isEqualTo(PgVectorStoreService.MAX_EF_SEARCH);
    }
}
