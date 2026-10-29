package ai.proxyma.core.tools;

import ai.proxyma.core.chat.module.AgentProfile;
import ai.proxyma.core.chat.module.AgentProfiles;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.service.ConnectorService;
import ai.proxyma.core.registry.service.SourceCollections;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.search.service.AgentMemoryService;
import ai.proxyma.core.search.service.AgentMemoryService.MemoryCategory;
import ai.proxyma.core.indexstore.BM25IndexService;
import ai.proxyma.core.search.service.SearchService;
import ai.proxyma.core.indexstore.VectorStoreService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The tools' half of a saved agent's profile (AgentProfile, Constellations): a search in its
 * conversation reads only the sources it was given, of those its person can read, and remember and
 * recall work on the AGENT's memory - kept apart from its person's and from every other agent's.
 * The keyword index is real (Lucene in a temp dir), so "apart" is two collections, not two mocks.
 */
class AgentProfileToolsTest {

    private static final String AGENT_CONVERSATION = "conv-agent";
    private static final String OTHER_AGENT_CONVERSATION = "conv-other-agent";

    @TempDir Path lucene;
    private final AgentProfiles profiles = mock(AgentProfiles.class);
    private final UUID jira = UUID.randomUUID();
    private final UUID wiki = UUID.randomUUID();

    private static RegisteredConnector source(UUID id, String name) {
        RegisteredConnector c = new RegisteredConnector();
        c.setId(id);
        c.setName(name);
        return c;
    }

    private static AgentProfile agent(Set<UUID> sources, String memoryKey) {
        return new AgentProfile(null, null, null, null, sources, memoryKey);
    }

    @BeforeEach
    void setUp() {
        when(profiles.of(anyString(), anyString())).thenReturn(Optional.empty());
        SearchContext.setUserId("ana");
    }

    @AfterEach
    void tearDown() {
        SearchContext.clear();
    }

    @Test
    @DisplayName("an agent's search reads its own sources, never one its person cannot read")
    void searchReadsTheAgentsSources() {
        SearchService search = mock(SearchService.class);
        ConnectorService connectors = mock(ConnectorService.class);
        when(connectors.listVisibleTo("ana")).thenReturn(List.of(source(jira, "PRX Jira"), source(wiki, "Wiki")));
        SearchTool tool = new SearchTool(search, connectors);
        tool.setAgentProfiles(profiles);
        UUID notVisible = UUID.randomUUID();
        when(profiles.of(AGENT_CONVERSATION, "ana")).thenReturn(Optional.of(agent(Set.of(jira, notVisible), null)));

        SearchContext.setConversationId(AGENT_CONVERSATION);
        tool.searchIndexedSources("blocker", 5, null, null);
        verify(search).search(eq(List.of(SourceCollections.of(jira))), anyString(), anyInt(), anyDouble());

        // The same person in an ordinary conversation searches everything they can read.
        SearchContext.setConversationId("conv-ordinary");
        tool.searchIndexedSources("blocker", 5, null, null);
        verify(search).search(eq(List.of(SourceCollections.of(jira), SourceCollections.of(wiki))),
                anyString(), anyInt(), anyDouble());
    }

    @Test
    @DisplayName("an agent given no source the person can read searches nothing, rather than everything")
    void noSourceIsNoSearch() {
        SearchService search = mock(SearchService.class);
        ConnectorService connectors = mock(ConnectorService.class);
        when(connectors.listVisibleTo("ana")).thenReturn(List.of(source(jira, "PRX Jira")));
        SearchTool tool = new SearchTool(search, connectors);
        tool.setAgentProfiles(profiles);
        when(profiles.of(AGENT_CONVERSATION, "ana")).thenReturn(Optional.of(agent(Set.of(), null)));
        SearchContext.setConversationId(AGENT_CONVERSATION);

        Map<String, Object> result = tool.searchIndexedSources("blocker", 5, null, null);
        assertThat(result.get("totalFound")).isEqualTo(0);
        org.mockito.Mockito.verifyNoInteractions(search);
    }

    @Test
    @DisplayName("a LOCKED profile remembers into nobody's memory and recalls nothing - never the person's")
    void lockedHasNoMemory() {
        VectorStoreService vectors = mock(VectorStoreService.class);
        when(vectors.search(anyList(), anyString(), anyInt(), anyDouble()))
                .thenThrow(new RuntimeException("embedding server unreachable"));
        AgentMemoryService memory = new AgentMemoryService(vectors, new BM25IndexService(lucene.toString(), null),
                mock(SearchService.class));
        RememberTool remember = new RememberTool(memory);
        remember.setAgentProfiles(profiles);
        RecallTool recall = new RecallTool(memory);
        recall.setAgentProfiles(profiles);

        SearchContext.setConversationId("conv-ordinary");
        assertThat(remember.remember("The person's own fact.", "knowledge", null, null)).containsEntry("success", true);

        when(profiles.of(AGENT_CONVERSATION, "ana")).thenReturn(Optional.of(AgentProfile.LOCKED));
        SearchContext.setConversationId(AGENT_CONVERSATION);
        assertThat(remember.remember("Written by a conversation that could not be described.", "knowledge", null, null))
                .containsEntry("success", false);
        assertThat(entries(recall)).as("the person's memory is not lent to it").isEmpty();
        assertThat(memory.list("ana", null, 10)).hasSize(1);
    }

    @Test
    @DisplayName("an agent remembers in its own memory: its person, and another agent, do not read it")
    void memoryIsTheAgentsOwn() {
        VectorStoreService vectors = mock(VectorStoreService.class);
        when(vectors.search(anyList(), anyString(), anyInt(), anyDouble()))
                .thenThrow(new RuntimeException("embedding server unreachable"));
        AgentMemoryService memory = new AgentMemoryService(vectors, new BM25IndexService(lucene.toString(), null),
                mock(SearchService.class));
        RememberTool remember = new RememberTool(memory);
        remember.setAgentProfiles(profiles);
        RecallTool recall = new RecallTool(memory);
        recall.setAgentProfiles(profiles);
        String agentKey = AgentMemoryService.agentKey("agent-1");
        when(profiles.of(AGENT_CONVERSATION, "ana")).thenReturn(Optional.of(agent(null, agentKey)));
        when(profiles.of(OTHER_AGENT_CONVERSATION, "ana"))
                .thenReturn(Optional.of(agent(null, AgentMemoryService.agentKey("agent-2"))));

        SearchContext.setConversationId(AGENT_CONVERSATION);
        assertThat(remember.remember("The 0.38 code freeze is 30 October.", "knowledge", null, null))
                .containsEntry("success", true);

        assertThat(memory.list(agentKey, MemoryCategory.KNOWLEDGE, 10)).hasSize(1);
        assertThat(memory.list("ana", null, 10)).as("the person's own memory").isEmpty();
        assertThat(entries(recall)).hasSize(1);

        SearchContext.setConversationId(OTHER_AGENT_CONVERSATION);
        assertThat(entries(recall)).as("another agent's memory").isEmpty();
        SearchContext.setConversationId("conv-ordinary");
        assertThat(entries(recall)).as("the person in an ordinary chat").isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> entries(RecallTool recall) {
        return (List<Object>) recall.recall(null, null, null, null).get("entries");
    }

    @Test
    @DisplayName("an agent's memory key is never a person's id")
    void agentKeys() {
        assertThat(AgentMemoryService.isAgentKey(AgentMemoryService.agentKey("a1"))).isTrue();
        assertThat(AgentMemoryService.isAgentKey(UUID.randomUUID().toString())).isFalse();
        assertThat(AgentMemoryService.isAgentKey(AgentMemoryService.LOCAL_USER_ID)).isFalse();
    }
}
