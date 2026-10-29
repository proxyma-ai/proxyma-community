package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.domain.PermissionMode;
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
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The composer's permission mode - Ask, Auto, Read-only - and what it may and may not do.
 *
 * <p>The mode travels in the chat request, so it is a CLIENT's word. That is safe only because it
 * narrows or skips the approval pause and never grants anything: Auto must still be refused by a
 * switch that is off, and Read-only must refuse a change before any prompt appears.
 */
class PermissionModeTest {

    private static final String CONV = "conv-1";
    private static final ToolCall WRITE = new ToolCall("call-1", "function", "createJiraIssue", "{}");
    private static final ToolCall OPEN_FILE = new ToolCall("call-2", "function", "openLocalFile", "{}");
    // A note saved into one of the user's own custom sources - a write, though no switch gates it.
    private static final ToolCall SAVE_NOTE = new ToolCall("call-3", "function", "saveToSource",
            "{\"sourceId\":\"6f1d2c3b-4a59-4e6f-8a7b-1c2d3e4f5a6b\",\"title\":\"Decision\",\"text\":\"We ship Friday.\"}");

    private McpGuardService guard;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        ToolCallbackProvider tools = mock(ToolCallbackProvider.class);
        when(tools.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        guard = mock(McpGuardService.class);
        chatService = new ChatService(mock(LlmGateway.class), mock(SettingsService.class),
                mock(GlobalQuotaGate.class), guard, mock(SmartChatMemory.class),
                mock(ApplicationEventPublisher.class), mock(ProductIdentity.class),
                mock(ConversationUsageRecorder.class), tools, mock(ChatSkillService.class),
                mock(ToolSkillGating.class), mock(SystemPromptService.class), mock(FeedbackRepository.class),
                mock(ConversationShareRepository.class), mock(ChatEventSink.class),
                mock(ConversationTitleService.class), mock(ModelPricingService.class),
                mock(WorkflowRunService.class), mock(WorkflowService.class),
                mock(WorkflowRunner.class), mock(GuardrailService.class));
    }

    // Aborted up front, so a call that reaches the approval pause returns at once as a denial
    // instead of waiting half an hour for a click - reaching it is what these tests observe.
    private String decide(ToolCall call) {
        return chatService.awaitApprovalIfNeeded(CONV, call, call.id(), new AtomicBoolean(true));
    }

    @Test
    @DisplayName("Ask pauses a change for approval - the default, with no mode sent at all")
    void askPauses() {
        assertThat(decide(WRITE)).isEqualTo("{\"success\":false,\"error\":\"" + ChatService.STOPPED_BEFORE_ANSWER + "\"}");
    }

    @Test
    @DisplayName("Auto lets a permitted change go ahead without the pause")
    void autoSkipsThePause() {
        chatService.setPermissionMode(CONV, PermissionMode.AUTO);
        assertThat(decide(WRITE)).isNull();
    }

    @Test
    @DisplayName("Auto grants nothing - a change the switches refuse is still refused")
    void autoGrantsNothing() {
        chatService.setPermissionMode(CONV, PermissionMode.AUTO);
        when(guard.checkWriteAllowed(any(), any())).thenReturn("Write is off for this connection.");

        assertThat(decide(WRITE)).contains("Write is off for this connection.");
    }

    @Test
    @DisplayName("Read-only refuses a change before any prompt, and says how to allow it")
    void readOnlyRefuses() {
        chatService.setPermissionMode(CONV, PermissionMode.READ_ONLY);
        assertThat(decide(WRITE)).isEqualTo(ChatService.READ_ONLY_REFUSAL);
    }

    @Test
    @DisplayName("Read-only still lets opening a local file ask - it changes nothing")
    void readOnlyStillOpensFiles() {
        chatService.setPermissionMode(CONV, PermissionMode.READ_ONLY);
        assertThat(decide(OPEN_FILE)).isEqualTo("{\"success\":false,\"error\":\"" + ChatService.STOPPED_BEFORE_ANSWER + "\"}");
    }

    @Test
    @DisplayName("saving a note into a custom source takes the same gate: Ask pauses it, Read-only refuses it, Auto lets it go")
    void savingANoteIsAWrite() {
        assertThat(decide(SAVE_NOTE)).isEqualTo("{\"success\":false,\"error\":\"" + ChatService.STOPPED_BEFORE_ANSWER + "\"}");
        chatService.setPermissionMode(CONV, PermissionMode.READ_ONLY);
        assertThat(decide(SAVE_NOTE)).isEqualTo(ChatService.READ_ONLY_REFUSAL);
        chatService.setPermissionMode(CONV, PermissionMode.AUTO);
        assertThat(decide(SAVE_NOTE)).isNull();
    }

    @Test
    @DisplayName("an unknown mode from a client is Ask, never something more permissive")
    void unknownModeIsAsk() {
        assertThat(PermissionMode.parse("yolo")).isEqualTo(PermissionMode.ASK);
        assertThat(PermissionMode.parse(null)).isEqualTo(PermissionMode.ASK);
        assertThat(PermissionMode.parse("read-only")).isEqualTo(PermissionMode.READ_ONLY);
    }
}
