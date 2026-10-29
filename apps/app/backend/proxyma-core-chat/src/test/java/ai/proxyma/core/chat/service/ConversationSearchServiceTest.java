package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.model.ChatMessage;
import ai.proxyma.core.chat.model.Conversation;
import ai.proxyma.core.chat.repository.ChatMessageRepository;
import ai.proxyma.core.chat.repository.ConversationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Who can search and read which earlier conversations - the history list's own rule - and how a
 * query becomes terms and a hit becomes an excerpt.
 */
class ConversationSearchServiceTest {

    private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    private final ConversationRepository conversations = mock(ConversationRepository.class);

    @Test
    void onEnterpriseServerSomeoneWithNoAccountSearchesNothing() {
        ConversationSearchService service = new ConversationSearchService(messages, conversations, true);
        assertTrue(service.search(null, "c0", "kafka", 5).isEmpty());
        assertTrue(service.read(null, "c1", 0).isEmpty());
        verifyNoInteractions(messages, conversations);
    }

    @Test
    void onEnterpriseServerTheSearchIsScopedToTheCaller() {
        ConversationSearchService service = new ConversationSearchService(messages, conversations, true);
        service.search("alice", "c0", "kafka", 5);
        verify(messages).searchContentOfUser(eq("alice"), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any());
        verify(messages, never()).searchContent(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any());
    }

    @Test
    @DisplayName("chat-agent-14: every term is searched in ONE statement per table, not one per term")
    void oneStatementForAllTerms() {
        ChatMessage hit = new ChatMessage();
        hit.setId(1L);
        hit.setConversationId("c1");
        hit.setRole(ai.proxyma.core.chat.domain.MessageRole.USER);
        hit.setContent("We chose seven days of Kafka retention.");
        Conversation c1 = new Conversation();
        c1.setId("c1");
        c1.setTitle("Topics");
        when(messages.searchContent(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any())).thenReturn(List.of(hit));
        when(conversations.findAllById(any())).thenReturn(List.of(c1));
        ConversationSearchService service = new ConversationSearchService(messages, conversations, false);

        List<ConversationSearchService.Hit> hits = service.search(null, "c0", "kafka retention lunch", 5);

        verify(messages, times(1)).searchContent(anyString(), eq("%kafka%"), eq("%retention%"), eq("%lunch%"),
                eq("%lunch%"), eq("%lunch%"), eq("%lunch%"), any());
        verify(conversations, times(1)).searchTitles(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any());
        assertEquals(1, hits.size());
        assertEquals(2, hits.get(0).termsMatched(), "the terms the row holds, worked out from what came back");
    }

    @Test
    @DisplayName("chat-agent-29: reading an earlier conversation gives what was said, not the product's scaffolding")
    void readLeavesScaffoldingOut() {
        Conversation c1 = new Conversation();
        c1.setId("c1");
        when(conversations.findById("c1")).thenReturn(Optional.of(c1));
        when(messages.findDisplayMessagesByConversationId("c1")).thenReturn(List.of(
                row(1, "What did we decide?"),
                row(2, "[Report from Search (agent 11111111-2222-3333-4444-555555555555)]\nthe report"),
                row(3, SmartChatMemory.skillBodyMessages("Jira", "use JQL").get(0).getText()),
                row(4, "Seven days.")));
        ConversationSearchService service = new ConversationSearchService(messages, conversations, false);

        List<ConversationSearchService.Message> read = service.read(null, "c1", 0).orElseThrow().messages();

        assertEquals(List.of("What did we decide?", "Seven days."),
                read.stream().map(ConversationSearchService.Message::text).toList());
    }

    private static ChatMessage row(long id, String content) {
        ChatMessage m = new ChatMessage();
        m.setId(id);
        m.setConversationId("c1");
        m.setRole(ai.proxyma.core.chat.domain.MessageRole.USER);
        m.setContent(content);
        return m;
    }

    @Test
    void someoneElsesConversationReadsAsNotThere() {
        Conversation bobs = new Conversation();
        bobs.setId("b1");
        bobs.setUserId("bob");
        when(conversations.findById("b1")).thenReturn(Optional.of(bobs));
        ConversationSearchService service = new ConversationSearchService(messages, conversations, true);

        assertTrue(service.read("alice", "b1", 0).isEmpty());
        verify(messages, never()).findDisplayMessagesByConversationId(anyString());
    }

    @Test
    void aQueryBecomesAFewDistinctTermsAndAPhraseStaysWhole() {
        assertEquals(List.of("kafka retention", "topic"),
                ConversationSearchService.terms("\"Kafka retention\" in TOPIC topic"));
        assertEquals(6, ConversationSearchService.terms("alpha bravo charlie delta echo foxtrot golf hotel").size());
    }

    @Test
    void anExcerptIsCutAroundTheFirstHit() {
        String text = "x ".repeat(300) + "the retention was seven days " + "y ".repeat(300);
        String excerpt = ConversationSearchService.excerpt(text, List.of("retention"));
        assertTrue(excerpt.contains("retention was seven days"), excerpt);
        assertTrue(excerpt.startsWith("...") && excerpt.endsWith("..."), excerpt);
        assertTrue(excerpt.length() < 260, "bounded: " + excerpt.length());
    }
}
