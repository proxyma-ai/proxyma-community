package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.domain.MessageRole;
import ai.proxyma.core.chat.model.ChatMessage;
import ai.proxyma.core.chat.repository.ChatMessageRepository;
import ai.proxyma.core.chat.repository.ConversationRepository;
import ai.proxyma.core.chat.repository.JpaChatMemoryRepository;
import ai.proxyma.core.provider.gateway.LlmGateway;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.settings.usage.ChatSpendService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Condensing a conversation changes what the MODEL reads and nothing the READER sees.
 *
 * <p>It used to delete every row and insert a summarized copy. After a reload everything before
 * the last exchange was gone from the transcript, and every message had a new id - so a rewind to
 * a message still carrying its old id matched nothing and deleted the whole conversation.
 */
class CondenseKeepsHistoryTest {

    private static final String CONV = "c-1";
    private static final Instant T0 = Instant.parse("2026-09-24T10:00:00Z");

    private ChatMessageRepository rows;
    private JpaChatMemoryRepository memory;
    private SmartChatMemory chatMemory;
    private final List<ChatMessage> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        rows = mock(ChatMessageRepository.class);
        when(rows.save(any())).thenAnswer(inv -> { saved.add(inv.getArgument(0)); return inv.getArgument(0); });
        memory = new JpaChatMemoryRepository(rows, new ObjectMapper());
        ConversationRepository conversations = mock(ConversationRepository.class);
        when(conversations.findById(anyString())).thenReturn(Optional.empty());
        chatMemory = new SmartChatMemory(memory, conversations, rows, mock(LlmGateway.class),
                mock(SettingsService.class), mock(ModelPricingService.class), mock(ChatSpendService.class),
                mock(ApplicationEventPublisher.class), mock(ChatEventSink.class),
                System.getProperty("java.io.tmpdir"), new ObjectMapper(), false);
    }

    private static ChatMessage row(long id, MessageRole role, String text, int second) {
        ChatMessage m = new ChatMessage();
        m.setId(id);
        m.setConversationId(CONV);
        m.setRole(role);
        m.setContent(text);
        m.setCreatedAt(T0.plusSeconds(second));
        return m;
    }

    @Test
    @DisplayName("condensing marks the folded rows and writes the summary at the start of the range - nothing is deleted")
    void condenseMarksRows() {
        List<ChatMessage> live = List.of(
                row(1, MessageRole.USER, "q1", 0), row(2, MessageRole.ASSISTANT, "a1", 1),
                row(3, MessageRole.USER, "q2", 2), row(4, MessageRole.ASSISTANT, "a2", 3),
                row(5, MessageRole.USER, "q3", 4));
        when(rows.findByConversationIdAndCondensedFalseOrderByCreatedAtAscIdAsc(CONV)).thenReturn(live);

        memory.condenseRange(CONV, 0, 3, List.of(new UserMessage("summary"), new AssistantMessage("ok")));

        verify(rows, never()).deleteByConversationId(anyString());
        assertTrue(live.subList(0, 4).stream().allMatch(ChatMessage::isCondensed), "the folded rows are marked");
        assertFalse(live.get(4).isCondensed(), "the last exchange is untouched");
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), live.stream().map(ChatMessage::getId).toList(), "no id changes");

        List<ChatMessage> summary = saved.stream().filter(ChatMessage::isSummary).toList();
        assertEquals(2, summary.size());
        assertEquals(T0, summary.get(0).getCreatedAt(), "the summary sits where the folded messages were");
        assertTrue(summary.get(1).getCreatedAt().isBefore(live.get(4).getCreatedAt()), "and before what follows");
        assertTrue(summary.get(1).getCreatedAt().isAfter(summary.get(0).getCreatedAt()),
                "a microsecond apart - the column keeps six digits, so nanoseconds would collapse");
    }

    @Test
    @DisplayName("a rewind to an id outside this conversation deletes nothing")
    void rewindRejectsAForeignId() {
        when(rows.findById(99L)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> chatMemory.rewindTo(CONV, 99L));

        verify(rows, never()).deleteFromMessage(anyString(), any(), any());
    }

    @Test
    @DisplayName("a rewind after a condensed range keeps the summary - it deletes by time, and the summary is older")
    void rewindAfterTheRangeKeepsTheSummary() {
        ChatMessage target = row(7, MessageRole.USER, "q3", 4);
        when(rows.findById(7L)).thenReturn(Optional.of(target));

        chatMemory.rewindTo(CONV, 7L);

        ArgumentCaptor<Instant> at = ArgumentCaptor.forClass(Instant.class);
        verify(rows).deleteFromMessage(any(), at.capture(), org.mockito.ArgumentMatchers.eq(7L));
        assertEquals(target.getCreatedAt(), at.getValue());
        verify(rows, never()).deleteByConversationIdAndSummaryTrue(anyString());
        verify(rows, never()).restoreCondensed(anyString());
    }

    @Test
    @DisplayName("a rewind INTO a condensed range drops the summary and gives the model the plain history back")
    void rewindIntoTheRangeUndoesTheCondensation() {
        ChatMessage target = row(3, MessageRole.USER, "q2", 2);
        target.setCondensed(true);
        when(rows.findById(3L)).thenReturn(Optional.of(target));

        chatMemory.rewindTo(CONV, 3L);

        verify(rows).deleteFromMessage(CONV, target.getCreatedAt(), 3L);
        verify(rows, times(1)).deleteByConversationIdAndSummaryTrue(CONV);
        verify(rows, times(1)).restoreCondensed(CONV);
        verify(rows, never()).deleteById(anyLong());
    }
}
