package ai.proxyma.core.search.service;

import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.indexstore.VectorStoreService;
import ai.proxyma.core.search.service.AgentMemoryService.MemoryCategory;
import ai.proxyma.core.search.service.AgentMemoryService.MemoryEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Memory with the embedding server DOWN - the case the maintainer's feedback of 2026-09-26 recorded,
 * where the agent reported memory "timing out" three times before a fact stuck. The keyword index is
 * real (Lucene in a temp dir); the vector store and the hybrid search throw, as they do when every
 * embedding call fails. Everything here has to keep working through that.
 */
class AgentMemoryServiceTest {

    @TempDir Path lucene;
    private AgentMemoryService memory;

    @BeforeEach
    void setUp() {
        VectorStoreService vectors = mock(VectorStoreService.class);
        doThrow(new RuntimeException("embedding server unreachable")).when(vectors).upsert(anyString(), anyList());
        when(vectors.search(anyList(), anyString(), anyInt(), anyDouble()))
                .thenThrow(new RuntimeException("embedding server unreachable"));
        SearchService search = mock(SearchService.class);
        when(search.search(anyList(), anyString(), anyInt(), anyDouble()))
                .thenThrow(new RuntimeException("embedding server unreachable"));
        memory = new AgentMemoryService(vectors, new BM25IndexService(lucene.toString(), null), search);
    }

    @Test
    @DisplayName("a fact is stored even when the embedding write fails, and comes back with its id")
    void storesWithoutEmbeddings() throws Exception {
        String id = memory.remember("u1", "The search repos are acme-search and acme-index.", MemoryCategory.KNOWLEDGE);
        assertThat(id).isNotNull();
        List<MemoryEntry> all = memory.list("u1", null, 10);
        assertThat(all).extracting(MemoryEntry::id).containsExactly(id);
        assertThat(all.get(0).category()).isEqualTo("KNOWLEDGE");
        assertThat(all.get(0).savedAt()).isNotNull();
    }

    @Test
    @DisplayName("an entry is read back whole by its id, and only by its owner")
    void readsById() {
        String id = memory.remember("u1", "Prefers answers in Vietnamese.", MemoryCategory.USER);
        assertThat(memory.read("u1", id)).map(MemoryEntry::text).contains("Prefers answers in Vietnamese.");
        assertThat(memory.read("u2", id)).isEmpty();
        assertThat(memory.read("u1", "no-such-id")).isEmpty();
    }

    @Test
    @DisplayName("recall falls back to keywords when semantic search fails, and returns ids")
    void recallFallsBackToKeywords() {
        String id = memory.remember("u1", "The ledger is reconciled every Friday.", MemoryCategory.KNOWLEDGE);
        memory.remember("u1", "Unrelated note about coffee.", MemoryCategory.KNOWLEDGE);
        List<MemoryEntry> hits = memory.recall("u1", "ledger reconciled", 5);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).id()).isEqualTo(id);
    }

    @Test
    @DisplayName("a listing is newest first and can be limited to one category")
    void listsNewestFirstByCategory() throws Exception {
        String first = memory.remember("u1", "first knowledge", MemoryCategory.KNOWLEDGE);
        Thread.sleep(5);
        String user = memory.remember("u1", "a user fact", MemoryCategory.USER);
        Thread.sleep(5);
        String last = memory.remember("u1", "last knowledge", MemoryCategory.KNOWLEDGE);
        assertThat(memory.list("u1", null, 10)).extracting(MemoryEntry::id).containsExactly(last, user, first);
        assertThat(memory.list("u1", MemoryCategory.USER, 10)).extracting(MemoryEntry::id).containsExactly(user);
        assertThat(memory.list("u1", null, 1)).extracting(MemoryEntry::id).containsExactly(last);
    }

    @Test
    @DisplayName("an id in an older format still reads, just without a category or date")
    void olderIdFormat() {
        MemoryEntry e = AgentMemoryService.entry("legacy-source", "text");
        assertThat(e.category()).isNull();
        assertThat(e.savedAt()).isNull();
        assertThat(AgentMemoryService.entry("0000001790414284000-u-abcd1234", "x").category()).isEqualTo("USER");
    }

    // Settings > Memory (2026-10-03): the user's own edits.

    @Test
    @DisplayName("an edit changes the text and category and keeps the entry's place and date")
    void editKeepsItsPlace() throws Exception {
        String older = memory.remember("u1", "Works on the SAI project.", MemoryCategory.KNOWLEDGE);
        Thread.sleep(5);
        String newer = memory.remember("u1", "Prefers short answers.", MemoryCategory.USER);
        String savedAt = memory.read("u1", older).orElseThrow().savedAt();

        MemoryEntry edited = memory.edit("u1", older, "Leads the SAI project.", MemoryCategory.USER).orElseThrow();

        assertThat(edited.text()).isEqualTo("Leads the SAI project.");
        assertThat(edited.category()).isEqualTo("USER");
        assertThat(edited.savedAt()).isEqualTo(savedAt);
        assertThat(memory.list("u1", null, 10)).extracting(MemoryEntry::id).containsExactly(newer, edited.id());
        assertThat(memory.read("u1", older)).as("the old id is gone").isEmpty();
        assertThat(memory.read("u1", edited.id())).map(MemoryEntry::text).contains("Leads the SAI project.");
    }

    @Test
    @DisplayName("an edit with no text only moves the entry to another category")
    void editCategoryOnly() {
        String id = memory.remember("u1", "Lives in Hanoi.", MemoryCategory.KNOWLEDGE);

        MemoryEntry edited = memory.edit("u1", id, null, MemoryCategory.USER).orElseThrow();

        assertThat(edited.text()).isEqualTo("Lives in Hanoi.");
        assertThat(memory.list("u1", MemoryCategory.USER, 10)).extracting(MemoryEntry::text).containsExactly("Lives in Hanoi.");
        assertThat(memory.list("u1", MemoryCategory.KNOWLEDGE, 10)).isEmpty();
    }

    @Test
    @DisplayName("nobody edits or deletes an entry of somebody else's memory")
    void scopedToTheOwner() {
        String id = memory.remember("u1", "The ledger closes on Fridays.", MemoryCategory.KNOWLEDGE);

        assertThat(memory.edit("u2", id, "Rewritten.", null)).isEmpty();
        assertThat(memory.delete("u2", List.of(id))).isZero();
        assertThat(memory.read("u1", id)).map(MemoryEntry::text).contains("The ledger closes on Fridays.");
    }

    @Test
    @DisplayName("several entries are deleted at once, and an unknown id counts for nothing")
    void deletesSeveral() {
        String a = memory.remember("u1", "first", MemoryCategory.KNOWLEDGE);
        String b = memory.remember("u1", "second", MemoryCategory.USER);
        String kept = memory.remember("u1", "third", MemoryCategory.KNOWLEDGE);

        assertThat(memory.delete("u1", List.of(a, b, "no-such-id"))).isEqualTo(2);
        assertThat(memory.list("u1", null, 10)).extracting(MemoryEntry::id).containsExactly(kept);
    }
}
