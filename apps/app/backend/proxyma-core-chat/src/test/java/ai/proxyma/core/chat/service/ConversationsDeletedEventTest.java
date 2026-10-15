package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.event.ConversationsDeletedEvent;
import ai.proxyma.core.chat.model.Conversation;
import ai.proxyma.core.chat.repository.ConversationShareRepository;
import ai.proxyma.core.chat.repository.FeedbackRepository;
import ai.proxyma.core.guardrail.GuardrailService;
import ai.proxyma.core.provider.gateway.LlmGateway;
import ai.proxyma.core.settings.config.ProductIdentity;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import ai.proxyma.core.settings.service.McpGuardService;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.settings.usage.GlobalQuotaGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every way a conversation is deleted says so (ConversationsDeletedEvent), for what other modules
 * keep by its id - the key-account panel's binding, which otherwise outlived its conversation and
 * was reopened as an empty one. One delete (the history's, the API's, and the retention sweep's, which
 * deletes through the same method) and a user's whole account each publish it once the conversations
 * are gone; a delete that found nothing publishes nothing.
 */
class ConversationsDeletedEventTest {

    private SmartChatMemory memory;
    private ApplicationEventPublisher events;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        memory = mock(SmartChatMemory.class);
        events = mock(ApplicationEventPublisher.class);
        ToolCallbackProvider tools = mock(ToolCallbackProvider.class);
        when(tools.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        chatService = new ChatService(mock(LlmGateway.class), mock(SettingsService.class),
                mock(GlobalQuotaGate.class), mock(McpGuardService.class), memory,
                events, mock(ProductIdentity.class),
                mock(ConversationUsageRecorder.class), tools, mock(ChatSkillService.class),
                mock(ToolSkillGating.class), mock(SystemPromptService.class), mock(FeedbackRepository.class),
                mock(ConversationShareRepository.class), mock(ChatEventSink.class),
                mock(ConversationTitleService.class), mock(ModelPricingService.class),
                mock(SubAgentService.class), mock(WorkflowRunService.class), mock(WorkflowService.class),
                mock(WorkflowRunner.class), mock(GuardrailService.class));
    }

    @Test
    @DisplayName("deleting one conversation publishes its id")
    void oneConversation() {
        chatService.deleteConversation("conv-1");
        verify(memory).deleteConversation("conv-1");
        verify(events).publishEvent(new ConversationsDeletedEvent(List.of("conv-1")));
    }

    @Test
    @DisplayName("a conversation that was not there publishes nothing")
    void missingConversation() {
        doThrow(new NoSuchElementException("Conversation not found: gone")).when(memory).deleteConversation("gone");
        assertThatThrownBy(() -> chatService.deleteConversation("gone")).isInstanceOf(NoSuchElementException.class);
        verify(events, never()).publishEvent(any(ConversationsDeletedEvent.class));
    }

    @Test
    @DisplayName("deleting a user's conversations publishes every id, whatever surface each was held under")
    void aUsersConversations() {
        when(memory.listConversationsPaged(eq("alice"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(conversation("chat-1"), conversation("ka-1"))));
        chatService.deleteAllByUserId("alice");
        verify(memory).deleteAllByUserId("alice");
        verify(events).publishEvent(new ConversationsDeletedEvent(List.of("chat-1", "ka-1")));
    }

    @Test
    @DisplayName("a user with no conversations publishes nothing")
    void aUserWithNone() {
        when(memory.listConversationsPaged(eq("bob"), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        chatService.deleteAllByUserId("bob");
        verify(events, never()).publishEvent(any(ConversationsDeletedEvent.class));
    }

    private static Conversation conversation(String id) {
        Conversation c = new Conversation();
        c.setId(id);
        c.setUserId("alice");
        return c;
    }
}
