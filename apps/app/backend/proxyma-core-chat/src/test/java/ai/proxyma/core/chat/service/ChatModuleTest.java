package ai.proxyma.core.chat.service;

import ai.proxyma.contracts.license.LicenseTier;
import ai.proxyma.core.chat.module.ChatModule;
import ai.proxyma.core.chat.repository.ConversationShareRepository;
import ai.proxyma.core.chat.repository.FeedbackRepository;
import ai.proxyma.core.graph.service.KnowledgeGraphService;
import ai.proxyma.core.guardrail.GuardrailService;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.provider.gateway.LlmGateway;
import ai.proxyma.core.settings.config.ProductIdentity;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import ai.proxyma.core.settings.service.McpGuardService;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.settings.usage.GlobalQuotaGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
 * A product module's agent half (ChatModule - the key-account panel's, on Enterprise Server): its
 * tools are offered only where the module says AND its skill is loaded AND the license includes it,
 * and its identity line is in the system prompt of its own conversations only, without making the
 * prompt move from one turn to the next.
 */
class ChatModuleTest {

    private static final String SKILL = "Key account review";
    private static final String BOUND = "conv-bound";

    /** The key-account module as the chat sees it: bound means BOUND, licensed is a switch. */
    private static final class FakeModule implements ChatModule {
        boolean licensed = true;
        String accountName = "Northwind Utilities";
        /** The user the chat last asked about - the turn's, never a guess. */
        String askedFor;

        @Override public List<Object> toolObjects() { return List.of(); }
        @Override public Map<String, Set<String>> skillToolGroups() {
            return Map.of(SKILL, Set.of("getKeyAccount", "proposeFact", "remember"));
        }
        @Override public Set<String> ownToolNames() { return Set.of("getKeyAccount", "proposeFact"); }
        @Override public boolean offersToolsIn(String conversationId, String userId) {
            askedFor = userId;
            return licensed && BOUND.equals(conversationId);
        }
        @Override public Optional<String> systemContext(String conversationId, String userId) {
            askedFor = userId;
            return BOUND.equals(conversationId) ? Optional.of("KEY ACCOUNT - \"" + accountName + "\".") : Optional.empty();
        }
        @Override public boolean licensed() { return licensed; }
    }

    private static ToolCallback tool(String name) {
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }

    private final FakeModule module = new FakeModule();
    private final FeatureGateService gate = new FeatureGateService();

    private ToolSkillGating gating() {
        LicenseToolScope license = new LicenseToolScope(gate);
        license.setModules(List.of(module));
        ChatSkillService skills = mock(ChatSkillService.class);
        when(skills.toolsForSkillName(anyString())).thenReturn(Optional.empty());
        return new ToolSkillGating(skills, mock(KnowledgeGraphService.class), license, List.of(module));
    }

    private List<String> offered(ToolSkillGating gating, Set<String> unlocked, String conversationId) {
        List<ToolCallback> all = List.of(tool("searchIndexedSources"), tool("getKeyAccount"), tool("proposeFact"),
                tool("remember"));
        Set<String> gated = gating.allGatedToolNames();
        List<ToolCallback> core = all.stream().filter(cb -> !gated.contains(cb.getToolDefinition().name())).toList();
        return gating.resolveAdvertised(all, core, unlocked, conversationId, "u-1").stream()
                .map(cb -> cb.getToolDefinition().name()).toList();
    }

    @Test
    @DisplayName("a module's tools are gated by its skill - never core tools offered in every conversation")
    void aModulesToolsAreGated() {
        gate.refresh(LicenseTier.ENTERPRISE);
        ToolSkillGating gating = gating();

        assertThat(gating.allGatedToolNames()).contains("getKeyAccount", "proposeFact", "remember")
                .doesNotContain("searchIndexedSources");
        assertThat(gating.toolNamesForGroup(SKILL)).containsExactlyInAnyOrder("getKeyAccount", "proposeFact", "remember");
        // The base group a module also names keeps its own tools.
        assertThat(gating.toolNamesForGroup("Memory & Knowledge")).containsExactly("remember");
    }

    @Test
    @DisplayName("offered only in a bound conversation with the skill loaded, on a licensed server")
    void offeredOnlyWhereBoundLicensedAndPicked() {
        gate.refresh(LicenseTier.ENTERPRISE);
        ToolSkillGating gating = gating();

        assertThat(offered(gating, Set.of(SKILL), BOUND))
                .containsExactlyInAnyOrder("searchIndexedSources", "getKeyAccount", "proposeFact", "remember");
        assertThat(module.askedFor).as("the module is asked for the turn's user").isEqualTo("u-1");
        // Not bound: the skill alone offers nothing of the module's - remember, a base tool, stays.
        assertThat(offered(gating, Set.of(SKILL), "conv-elsewhere"))
                .containsExactlyInAnyOrder("searchIndexedSources", "remember");
        // Over MCP there is no conversation at all.
        assertThat(offered(gating, Set.of(SKILL), null)).doesNotContain("getKeyAccount", "proposeFact");
        // Bound, but the skill is not loaded.
        assertThat(offered(gating, Set.of(), BOUND)).containsExactly("searchIndexedSources");

        // Unlicensed: the license scope drops them before the module is even asked.
        module.licensed = false;
        assertThat(offered(gating, Set.of(SKILL), BOUND)).doesNotContain("getKeyAccount", "proposeFact");
    }

    @Test
    @DisplayName("the module's skill is marked out of the plan in the skill list while unlicensed")
    void theSkillFollowsTheModulesLicense() {
        LicenseToolScope license = new LicenseToolScope(gate);
        license.setModules(List.of(module));
        assertThat(license.isSkillIncluded(SKILL)).isTrue();
        module.licensed = false;
        assertThat(license.isSkillIncluded(SKILL)).isFalse();
        assertThat(license.isSkillIncluded("Web")).isTrue();
    }

    // ---- The system context ----

    private SmartChatMemory memory;

    private ChatService chatService() {
        memory = mock(SmartChatMemory.class);
        when(memory.replaceSystemPrompt(anyString(), anyString())).thenReturn(true);
        ToolCallbackProvider tools = mock(ToolCallbackProvider.class);
        when(tools.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        ProductIdentity identity = mock(ProductIdentity.class);
        when(identity.getProductName()).thenReturn("Proxyma");
        SystemPromptService prompts = mock(SystemPromptService.class);
        when(prompts.read()).thenReturn(SystemPromptService.DEFAULT);
        ChatSkillService skills = mock(ChatSkillService.class);
        when(skills.formatForSystemPrompt(any())).thenReturn("SKILLS - one line each.");
        GuardrailService guardrails = mock(GuardrailService.class);
        when(guardrails.canaryLine()).thenReturn("");
        ChatService chat = new ChatService(mock(LlmGateway.class), mock(SettingsService.class),
                mock(GlobalQuotaGate.class), mock(McpGuardService.class), memory,
                mock(ApplicationEventPublisher.class), identity,
                mock(ConversationUsageRecorder.class), tools, skills,
                mock(ToolSkillGating.class), prompts, mock(FeedbackRepository.class),
                mock(ConversationShareRepository.class), mock(ChatEventSink.class),
                mock(ConversationTitleService.class), mock(ModelPricingService.class),
                mock(WorkflowRunService.class), mock(WorkflowService.class),
                mock(WorkflowRunner.class), guardrails);
        chat.setChatModules(List.of(module));
        return chat;
    }

    private static List<Message> history(String systemMessage) {
        return List.of(new SystemMessage(systemMessage), new UserMessage("what is still missing?"));
    }

    private String written(String conversationId) {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(memory).replaceSystemPrompt(eq(conversationId), text.capture());
        return text.getValue();
    }

    @Test
    @DisplayName("a bound conversation's system prompt names its account; any other conversation's does not")
    void theContextIsInBoundConversationsOnly() {
        ChatService chat = chatService();

        chat.refreshSystemPrompt(BOUND, history("an old prompt"), null, "u-1");
        assertThat(module.askedFor).as("the module is asked for the turn's user").isEqualTo("u-1");
        chat.refreshSystemPrompt("conv-elsewhere", history("an old prompt"), null, "u-1");

        assertThat(written(BOUND)).contains("KEY ACCOUNT - \"Northwind Utilities\".");
        assertThat(written("conv-elsewhere")).doesNotContain("KEY ACCOUNT");
    }

    @Test
    @DisplayName("the context does not move between turns, so the prompt is not rewritten turn after turn")
    void theContextKeepsThePromptStill() {
        ChatService chat = chatService();
        chat.refreshSystemPrompt(BOUND, history("an old prompt"), null, "u-1");
        String current = written(BOUND);

        chat.refreshSystemPrompt(BOUND, history(current), null, "u-1");
        chat.refreshSystemPrompt(BOUND, history(current), null, "u-1");
        verify(memory, times(1)).replaceSystemPrompt(anyString(), anyString());

        // An account renamed is a changed identity: given once, then still again.
        module.accountName = "Northwind Group";
        chat.refreshSystemPrompt(BOUND, history(current), null, "u-1");
        verify(memory, times(2)).replaceSystemPrompt(anyString(), anyString());
    }

    @Test
    @DisplayName("a module that fails to answer adds nothing and fails no turn")
    void aFailingModuleAddsNothing() {
        ChatService chat = chatService();
        ChatModule broken = mock(ChatModule.class);
        when(broken.systemContext(anyString(), anyString())).thenThrow(new IllegalStateException("database down"));
        chat.setChatModules(List.of(broken));

        chat.refreshSystemPrompt(BOUND, history("an old prompt"), null, "u-1");

        assertThat(written(BOUND)).doesNotContain("KEY ACCOUNT");
        verify(memory, never()).replaceSystemPrompt(eq("conv-elsewhere"), anyString());
    }
}
