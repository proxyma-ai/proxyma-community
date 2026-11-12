package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.module.ChatModule;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A MODULE'S TOOL THAT WAITS FOR ANOTHER AGENT (feedback fdc55060, 2026-11-02: a question to a
 * Constellations project's lead from the main chat came back "in a few minutes" and never showed). The
 * module says how long the call may run, past the chat's minute; a call the chat gives up is reported to
 * the module, so its wait takes nothing; and an answer still to come is background work, so the turn's end
 * says more is coming and the page keeps following the conversation until it does.
 */
class ModuleWaitsTest {

    static final String CONV = "conv-main";
    static final String TOOL = "messageProjectLead";

    ChatService chatService;
    ChatModule module;

    @BeforeEach
    void setUp() {
        ToolCallbackProvider tools = mock(ToolCallbackProvider.class);
        when(tools.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        chatService = new ChatService(mock(LlmGateway.class), mock(SettingsService.class),
                mock(GlobalQuotaGate.class), mock(McpGuardService.class), mock(SmartChatMemory.class),
                mock(ApplicationEventPublisher.class), mock(ProductIdentity.class),
                mock(ConversationUsageRecorder.class), tools, mock(ChatSkillService.class),
                mock(ToolSkillGating.class), mock(SystemPromptService.class), mock(FeedbackRepository.class),
                mock(ConversationShareRepository.class), mock(ChatEventSink.class),
                mock(ConversationTitleService.class), mock(ModelPricingService.class),
                mock(WorkflowRunService.class), mock(WorkflowService.class),
                mock(WorkflowRunner.class), mock(GuardrailService.class));
        module = mock(ChatModule.class);
        when(module.toolTimeoutSeconds(TOOL)).thenReturn(150);
        chatService.setChatModules(List.of(module));
    }

    @Test
    @DisplayName("a module's waiting tool runs as long as the module says; every other tool keeps the chat's minute")
    void timeoutFromTheModule() {
        assertThat(chatService.toolTimeoutSeconds(TOOL)).isEqualTo(150);
        assertThat(chatService.toolTimeoutSeconds("readWorkspaceFile")).isEqualTo(ChatService.TOOL_CALL_TIMEOUT_SECONDS);

        ChatModule failing = mock(ChatModule.class);
        when(failing.toolTimeoutSeconds(anyString())).thenThrow(new IllegalStateException("down"));
        chatService.setChatModules(List.of(failing, module));
        assertThat(chatService.toolTimeoutSeconds(TOOL)).as("a module that fails costs only its say").isEqualTo(150);
    }

    @Test
    @DisplayName("a call given up is reported to every module, one that throws included")
    void givenUpReachesTheModule() {
        ChatModule failing = mock(ChatModule.class);
        doThrow(new IllegalStateException("down")).when(failing).toolAbandoned(CONV, TOOL);
        chatService.setChatModules(List.of(failing, module));
        chatService.toolGivenUp(CONV, TOOL);
        verify(module).toolAbandoned(CONV, TOOL);
    }

    @Test
    @DisplayName("an answer a module still owes the conversation is background work, so its stream stays followed")
    void answerOwedIsBackgroundWork() {
        assertThat(chatService.hasBackgroundWork(CONV)).isFalse();
        when(module.awaitsAnswer(CONV)).thenReturn(true);
        assertThat(chatService.hasBackgroundWork(CONV)).isTrue();
        assertThat(chatService.isBusy(CONV)).isTrue();
        assertThat(chatService.hasBackgroundWork("conv-other")).isFalse();
    }
}
