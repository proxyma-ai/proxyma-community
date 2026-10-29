package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.domain.MessageRole;
import ai.proxyma.core.chat.dto.ChatMessageResponse;
import ai.proxyma.core.chat.dto.ChatMessageResponse.ToolCallResponse;
import ai.proxyma.core.chat.model.ChatMessage;
import ai.proxyma.core.chat.model.Conversation;
import ai.proxyma.core.chat.model.ConversationShare;
import ai.proxyma.core.chat.model.AgentNode;
import ai.proxyma.core.chat.repository.ChatMessageRepository;
import ai.proxyma.core.chat.repository.ConversationRepository;
import ai.proxyma.core.chat.repository.ConversationShareRepository;
import ai.proxyma.core.chat.repository.JpaChatMemoryRepository;
import ai.proxyma.core.chat.repository.AgentNodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A recorded run written back as rows the product reads as its own.
 *
 * <p>The sandbox's example conversation is the transcript of a real run, exported through the REST
 * API and imported at startup. What can go wrong is quiet: rows the transcript reads differently
 * from the run (a tool row lost, a call attached to the wrong message), a sub-agent panel that
 * never appears because its {@code delegate} result names an id no row has, and a share link that
 * stops short of the answer it exists to show. Each is pinned here by reading the rebuilt rows back
 * through {@link ChatService#transcript} - the same code the shared page is served from.
 */
class ExampleConversationImporterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // Recorded times: the run started at T and ended at T + 600 s.
    private static final long T = 1_759_650_000_000L;
    private static final String RECORDED_A = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String RECORDED_B = "bbbbbbbb-0000-0000-0000-000000000002";
    // What the import writes them under, and their conversations. Hex, like a real id: the report row
    // names its agents in a form only a hex id matches (AgentInbox.entriesIn).
    private static final String NEW_1 = "11111111-0000-0000-0000-00000000000a";
    private static final String NEW_2 = "22222222-0000-0000-0000-00000000000b";
    private static final String CONV_1 = "33333333-0000-0000-0000-00000000000c";
    private static final String CONV_2 = "44444444-0000-0000-0000-00000000000d";
    private static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");

    private static String document() throws Exception {
        Map<String, Object> doc = Map.of(
                "format", "proxyma-conversation",
                "version", 1,
                "title", "FOSP-SA-2026-07 response",
                "createdAt", T,
                "updatedAt", T + 600_000,
                "usage", Map.of("inputTokens", 120_000, "outputTokens", 9_000, "costUsd", 0.42),
                "messages", List.of(
                        Map.of("role", "user", "content", "We just received the CERT notice.",
                                "skills", List.of("CRA vulnerability response"), "at", T),
                        Map.of("role", "assistant", "content", "Two sub-agents are scoping GW-20 and DX-600.",
                                "at", T + 60_000,
                                "toolCalls", List.of(
                                        call("s1", "searchIndexedSources", "{\"query\":\"fbstack\"}",
                                                "{\"success\":true,\"results\":[],\"totalFound\":0}", T + 1_000, T + 3_000, null),
                                        call("d1", "delegate", "{\"title\":\"GW-20\",\"task\":\"Scope GW-20.\"}",
                                                "{\"success\":true,\"status\":\"RUNNING\",\"agentId\":\"" + RECORDED_A + "\"}",
                                                T + 4_000, T + 5_000, RECORDED_A),
                                        call("d2", "delegate", "{\"title\":\"DX-600\"}",
                                                "{\"success\":true,\"status\":\"RUNNING\",\"agentId\":\"" + RECORDED_B + "\"}",
                                                T + 4_000, T + 5_500, RECORDED_B))),
                        Map.of("role", "reports", "at", T + 400_000, "agents", List.of(RECORDED_A, RECORDED_B)),
                        Map.of("role", "assistant", "content", "## Verdict table\n\nGW-20 2.3.0 is AFFECTED.",
                                "at", T + 600_000)),
                "subAgents", List.of(
                        agent(RECORDED_A, "GW-20", T + 4_000, T + 300_000),
                        agent(RECORDED_B, "DX-600", T + 4_000, T + 390_000)));
        return JSON.writeValueAsString(doc);
    }

    private static Map<String, Object> call(String id, String name, String args, String result, long start, long end,
                                            String agentId) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("arguments", args);
        m.put("result", result);
        m.put("done", true);
        m.put("startedAt", start);
        m.put("endedAt", end);
        if (agentId != null) m.put("agentId", agentId);
        return m;
    }

    private static Map<String, Object> agent(String id, String title, long start, long end) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("mode", "async");
        m.put("status", "completed");
        m.put("startedAt", start);
        m.put("endedAt", end);
        m.put("report", title + " is affected.");
        m.put("items", List.of(Map.of("kind", "tool", "callId", "x1", "toolName", "searchIndexedSources",
                "arguments", "{}", "result", "{}", "done", true, "startedAt", start + 1_000, "endedAt", start + 2_000)));
        m.put("provider", "Demo");
        m.put("model", "m");
        m.put("inputTokens", 50_000);
        m.put("costUsd", 0.1);
        return m;
    }

    private static ExampleConversationImporter.Plan plan() throws Exception {
        Iterator<String> ids = List.of(NEW_1, NEW_2, CONV_1, CONV_2).iterator();
        return ExampleConversationImporter.plan(ExampleConversationImporter.read(document()), "conv-1", "owner-1",
                NOW, ids::next);
    }

    // The rows with ids, as the database would hand them back.
    private static List<ChatMessage> withIds(List<ChatMessage> rows) {
        long id = 100;
        for (ChatMessage row : rows) row.setId(id++);
        return rows;
    }

    @Test
    @DisplayName("the rebuilt rows read back as the recorded transcript: the skill chip, every tool row, the report, the answer")
    void rowsReadBackAsTheTranscript() throws Exception {
        var plan = plan();
        List<ChatMessage> rows = withIds(plan.rows());

        List<ChatMessageResponse> transcript = ChatService.transcript(rows, Map.of(), null);

        assertThat(transcript).hasSize(4);
        assertThat(transcript.get(0).role()).isEqualTo(MessageRole.USER);
        assertThat(transcript.get(0).content()).isEqualTo("We just received the CERT notice.");
        assertThat(transcript.get(0).skillMentions()).containsExactly("CRA vulnerability response");

        ChatMessageResponse running = transcript.get(1);
        assertThat(running.content()).startsWith("Two sub-agents");
        assertThat(running.toolCalls()).extracting(ToolCallResponse::toolName)
                .containsExactly("searchIndexedSources", "deployAgent", "deployAgent");
        assertThat(running.toolCalls()).allMatch(ToolCallResponse::done);
        // Durations survive: the transcript reads them from the TOOL row's toolTimings.
        ToolCallResponse search = running.toolCalls().get(0);
        assertThat(search.endedAt() - search.startedAt()).isEqualTo(2_000);

        assertThat(transcript.get(2).agentMail()).extracting(ChatMessageResponse.AgentMail::agentId)
                .containsExactly(NEW_1, NEW_2);
        assertThat(transcript.get(3).content()).startsWith("## Verdict table");
        assertThat(rows.get(plan.finalAnswerIndex()).getContent()).startsWith("## Verdict table");
    }

    @Test
    @DisplayName("a recorded sub-agent is a finished deployed agent: its call names it and its conversation, under new ids")
    void delegateBecomesADeployedAgent() throws Exception {
        var plan = plan();
        List<ChatMessage> rows = withIds(plan.rows());

        List<String> results = new ArrayList<>();
        for (ChatMessage r : rows) if (r.getToolResponsesJson() != null) results.add(r.getToolResponsesJson());
        assertThat(String.join("", results)).contains(NEW_1, CONV_1, NEW_2, CONV_2)
                .doesNotContain(RECORDED_A).doesNotContain(RECORDED_B);

        ExampleConversationImporter.Agent gw = plan.subAgents().get(0);
        AgentNode n = gw.node();
        assertThat(n.getId()).hasToString(NEW_1);
        assertThat(n.getConversationId()).isEqualTo(CONV_1);
        assertThat(n.getParentConversationId()).isEqualTo("conv-1");
        assertThat(n.getRootConversationId()).isEqualTo("conv-1");
        assertThat(n.getDepth()).isEqualTo(1);
        assertThat(n.getStatus()).isEqualTo(AgentNode.DONE);
        assertThat(n.isReportOwed()).isFalse();
        assertThat(n.getLastReport()).isEqualTo("GW-20 is affected.");
        assertThat(n.getTask()).isEqualTo("Scope GW-20.");
        assertThat(gw.conversation().getSurface()).isEqualTo(ai.proxyma.core.chat.agents.AgentTree.SURFACE);
        assertThat(gw.conversation().getUserId()).isEqualTo("owner-1");
        assertThat(gw.rows()).extracting(ChatMessage::getContent).containsExactly("Scope GW-20.", "GW-20 is affected.");
        assertThat(plan.subAgents().get(1).node().getTask()).as("a call with no task is titled").isEqualTo("DX-600");
    }

    @Test
    @DisplayName("every time moves by one amount, so the run ended ten minutes before the import")
    void timesAreShiftedToRecent() throws Exception {
        var plan = plan();
        long shift = NOW.toEpochMilli() - ExampleConversationImporter.ENDED_BEFORE_IMPORT_MS - (T + 600_000);

        assertThat(plan.conversation().getUpdatedAt()).isEqualTo(NOW.minusMillis(ExampleConversationImporter.ENDED_BEFORE_IMPORT_MS));
        assertThat(plan.conversation().getCreatedAt().toEpochMilli()).isEqualTo(T + shift);
        assertThat(plan.rows()).isSortedAccordingTo(Comparator.comparing(ChatMessage::getCreatedAt));
        assertThat(plan.rows().stream().map(ChatMessage::getCreatedAt).distinct().count()).isEqualTo(plan.rows().size());
        ChatMessage toolRow = plan.rows().stream().filter(r -> r.getRole() == MessageRole.TOOL).findFirst().orElseThrow();
        assertThat(JpaChatMemoryRepository.readToolTimings(toolRow.getMetadataJson()).get("s1"))
                .containsExactly(T + 1_000 + shift, T + 3_000 + shift);
        AgentNode gw = plan.subAgents().get(0).node();
        assertThat(gw.getEndedAt().toEpochMilli()).isEqualTo(T + 300_000 + shift);
        assertThat(gw.getCreatedAt().toEpochMilli()).isEqualTo(T + 4_000 + shift);
    }

    @Test
    @DisplayName("the import writes the rows, pins the conversation to its owner, and shares it up to the final answer")
    void importWritesRowsAndShare() throws Exception {
        ConversationRepository conversations = mock(ConversationRepository.class);
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        AgentNodeRepository runs = mock(AgentNodeRepository.class);
        ConversationShareRepository shares = mock(ConversationShareRepository.class);
        when(shares.findById("cra-example-run")).thenReturn(Optional.empty());
        AtomicLong ids = new AtomicLong(500);
        List<ChatMessage> saved = new ArrayList<>();
        when(messages.save(any(ChatMessage.class))).thenAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            m.setId(ids.incrementAndGet());
            saved.add(m);
            return m;
        });
        var importer = new ExampleConversationImporter(conversations, messages, runs, shares);

        var imported = importer.importConversation(document(), "owner-1", "Admin", "cra-example-run", NOW).orElseThrow();

        ArgumentCaptor<Conversation> conversation = ArgumentCaptor.forClass(Conversation.class);
        verify(conversations, org.mockito.Mockito.times(3)).save(conversation.capture());
        // The conversation first, then each agent's own.
        Conversation main = conversation.getAllValues().get(0);
        assertThat(conversation.getAllValues().subList(1, 3)).allMatch(c -> "agent".equals(c.getSurface()));
        assertThat(main.getUserId()).isEqualTo("owner-1");
        assertThat(main.isPinned()).isTrue();
        assertThat(main.getEstimatedCostUsd()).isEqualTo(0.42);

        ArgumentCaptor<ConversationShare> share = ArgumentCaptor.forClass(ConversationShare.class);
        verify(shares).save(share.capture());
        assertThat(share.getValue().getToken()).isEqualTo("cra-example-run");
        assertThat(share.getValue().getConversationId()).isEqualTo(main.getId());
        // The conversation's own rows - its agents' conversations are written after them.
        List<ChatMessage> own = saved.stream().filter(m -> main.getId().equals(m.getConversationId())).toList();
        assertThat(saved.size() - own.size()).as("two rows for each agent's conversation").isEqualTo(4);
        ChatMessage last = own.stream().filter(m -> m.getRole() == MessageRole.ASSISTANT
                && m.getContent() != null && !m.getContent().isBlank()).reduce((a, b) -> b).orElseThrow();
        assertThat(share.getValue().getUptoMessageId()).isEqualTo(last.getId()).isEqualTo(imported.sharedUptoMessageId());
        // Covering the final answer is what makes the shared page show all of it: the page keeps
        // every message whose id is at or below the share's.
        assertThat(own).allMatch(m -> m.getId() <= share.getValue().getUptoMessageId());

        verify(runs, org.mockito.Mockito.times(2)).save(any(AgentNode.class));
    }

    @Test
    @DisplayName("a share token already taken means it was imported before: nothing is written")
    void skippedWhenTheShareExists() throws Exception {
        ConversationRepository conversations = mock(ConversationRepository.class);
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        AgentNodeRepository runs = mock(AgentNodeRepository.class);
        ConversationShareRepository shares = mock(ConversationShareRepository.class);
        when(shares.findById(anyString())).thenReturn(Optional.of(new ConversationShare()));
        var importer = new ExampleConversationImporter(conversations, messages, runs, shares);

        assertThat(importer.importConversation(document(), "owner-1", "Admin", "cra-example-run", NOW)).isEmpty();
        verify(conversations, never()).save(any());
        verify(messages, never()).save(any());
        verify(shares, never()).save(any());
    }

    @Test
    @DisplayName("a document of another format or version is refused, not half-imported")
    void foreignDocumentsAreRefused() {
        assertThatThrownBy(() -> ExampleConversationImporter.read("{\"format\":\"other\",\"version\":1,\"messages\":[{}]}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExampleConversationImporter.read(
                "{\"format\":\"proxyma-conversation\",\"version\":2,\"messages\":[{}]}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExampleConversationImporter.read("not json"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
