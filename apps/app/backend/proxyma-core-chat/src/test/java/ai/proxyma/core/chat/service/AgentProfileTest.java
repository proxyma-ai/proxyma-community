package ai.proxyma.core.chat.service;

import ai.proxyma.contracts.license.LicenseTier;
import ai.proxyma.core.chat.module.AgentProfile;
import ai.proxyma.core.chat.module.AgentProfiles;
import ai.proxyma.core.chat.module.ChatModule;
import ai.proxyma.core.chat.repository.ConversationShareRepository;
import ai.proxyma.core.chat.repository.FeedbackRepository;
import ai.proxyma.core.graph.service.KnowledgeGraphService;
import ai.proxyma.core.guardrail.GuardrailService;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.provider.gateway.LlmGateway;
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.settings.config.ProductIdentity;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import ai.proxyma.core.settings.service.McpGuardService;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.settings.usage.GlobalQuotaGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A conversation a product module holds for a saved agent runs on that agent's settings
 * (AgentProfile, Constellations): its model, its step limit, the tools it is not given, its sources
 * and its memory. What is pinned here is the half that lives in chat - that every field only
 * narrows or redirects, that a withheld tool is refused at the call as well as hidden, that a
 * workflow step keeps its own model, and that a module failing to answer costs the one call and
 * leaves an ordinary chat.
 */
class AgentProfileTest {

    private static final String AGENT_CONVERSATION = "conv-agent";

    /** A module holding one conversation for an agent, with whatever profile a test gives it. */
    private static final class AgentModule implements ChatModule {
        AgentProfile profile;
        boolean fails;
        String askedFor;
        String pending;

        @Override public List<Object> toolObjects() { return List.of(); }
        @Override public Map<String, Set<String>> skillToolGroups() { return Map.of(); }
        @Override public Set<String> ownToolNames() { return Set.of(); }
        @Override public boolean offersToolsIn(String conversationId, String userId) { return false; }
        @Override public Optional<String> systemContext(String conversationId, String userId) { return Optional.empty(); }
        @Override public boolean licensed() { return true; }
        @Override public Optional<AgentProfile> profileFor(String conversationId, String userId) {
            askedFor = userId;
            if (fails) throw new IllegalStateException("database is down");
            return AGENT_CONVERSATION.equals(conversationId) ? Optional.ofNullable(profile) : Optional.empty();
        }
        @Override public boolean hasPendingInput(String conversationId) {
            return pending != null && AGENT_CONVERSATION.equals(conversationId);
        }
        @Override public Optional<String> takePendingInput(String conversationId) {
            String out = pending;
            pending = null;
            return Optional.ofNullable(out);
        }
    }

    private static AgentProfile withholding(String... tools) {
        return new AgentProfile(null, null, null, Set.of(tools), null, null);
    }

    private static ToolCallback tool(String name) {
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call(anyString())).thenReturn("{\"success\":true}");
        when(callback.call(anyString(), any())).thenReturn("{\"success\":true}");
        return callback;
    }

    private final AgentModule module = new AgentModule();

    @AfterEach
    void tearDown() {
        SearchContext.clear();
    }

    @Nested
    @DisplayName("the model a turn runs on")
    class Model {

        @Test
        @DisplayName("an ordinary chat runs on the chat role's pair")
        void ordinaryChat() {
            assertThat(TurnModel.of("p-chat", "big", null, null, null))
                    .isEqualTo(new TurnModel("p-chat", "big", false, false));
            assertThat(TurnModel.of("p-chat", "big", null, null, new AgentProfile(null, null, 8, null, null, null)))
                    .as("a profile with no model of its own changes nothing about the model")
                    .isEqualTo(new TurnModel("p-chat", "big", false, false));
        }

        @Test
        @DisplayName("an agent runs on its own model, on its provider or else the chat role's")
        void agentsOwnModel() {
            assertThat(TurnModel.of("p-chat", "big", null, null, new AgentProfile("p-cheap", "small", null, null, null, null)))
                    .isEqualTo(new TurnModel("p-cheap", "small", true, true));
            assertThat(TurnModel.of("p-chat", "big", null, null, new AgentProfile(null, "small", null, null, null, null)))
                    .isEqualTo(new TurnModel("p-chat", "small", true, true));
        }

        @Test
        @DisplayName("a workflow step keeps the model it picked, inside an agent's conversation too")
        void workflowFirst() {
            assertThat(TurnModel.of("p-chat", "big", "p-wf", "wf-model", new AgentProfile("p-cheap", "small", null, null, null, null)))
                    .isEqualTo(new TurnModel("p-wf", "wf-model", true, false));
        }
    }

    @Nested
    @DisplayName("tools")
    class Tools {

        private ToolSkillGating gating() {
            FeatureGateService gate = new FeatureGateService();
            gate.refresh(LicenseTier.ENTERPRISE);
            LicenseToolScope license = new LicenseToolScope(gate);
            license.setModules(List.of(module));
            ChatSkillService skills = mock(ChatSkillService.class);
            when(skills.toolsForSkillName(anyString())).thenReturn(Optional.empty());
            return new ToolSkillGating(skills, mock(KnowledgeGraphService.class), license, List.of(module));
        }

        private List<String> offered(String conversationId) {
            ToolSkillGating gating = gating();
            List<ToolCallback> all = List.of(tool("searchIndexedSources"), tool("recall"), tool("webFetch"));
            return gating.resolveAdvertised(all, all, Set.of(), conversationId, "u-1").stream()
                    .map(cb -> cb.getToolDefinition().name()).toList();
        }

        @Test
        @DisplayName("an agent's conversation is not offered the tools it withholds; any other conversation is")
        void withheldIsNotOffered() {
            module.profile = withholding("webFetch");
            assertThat(offered(AGENT_CONVERSATION)).containsExactlyInAnyOrder("searchIndexedSources", "recall");
            assertThat(module.askedFor).as("asked for the turn's user").isEqualTo("u-1");
            assertThat(offered("conv-ordinary")).containsExactlyInAnyOrder("searchIndexedSources", "recall", "webFetch");
        }

        @Test
        @DisplayName("a profile cannot add a tool: withholding one never offered changes nothing")
        void onlyNarrows() {
            module.profile = withholding("deleteEverything");
            assertThat(offered(AGENT_CONVERSATION)).containsExactlyInAnyOrder("searchIndexedSources", "recall", "webFetch");
        }

        @Test
        @DisplayName("a module that fails to answer leaves the conversation NOTHING for that call - never every tool")
        void aFailingModuleOffersNothing() {
            module.profile = withholding("webFetch");
            module.fails = true;
            assertThat(offered(AGENT_CONVERSATION)).isEmpty();
        }

        ChatService chatService(ToolCallback... callbacks) {
            ToolCallbackProvider tools = mock(ToolCallbackProvider.class);
            when(tools.getToolCallbacks()).thenReturn(callbacks);
            ProductIdentity identity = mock(ProductIdentity.class);
            when(identity.getProductName()).thenReturn("Proxyma");
            ChatService chat = new ChatService(mock(LlmGateway.class), mock(SettingsService.class),
                    mock(GlobalQuotaGate.class), mock(McpGuardService.class), mock(SmartChatMemory.class),
                    mock(ApplicationEventPublisher.class), identity,
                    mock(ConversationUsageRecorder.class), tools, mock(ChatSkillService.class),
                    mock(ToolSkillGating.class), mock(SystemPromptService.class), mock(FeedbackRepository.class),
                    mock(ConversationShareRepository.class), mock(ChatEventSink.class),
                    mock(ConversationTitleService.class), mock(ModelPricingService.class),
                    mock(WorkflowRunService.class), mock(WorkflowService.class),
                    mock(WorkflowRunner.class), mock(GuardrailService.class));
            chat.setChatModules(List.of(module));
            return chat;
        }

        @Test
        @DisplayName("a withheld tool the model names anyway is refused at the call, and never runs")
        void withheldIsRefusedAtTheCall() {
            module.profile = withholding("webFetch");
            ToolCallback webFetch = tool("webFetch");
            ToolCallback recall = tool("recall");
            ChatService chat = chatService(webFetch, recall);
            SearchContext.setUserId("u-1");

            ToolResponseMessage refused = chat.callTools(AGENT_CONVERSATION,
                    List.of(new ToolCall("t1", "function", "webFetch", "{\"url\":\"https://example.com\"}")),
                    new AtomicBoolean());
            assertThat(refused.getResponses().get(0).responseData()).contains("not given to this agent");
            verify(webFetch, never()).call(anyString());
            verify(webFetch, never()).call(anyString(), any());

            // The same tool in an ordinary conversation is not this rule's business.
            chat.callTools("conv-ordinary", List.of(new ToolCall("t2", "function", "webFetch", "{}")),
                    new AtomicBoolean());
            // And a tool the agent is given runs as ever.
            chat.callTools(AGENT_CONVERSATION, List.of(new ToolCall("t3", "function", "recall", "{}")),
                    new AtomicBoolean());
            assertThat(org.mockito.Mockito.mockingDetails(recall).getInvocations())
                    .anyMatch(i -> i.getMethod().getName().equals("call"));
        }
    }

    @Nested
    @DisplayName("skills an agent is not given")
    class Skills {

        @Test
        @DisplayName("reading a withheld skill is refused at the call, in any case; another skill is read as ever")
        void readSkillRefused() {
            module.profile = new AgentProfile(null, null, null, null, null, null, Set.of("Jira", "MCP: GitHub"));
            ToolCallback readSkill = tool("readSkill");
            ChatService chat = new Tools().chatService(readSkill);
            SearchContext.setUserId("u-1");

            ToolResponseMessage refused = chat.callTools(AGENT_CONVERSATION,
                    List.of(new ToolCall("t1", "function", "readSkill", "{\"skillName\":\" jira \"}")),
                    new AtomicBoolean());
            assertThat(refused.getResponses().get(0).responseData()).contains("is not given to this agent");
            verify(readSkill, never()).call(anyString());
            verify(readSkill, never()).call(anyString(), any());

            chat.callTools(AGENT_CONVERSATION,
                    List.of(new ToolCall("t2", "function", "readSkill", "{\"skillName\":\"Web\"}")),
                    new AtomicBoolean());
            assertThat(org.mockito.Mockito.mockingDetails(readSkill).getInvocations())
                    .anyMatch(i -> i.getMethod().getName().equals("call"));
        }

        @Test
        @DisplayName("a tool of a withheld group is refused at the call, though the model names it without reading the skill")
        void groupToolRefused() {
            module.profile = new AgentProfile(null, null, null, null, null, null, Set.of("Jira"));
            ToolCallback createJiraIssue = tool("createJiraIssue");
            ToolCallback recall = tool("recall");
            Tools tools = new Tools();
            ChatService chat = tools.chatService(createJiraIssue, recall);
            ToolSkillGating gating = (ToolSkillGating) org.springframework.test.util.ReflectionTestUtils
                    .getField(chat, "toolSkillGating");
            when(gating.skillsUnlocking("createJiraIssue")).thenReturn(Set.of("Jira"));
            SearchContext.setUserId("u-1");

            ToolResponseMessage refused = chat.callTools(AGENT_CONVERSATION,
                    List.of(new ToolCall("t1", "function", "createJiraIssue", "{\"summary\":\"x\"}")),
                    new AtomicBoolean());
            assertThat(refused.getResponses().get(0).responseData()).contains("not given to this agent");
            verify(createJiraIssue, never()).call(anyString());
            verify(createJiraIssue, never()).call(anyString(), any());

            chat.callTools(AGENT_CONVERSATION, List.of(new ToolCall("t2", "function", "recall", "{}")),
                    new AtomicBoolean());
            assertThat(org.mockito.Mockito.mockingDetails(recall).getInvocations())
                    .as("a core tool is not a group's").anyMatch(i -> i.getMethod().getName().equals("call"));
        }

        @Test
        @DisplayName("an MCP tool of a withheld server is refused by its name, without reaching the server")
        void mcpToolRefusedByName() {
            module.profile = new AgentProfile(null, null, null, null, null, null, Set.of("MCP: Prod DB"));
            ChatService chat = new Tools().chatService(tool("recall"));
            ai.proxyma.core.chat.external.ExternalToolSource external = mock(ai.proxyma.core.chat.external.ExternalToolSource.class);
            when(external.skillOf("u-1", "mcp_proddb_drop")).thenReturn(Optional.of("MCP: Prod DB"));
            ToolCallback drop = tool("mcp_proddb_drop");
            when(external.find("u-1", "mcp_proddb_drop")).thenReturn(Optional.of(drop));
            chat.setExternalTools(external);
            SearchContext.setUserId("u-1");

            ToolResponseMessage refused = chat.callTools(AGENT_CONVERSATION,
                    List.of(new ToolCall("t1", "function", "mcp_proddb_drop", "{}")), new AtomicBoolean());
            assertThat(refused.getResponses().get(0).responseData()).contains("not given to this agent");
            verify(drop, never()).call(anyString());
            verify(drop, never()).call(anyString(), any());
            verify(external, never()).toolsFor(anyString(), any());
        }

        @Test
        @DisplayName("a profile compares skill names without regard to case or spacing; none withheld allows all")
        void allowsSkill() {
            AgentProfile profile = new AgentProfile(null, null, null, null, null, null, Set.of("Office Files"));
            assertThat(profile.allowsSkill("office files ")).isFalse();
            assertThat(profile.allowsSkill("Web")).isTrue();
            assertThat(new AgentProfile(null, null, null, null, null, null).allowsSkill("Jira")).isTrue();
        }
    }

    @Nested
    @DisplayName("a module's messages for a conversation")
    class PendingInput {

        @Test
        @DisplayName("a message a module holds is background work the page keeps following, until taken")
        void owed() {
            ChatService chat = new Tools().chatService();
            module.pending = "[From Researcher] done";
            assertThat(chat.hasBackgroundWork(AGENT_CONVERSATION)).isTrue();
            assertThat(chat.hasBackgroundWork("conv-ordinary")).isFalse();
            module.takePendingInput(AGENT_CONVERSATION);
            assertThat(chat.hasBackgroundWork(AGENT_CONVERSATION)).isFalse();
        }

        @Test
        @DisplayName("a module's message once taken is never lost: owed until a step has written it")
        void takenIsKept() {
            ChatService chat = new Tools().chatService();
            module.pending = "[Reply from Researcher] the release is blocked";

            org.springframework.ai.chat.messages.Message taken = org.springframework.test.util.ReflectionTestUtils
                    .invokeMethod(chat, "takeReports", AGENT_CONVERSATION);
            assertThat(taken).isNotNull();
            assertThat(((org.springframework.ai.chat.messages.UserMessage) taken).getText())
                    .contains("the release is blocked");
            @SuppressWarnings("unchecked")
            Map<String, org.springframework.ai.chat.messages.Message> owed =
                    (Map<String, org.springframework.ai.chat.messages.Message>) org.springframework.test.util
                            .ReflectionTestUtils.getField(chat, "openingsTaken");
            assertThat(owed).as("a turn stopped before its first step still writes it").containsEntry(AGENT_CONVERSATION, taken);
        }
    }

    @Nested
    @DisplayName("the profile itself")
    class Profile {

        @Test
        @DisplayName("null means as an ordinary chat, for every field")
        void nullIsOrdinary() {
            AgentProfile none = new AgentProfile(" ", "", null, null, null, " ");
            assertThat(none.providerId()).isNull();
            assertThat(none.model()).isNull();
            assertThat(none.memoryKey()).isNull();
            assertThat(none.ownModel()).isFalse();
            assertThat(none.allows("anything")).isTrue();
            assertThat(none.reads(UUID.randomUUID())).isTrue();
        }

        @Test
        @DisplayName("sources are the given ones and no other")
        void sourcesNarrow() {
            UUID given = UUID.randomUUID();
            AgentProfile profile = new AgentProfile(null, null, null, null, Set.of(given), null);
            assertThat(profile.reads(given)).isTrue();
            assertThat(profile.reads(UUID.randomUUID())).isFalse();
            assertThat(new AgentProfile(null, null, null, null, Set.of(), null).reads(given))
                    .as("an agent given no source reads none").isFalse();
        }

        @Test
        @DisplayName("the first module with a profile answers; no conversation, no profile")
        void lookup() {
            module.profile = withholding("webFetch");
            assertThat(AgentProfiles.of(List.of(new AgentModule(), module), AGENT_CONVERSATION, "u-1"))
                    .as("a module with nothing to say is passed over").contains(module.profile);
            assertThat(AgentProfiles.of(List.of(module), null, "u-1")).isEmpty();
            assertThat(AgentProfiles.of(List.of(), AGENT_CONVERSATION, "u-1")).isEmpty();
        }

        @Test
        @DisplayName("a module that fails to answer FAILS CLOSED: nothing at all, never the user's whole account")
        void failureIsLocked() {
            AgentModule failing = new AgentModule();
            failing.fails = true;
            module.profile = withholding("webFetch");
            AgentProfile p = AgentProfiles.of(List.of(failing, module), AGENT_CONVERSATION, "u-1").orElseThrow();
            assertThat(p.locked()).isTrue();
            assertThat(p.allows("searchIndexedSources")).isFalse();
            assertThat(p.allows("remember")).isFalse();
            assertThat(p.allowsSkill("Jira")).isFalse();
            assertThat(p.reads(UUID.randomUUID())).isFalse();
            assertThat(p.narrowedSources()).contains(Set.of());
            assertThat(p.memoryKey()).isNull();
        }
    }
}
