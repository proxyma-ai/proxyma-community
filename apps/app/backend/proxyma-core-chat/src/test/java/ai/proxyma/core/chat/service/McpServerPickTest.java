package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.external.ExternalToolSource;
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
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An MCP server picked from the composer's + menu (2026-10-23) arrives as a skill mention named
 * "MCP: server" and is loaded as the agent's own readSkill would load it: the server's text into the
 * history once, its tools unlocked for the conversation. A name no external source owns is not this
 * path's, one this person may not use is dropped, and a server that cannot be reached does not fail
 * the turn.
 */
class McpServerPickTest {

    private static final String CONVERSATION = "conv-1";
    private static final String SERVER = "MCP: Tracker";

    private SmartChatMemory memory;
    private ExternalToolSource external;
    private ChatService chat;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        memory = mock(SmartChatMemory.class);
        conversation = new Conversation();
        conversation.setUnlockedSkillNames("");
        when(memory.findConversation(CONVERSATION)).thenReturn(Optional.of(conversation));
        external = mock(ExternalToolSource.class);
        when(external.owns(SERVER)).thenReturn(true);
        ToolCallbackProvider tools = mock(ToolCallbackProvider.class);
        when(tools.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        chat = new ChatService(mock(LlmGateway.class), mock(SettingsService.class),
                mock(GlobalQuotaGate.class), mock(McpGuardService.class), memory,
                mock(ApplicationEventPublisher.class), mock(ProductIdentity.class),
                mock(ConversationUsageRecorder.class), tools, mock(ChatSkillService.class),
                mock(ToolSkillGating.class), mock(SystemPromptService.class), mock(FeedbackRepository.class),
                mock(ConversationShareRepository.class), mock(ChatEventSink.class),
                mock(ConversationTitleService.class), mock(ModelPricingService.class),
                mock(WorkflowRunService.class), mock(WorkflowService.class),
                mock(WorkflowRunner.class), mock(GuardrailService.class));
        chat.setExternalTools(external);
    }

    @SuppressWarnings("unchecked")
    private List<Message> added() {
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(memory).add(eq(CONVERSATION), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("a picked server's text goes into the history and its tools are unlocked")
    void pickLoadsAndUnlocks() {
        when(external.readSkill(SERVER, "alice")).thenReturn(Optional.of("Tracker's tools: listIssues"));

        chat.applyPickedMcpServers(CONVERSATION, List.of(SERVER), "alice");

        assertThat(added()).hasSize(2);
        assertThat(added().get(0).getText()).contains(SERVER).contains("Tracker's tools: listIssues");
        verify(memory).setUnlockedSkillNames(CONVERSATION, SERVER);
    }

    @Test
    @DisplayName("a server already loaded costs a one-line pointer, not a second copy")
    void repickIsAReminder() {
        conversation.setUnlockedSkillNames(SERVER);

        chat.applyPickedMcpServers(CONVERSATION, List.of(SERVER), "alice");

        verify(external, never()).readSkill(anyString(), any());
        assertThat(added().get(0).getText()).contains("ALREADY LOADED").contains(SERVER);
    }

    @Test
    @DisplayName("a plain skill's name is not this path's, and one the person may not use is dropped")
    void notOwnedOrNotAllowed() {
        when(external.readSkill(SERVER, "bob")).thenReturn(Optional.empty());

        chat.applyPickedMcpServers(CONVERSATION, List.of("Release notes"), "alice");
        chat.applyPickedMcpServers(CONVERSATION, List.of(SERVER), "bob");

        verify(external, never()).readSkill(eq("Release notes"), any());
        verify(memory, never()).add(anyString(), anyList());
        verify(memory, never()).setUnlockedSkillNames(anyString(), anyString());
    }

    @Test
    @DisplayName("a server that cannot be reached does not fail the turn")
    void unreachableServer() {
        when(external.readSkill(SERVER, "alice")).thenThrow(new IllegalStateException("Connection refused"));

        chat.applyPickedMcpServers(CONVERSATION, List.of(SERVER), "alice");

        verify(memory, never()).add(anyString(), anyList());
    }
}
