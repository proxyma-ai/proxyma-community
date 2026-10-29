package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.domain.MessageRole;
import ai.proxyma.core.chat.model.ChatMessage;
import ai.proxyma.core.chat.repository.ChatMessageRepository;
import ai.proxyma.core.chat.repository.ConversationShareRepository;
import ai.proxyma.core.chat.repository.FeedbackRepository;
import ai.proxyma.core.chat.repository.JpaChatMemoryRepository;
import ai.proxyma.core.guardrail.GuardrailService;
import ai.proxyma.core.provider.gateway.LlmGateway;
import ai.proxyma.core.settings.config.ProductIdentity;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import ai.proxyma.core.settings.service.McpGuardService;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.settings.usage.GlobalQuotaGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A conversation is not left under the instructions of the version that started it.
 *
 * <p>The system message was written once, with the first message of a conversation, and never
 * again. An install updated to a build with a new prompt went on running every conversation it
 * already had under the old one - reported by the maintainer the day 0.24.0 shipped, on two
 * conversations started under 0.22.
 */
class SystemPromptRefreshTest {

    private static final String CONVERSATION = "conv-1";

    /** What a build two versions back opened a conversation with. */
    private static final String OLD = "You are Proxyma, an AI agent.\n\nDELEGATION - your default: hand work to a sub-agent."
            + SystemPromptService.DATE_LEAD + "2026-09-28 (Monday).";

    private SmartChatMemory memory;
    private ChatSkillService skills;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        memory = mock(SmartChatMemory.class);
        when(memory.replaceSystemPrompt(anyString(), anyString())).thenReturn(true);
        ToolCallbackProvider tools = mock(ToolCallbackProvider.class);
        when(tools.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        ProductIdentity identity = mock(ProductIdentity.class);
        when(identity.getProductName()).thenReturn("Proxyma");
        SystemPromptService prompts = mock(SystemPromptService.class);
        when(prompts.read()).thenReturn(SystemPromptService.DEFAULT);
        skills = mock(ChatSkillService.class);
        when(skills.formatForSystemPrompt(any())).thenReturn("SKILLS - one line each.");
        GuardrailService guardrails = mock(GuardrailService.class);
        when(guardrails.canaryLine()).thenReturn("");
        chatService = new ChatService(mock(LlmGateway.class), mock(SettingsService.class),
                mock(GlobalQuotaGate.class), mock(McpGuardService.class), memory,
                mock(ApplicationEventPublisher.class), identity,
                mock(ConversationUsageRecorder.class), tools, skills,
                mock(ToolSkillGating.class), prompts, mock(FeedbackRepository.class),
                mock(ConversationShareRepository.class), mock(ChatEventSink.class),
                mock(ConversationTitleService.class), mock(ModelPricingService.class),
                mock(WorkflowRunService.class), mock(WorkflowService.class),
                mock(WorkflowRunner.class), guardrails);
    }

    private static List<Message> history(String systemMessage) {
        return List.of(new SystemMessage(systemMessage), new UserMessage("what changed last week?"));
    }

    private String written() {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(memory).replaceSystemPrompt(eq(CONVERSATION), text.capture());
        return text.getValue();
    }

    @Test
    @DisplayName("a conversation started under an earlier version's prompt is given the current one on its next turn")
    void anOlderPromptIsReplaced() {

        chatService.refreshSystemPrompt(CONVERSATION, history(OLD), null, "u-1");

        assertThat(written())
                .startsWith("You are Proxyma, an AI agent that works across")
                .contains("HOW YOU WORK - you lead, the agents you deploy do the work.")
                .doesNotContain("DELEGATION - your default")
                .contains("SKILLS - one line each.")
                .endsWith(SystemPromptService.DELEGATION_REMINDER);
    }

    @Test
    @DisplayName("a conversation already under the current prompt is left alone, turn after turn")
    void theCurrentPromptIsNotRewritten() {
        chatService.refreshSystemPrompt(CONVERSATION, history(OLD), null, "u-1");
        String current = written();

        chatService.refreshSystemPrompt(CONVERSATION, history(current), null, "u-1");
        chatService.refreshSystemPrompt(CONVERSATION, history(current), null, "u-1");

        // Once, for the old one. A rewrite on every turn would cost the provider's cached prefix
        // on every turn.
        verify(memory, times(1)).replaceSystemPrompt(anyString(), anyString());
    }

    @Test
    @DisplayName("a conversation whose first message is not a system message is not touched")
    void noSystemMessageNothingWritten() {
        chatService.refreshSystemPrompt(CONVERSATION, List.of(new UserMessage("hello")), null, "u-1");
        chatService.refreshSystemPrompt(CONVERSATION, List.of(), null, "u-1");

        verify(memory, never()).replaceSystemPrompt(anyString(), anyString());
    }

    @Test
    @DisplayName("a skill added or reworded since the conversation started reaches it on its next turn")
    void aChangedSkillListIsNoticed() {
        chatService.refreshSystemPrompt(CONVERSATION, history(OLD), null, "u-1");
        String current = written();

        when(skills.formatForSystemPrompt(any())).thenReturn("SKILLS - one line each, and one more.");
        chatService.refreshSystemPrompt("conv-2", history(current), null, "u-1");

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(memory).replaceSystemPrompt(eq("conv-2"), text.capture());
        assertThat(text.getValue()).contains("and one more.");
    }

    @Test
    @DisplayName("a new day alone is not a change - the date is the one line that does not count")
    void theDateDoesNotCount() {
        String body = "You are Proxyma.";
        String rest = "\n\nSKILLS - one line each.";

        assertThat(SystemPromptService.sameButForDate(
                body + SystemPromptService.DATE_LEAD + "2026-09-28 (Monday)." + rest,
                body + SystemPromptService.DATE_LEAD + "2026-10-03 (Saturday)." + rest)).isTrue();
        assertThat(SystemPromptService.sameButForDate(
                body + SystemPromptService.DATE_LEAD + "2026-09-28 (Monday)." + rest,
                body + SystemPromptService.DATE_LEAD + "2026-09-28 (Monday)." + rest + " And one more.")).isFalse();
        assertThat(SystemPromptService.sameButForDate(
                body + " Be brief." + SystemPromptService.DATE_LEAD + "2026-09-28 (Monday)." + rest,
                body + SystemPromptService.DATE_LEAD + "2026-09-28 (Monday)." + rest)).isFalse();
        assertThat(SystemPromptService.sameButForDate(null, body)).isFalse();
    }

    @Test
    @DisplayName("Desktop and Home Server run the built-in prompt whatever a saved file says, and never write one")
    void aSingleUserEditionHasNoPromptOfItsOwn(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path file = dir.resolve("system-prompt-override.md");
        java.nio.file.Files.writeString(file, "You are an assistant from three versions ago.");

        SystemPromptService desktop = new SystemPromptService(file.toString(), false);
        assertThat(desktop.read()).isEqualTo(SystemPromptService.DEFAULT);
        assertThat(desktop.readSaved()).isEmpty();
        desktop.write("You are whatever an imported file says.");
        assertThat(java.nio.file.Files.readString(file)).isEqualTo("You are an assistant from three versions ago.");

        // Enterprise Server is where a super admin writes one, and there it stands.
        SystemPromptService server = new SystemPromptService(file.toString(), true);
        assertThat(server.read()).isEqualTo("You are an assistant from three versions ago.");
    }

    @Test
    @DisplayName("the row is rewritten in place - same id, same position - and only when it is the system message")
    void theRowIsRewrittenInPlace() {
        ChatMessageRepository rows = mock(ChatMessageRepository.class);
        JpaChatMemoryRepository repository = new JpaChatMemoryRepository(rows, new ObjectMapper());
        ChatMessage system = new ChatMessage();
        system.setId(7L);
        system.setConversationId(CONVERSATION);
        system.setRole(MessageRole.SYSTEM);
        system.setContent(OLD);
        when(rows.findFirstByConversationIdAndCondensedFalseOrderByCreatedAtAscIdAsc(CONVERSATION))
                .thenReturn(Optional.of(system));

        assertThat(repository.replaceSystemMessage(CONVERSATION, "the current prompt")).isTrue();

        ArgumentCaptor<ChatMessage> saved = ArgumentCaptor.forClass(ChatMessage.class);
        verify(rows).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(7L);
        assertThat(saved.getValue().getContent()).isEqualTo("the current prompt");
        assertThat(saved.getValue().getRole()).isEqualTo(MessageRole.SYSTEM);

        ChatMessage user = new ChatMessage();
        user.setRole(MessageRole.USER);
        user.setContent("hello");
        when(rows.findFirstByConversationIdAndCondensedFalseOrderByCreatedAtAscIdAsc("conv-2"))
                .thenReturn(Optional.of(user));
        when(rows.findFirstByConversationIdAndCondensedFalseOrderByCreatedAtAscIdAsc("conv-3"))
                .thenReturn(Optional.empty());

        assertThat(repository.replaceSystemMessage("conv-2", "the current prompt")).isFalse();
        assertThat(repository.replaceSystemMessage("conv-3", "the current prompt")).isFalse();
        assertThat(user.getContent()).isEqualTo("hello");
        verify(rows, times(1)).save(any());
    }
}
