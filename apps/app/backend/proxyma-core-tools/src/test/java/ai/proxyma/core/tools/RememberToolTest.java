package ai.proxyma.core.tools;

import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.indexstore.VectorStoreService;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.search.service.AgentMemoryService;
import ai.proxyma.core.search.service.AgentMemoryService.MemoryCategory;
import ai.proxyma.core.search.service.AgentMemoryService.MemoryEntry;
import ai.proxyma.core.search.service.SearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The agent corrects and deletes its own memory (maintainer's 0.36.2 feedback: "the agent should be
 * able to update an item in the memory"). Before, `remember` could only add, so a correction worded
 * differently from the entry it corrected was stored beside it. The keyword index is real (Lucene in
 * a temp dir); the dense search throws, so the dedup guard never runs and cannot be what replaced
 * anything here.
 */
class RememberToolTest {

    @TempDir Path lucene;
    private VectorStoreService vectors;
    private AgentMemoryService memory;
    private RememberTool tool;

    @BeforeEach
    void setUp() {
        vectors = mock(VectorStoreService.class);
        when(vectors.search(anyList(), anyString(), anyInt(), anyDouble()))
                .thenThrow(new RuntimeException("embedding server unreachable"));
        memory = new AgentMemoryService(vectors, new BM25IndexService(lucene.toString(), null), mock(SearchService.class));
        tool = new RememberTool(memory);
    }

    @AfterEach
    void tearDown() {
        SearchContext.clear();
    }

    private String user() {
        return AgentMemoryService.LOCAL_USER_ID;
    }

    @Test
    @DisplayName("with an id, the fact replaces that entry in its place and keeps its category")
    void anIdReplacesTheEntry() {
        String id = memory.remember(user(), "The release train leaves on Thursdays.", MemoryCategory.KNOWLEDGE);
        String other = memory.remember(user(), "Prefers short answers.", MemoryCategory.USER);

        Map<String, Object> result = tool.remember("The release train leaves on Tuesdays since October.", null, id, null);

        assertThat(result).containsEntry("success", true).containsEntry("id", id);
        List<MemoryEntry> all = memory.list(user(), null, 10);
        assertThat(all).extracting(MemoryEntry::id).containsExactlyInAnyOrder(id, other);
        assertThat(memory.read(user(), id)).map(MemoryEntry::text)
                .contains("The release train leaves on Tuesdays since October.");
        assertThat(memory.read(user(), id)).map(MemoryEntry::category).contains("KNOWLEDGE");
    }

    @Test
    @DisplayName("an edit can move an entry to the other category, its id saying so")
    void anEditCanRecategorize() {
        String id = memory.remember(user(), "Works on the billing team.", MemoryCategory.KNOWLEDGE);

        Map<String, Object> result = tool.remember("Works on the billing team.", "user", id, null);

        String newId = (String) result.get("id");
        assertThat(memory.read(user(), newId)).map(MemoryEntry::category).contains("USER");
        assertThat(memory.read(user(), id)).isEmpty();
    }

    @Test
    @DisplayName("forget with an id deletes that entry and nothing else")
    void forgetDeletes() {
        String id = memory.remember(user(), "The staging host is stg-02.", MemoryCategory.KNOWLEDGE);
        String kept = memory.remember(user(), "The production host is prd-01.", MemoryCategory.KNOWLEDGE);

        assertThat(tool.remember(null, null, id, true)).containsEntry("success", true).doesNotContainKey("id");
        assertThat(memory.list(user(), null, 10)).extracting(MemoryEntry::id).containsExactly(kept);
    }

    @Test
    @DisplayName("forget without an id, or an id that names nothing, is refused and changes nothing")
    void forgetNeedsARealId() {
        String id = memory.remember(user(), "A fact.", MemoryCategory.KNOWLEDGE);

        assertThat(tool.remember(null, null, null, true)).containsEntry("success", false);
        assertThat(tool.remember(null, null, "no-such-id", true)).containsEntry("success", false);
        assertThat(tool.remember("Something new.", null, "no-such-id", null)).containsEntry("success", false);
        assertThat(memory.list(user(), null, 10)).extracting(MemoryEntry::id).containsExactly(id);
    }

    @Test
    @DisplayName("another user's entry can be neither edited nor deleted")
    void anotherUsersEntryIsOutOfReach() {
        String theirs = memory.remember("u2", "u2's own fact.", MemoryCategory.KNOWLEDGE);
        SearchContext.setUserId("u1");

        assertThat(tool.remember("Overwritten.", null, theirs, null)).containsEntry("success", false);
        assertThat(tool.remember(null, null, theirs, true)).containsEntry("success", false);
        assertThat(memory.read("u2", theirs)).map(MemoryEntry::text).contains("u2's own fact.");
    }

    @Test
    @DisplayName("without an id it still adds, and a blank fact is still refused")
    void withoutAnIdItAdds() {
        Map<String, Object> result = tool.remember("A new fact.", null, null, null);
        assertThat(result).containsEntry("success", true).containsKey("id");
        assertThat(tool.remember("  ", null, null, null)).containsEntry("success", false);
        assertThat(memory.list(user(), null, 10)).hasSize(1);
    }

    @Test
    @DisplayName("an agent's edit records the conversation it was made in")
    @SuppressWarnings("unchecked")
    void anEditRecordsItsConversation() {
        String id = memory.remember(user(), "Old wording.", MemoryCategory.KNOWLEDGE);
        SearchContext.setConversationId("conv-7");

        tool.remember("New wording.", null, id, null);

        ArgumentCaptor<List<Document>> docs = ArgumentCaptor.forClass(List.class);
        verify(vectors, atLeastOnce()).upsert(anyString(), docs.capture());
        Document last = docs.getValue().get(0);
        assertThat(last.getText()).isEqualTo("New wording.");
        assertThat(last.getMetadata()).containsEntry("conversationId", "conv-7");
    }
}
