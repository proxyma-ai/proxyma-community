package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.dto.SubAgentView;
import ai.proxyma.core.chat.event.ChatEvent;
import ai.proxyma.core.chat.model.SubAgentRun;
import ai.proxyma.core.chat.repository.SubAgentRunRepository;
import ai.proxyma.core.guardrail.GuardrailService;
import ai.proxyma.core.guardrail.StreamingScrubber;
import ai.proxyma.core.provider.gateway.LlmGateway;
import ai.proxyma.core.provider.gateway.LlmRole;
import ai.proxyma.core.provider.gateway.LlmSession;
import ai.proxyma.core.settings.config.ProductIdentity;
import ai.proxyma.core.settings.dto.ChatSettings;
import ai.proxyma.core.settings.dto.SubAgentSettings;
import ai.proxyma.core.settings.service.SettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A sub-agent, since it became the main agent's equal (2026-10-02): what it is offered, how it
 * runs, and how it ends.
 *
 * <p>The claims worth pinning are the ones a reader would otherwise take on trust. That its tool
 * calls go to the conversation's OWN tool path - the host - and nowhere else, which is what makes
 * "its writes take the user's approval" true rather than said. That a background one returns at
 * once and its report is handed over exactly once. That a stop spends nothing more, and a run cut
 * short never calls itself done.
 */
class SubAgentServiceTest {

    private static final String CONV = "conv-1";

    /** A model that answers from a script, one entry per call, and remembers what it was sent. */
    private static final class ScriptedSession implements LlmSession {
        final List<BiFunction<List<Message>, List<ToolCallback>, AssistantMessage>> script = new ArrayList<>();
        final List<List<Message>> prompts = new CopyOnWriteArrayList<>();
        final List<List<String>> offered = new CopyOnWriteArrayList<>();
        /** What every call reports having used; none when null. */
        volatile Usage usage;

        /** When set, the session streams instead: one flux per call, the last repeated. */
        final List<java.util.function.Supplier<Flux<ChatResponse>>> streamed = new ArrayList<>();

        @Override public String provider() { return "Fake"; }
        @Override public String model() { return "fake-mini"; }
        @Override public boolean dedicated() { return true; }
        // One blocking call per step - what a session answers for a gateway told not to stream.
        @Override public boolean streams() { return !streamed.isEmpty(); }

        @Override public LlmGateway.LlmResult chat(List<Message> messages, List<ToolCallback> tools) {
            int call = prompts.size();
            prompts.add(List.copyOf(messages));
            offered.add(tools.stream().map(t -> t.getToolDefinition().name()).toList());
            AssistantMessage answer = script.get(Math.min(call, script.size() - 1)).apply(messages, tools);
            ChatResponse response = usage == null ? new ChatResponse(List.of(new Generation(answer)))
                    : new ChatResponse(List.of(new Generation(answer)), ChatResponseMetadata.builder().usage(usage).build());
            return new LlmGateway.LlmResult(response, provider(), model(), true);
        }

        @Override public Flux<ChatResponse> stream(List<Message> messages, List<ToolCallback> tools) {
            if (streamed.isEmpty()) throw new UnsupportedOperationException("this session does not stream");
            int call = prompts.size();
            prompts.add(List.copyOf(messages));
            offered.add(tools.stream().map(t -> t.getToolDefinition().name()).toList());
            return streamed.get(Math.min(call, streamed.size() - 1)).get();
        }

        void says(String text) {
            script.add((m, t) -> new AssistantMessage(text));
        }

        void calls(String id, String tool) {
            script.add((m, t) -> AssistantMessage.builder().content("")
                    .toolCalls(List.of(new ToolCall(id, "function", tool, "{}"))).build());
        }
    }

    /** The conversation, as a sub-agent sees it: something that runs its tool calls and is told when it ends. */
    private static final class RecordingHost implements AgentHost {
        final List<List<ToolCall>> rounds = new CopyOnWriteArrayList<>();
        final List<String> scopes = new CopyOnWriteArrayList<>();
        final CountDownLatch settled = new CountDownLatch(1);

        @Override public ToolResponseMessage callTools(String conversationId, List<ToolCall> calls, AtomicBoolean stop,
                                                       AgentScope scope) {
            rounds.add(calls);
            scopes.add(scope.agentId());
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            long seq = 100 + rounds.size() * 10L;
            for (ToolCall call : calls) {
                scope.toolStarted(call.id(), call.name(), call.arguments(), seq++, 1_000);
                scope.toolFinished(call.id(), "{\"results\":[]}", seq++, 2_000);
                responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), "{\"results\":[]}"));
            }
            return ToolResponseMessage.builder().responses(responses).build();
        }

        @Override public void subAgentSettled(String conversationId) {
            settled.countDown();
        }
    }

    private final ScriptedSession session = new ScriptedSession();
    private final RecordingHost host = new RecordingHost();
    private final Map<String, SubAgentRun> rows = new ConcurrentHashMap<>();
    private final SubAgentSettings settings = new SubAgentSettings();
    private ApplicationEventPublisher events;
    private final ConversationUsageRecorder usageRecorder = mock(ConversationUsageRecorder.class);
    private SubAgentService service;
    private ToolCallbackProvider tools;
    private ObjectProvider<ToolSkillGating> gating;
    private SmartChatMemory memory;
    private SubAgentRunRepository repository;

    private static ToolCallback tool(String name) {
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call(anyString())).thenReturn("{\"results\":[]}");
        return callback;
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        settings.setModel("fake-mini");
        ChatSettings chat = new ChatSettings();
        SettingsService settingsService = mock(SettingsService.class);
        when(settingsService.getSettings(SubAgentSettings.PREFIX, SubAgentSettings.class)).thenReturn(settings);
        when(settingsService.getSettings(ChatSettings.PREFIX, ChatSettings.class)).thenReturn(chat);

        LlmGateway gateway = mock(LlmGateway.class);
        when(gateway.isAvailable(LlmRole.SUB_AGENT)).thenReturn(true);
        when(gateway.open(any(), any())).thenReturn(session);

        // Built before the stubbing below: tool() stubs too, and Mockito cannot nest one inside another.
        ToolCallback[] registered = {
                tool("searchIndexedSources"), tool("createJiraIssue"), tool("runPython"),
                tool("askUser"), tool("delegate"), tool("useServer")};
        tools = mock(ToolCallbackProvider.class);
        when(tools.getToolCallbacks()).thenReturn(registered);
        ObjectProvider<ToolCallbackProvider> toolProvider = mock(ObjectProvider.class);
        when(toolProvider.getObject()).thenReturn(tools);

        gating = mock(ObjectProvider.class);
        ObjectProvider<AgentHost> hostProvider = mock(ObjectProvider.class);
        when(hostProvider.getObject()).thenReturn(host);

        GuardrailService guardrail = mock(GuardrailService.class);
        when(guardrail.outputScrubber()).thenAnswer(i -> new StreamingScrubber(List.of()));

        ProductIdentity identity = mock(ProductIdentity.class);
        when(identity.getProductName()).thenReturn("Proxyma");

        memory = mock(SmartChatMemory.class);
        when(memory.offloadIfLarge(any(), any(), any(), any())).thenAnswer(i -> i.getArgument(3));

        repository = mock(SubAgentRunRepository.class);
        when(repository.save(any())).thenAnswer(i -> {
            SubAgentRun row = i.getArgument(0);
            rows.put(row.getId(), row);
            return row;
        });
        when(repository.findByConversationIdOrderByStartedAtAsc(anyString())).thenAnswer(i -> rows.values().stream()
                .filter(r -> r.getConversationId().equals(i.getArgument(0)))
                .sorted(java.util.Comparator.comparing(SubAgentRun::getStartedAt))
                .toList());
        when(repository.findById(anyString())).thenAnswer(i -> Optional.ofNullable(rows.get(i.<String>getArgument(0))));
        when(repository.findByConversationIdAndDeliveredFalseAndStatusNotOrderByStartedAtAsc(anyString(), anyString()))
                .thenAnswer(i -> rows.values().stream()
                        .filter(r -> r.getConversationId().equals(i.getArgument(0)) && !r.isDelivered()
                                && !r.getStatus().equals(i.getArgument(1)))
                        .toList());

        events = mock(ApplicationEventPublisher.class);
        service = new SubAgentService(settingsService, gateway, toolProvider, events, new ChatEventSink(),
                identity, usageRecorder, memory, guardrail, gating, hostProvider, repository);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    // A model call that does not return until released - a sub-agent that is still working.
    private CountDownLatch blockModelCalls(CountDownLatch release) {
        CountDownLatch calling = new CountDownLatch(1);
        session.script.add((m, t) -> {
            calling.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new AssistantMessage("Found it.\nSTATUS: COMPLETED");
        });
        return calling;
    }

    private void awaitNoneRunning() throws InterruptedException {
        for (int i = 0; i < 200 && service.hasRunning(CONV); i++) Thread.sleep(25);
        assertThat(service.hasRunning(CONV)).isFalse();
    }

    @Test
    @DisplayName("chat-agent-04: a conversation that forgets a running sub-agent keeps nothing of it when it ends")
    void forgetDuringARunKeepsNothing() throws Exception {
        org.mockito.Mockito.doAnswer(i -> {
            rows.values().removeIf(r -> r.getConversationId().equals(i.getArgument(0)));
            return null;
        }).when(repository).deleteByConversationId(anyString());
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch calling = blockModelCalls(release);

        service.delegate(CONV, "delegate-1", request(null));
        assertThat(calling.await(5, TimeUnit.SECONDS)).isTrue();
        service.forget(CONV);
        release.countDown();
        awaitNoneRunning();

        // Its ending wrote its record back - for a conversation that is gone - and owed it a report.
        assertThat(rows).isEmpty();
        assertThat(service.hasUndelivered(CONV)).isFalse();
    }

    @Test
    @DisplayName("chat-agent-25: one reply that starts more sub-agents than the limit starts no more than the limit")
    void aFanOutStopsAtTheLimit() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        blockModelCalls(release);
        int asked = SubAgentService.MAX_RUNNING_PER_CONVERSATION + 4;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(asked);
        CountDownLatch go = new CountDownLatch(1);
        List<java.util.concurrent.Future<Map<String, Object>>> results = new ArrayList<>();
        try {
            for (int i = 0; i < asked; i++) {
                String callId = "delegate-" + i;
                results.add(pool.submit(() -> {
                    go.await();
                    return service.delegate(CONV, callId, request(null));
                }));
            }
            go.countDown();
            int started = 0;
            for (var f : results) if ("RUNNING".equals(f.get(10, TimeUnit.SECONDS).get("status"))) started++;
            assertThat(started).isEqualTo(SubAgentService.MAX_RUNNING_PER_CONVERSATION);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        awaitNoneRunning();
    }

    @Test
    @DisplayName("chat-agent-13: a paragraph streamed word by word is sent at most once a second, and whole at the end")
    void textIsSentAtMostOnceASecond() {
        // Sixty words over a second and a half: at the model call's own 80 ms pace that was about
        // twenty copies of the paragraph, each kept in the conversation's replay buffer.
        session.streamed.add(() -> Flux.interval(java.time.Duration.ofMillis(25)).take(60)
                .map(i -> new ChatResponse(List.of(new Generation(new AssistantMessage("word ")))))
                .concatWith(Flux.just(new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                        .toolCalls(List.of(new ToolCall("call-1", "function", "searchIndexedSources", "{}"))).build()))))));
        session.streamed.add(() -> Flux.just(new ChatResponse(List.of(new Generation(
                new AssistantMessage("Done.\nSTATUS: COMPLETED"))))));

        service.delegate(CONV, "delegate-1", request("sync"));

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events, atLeastOnce()).publishEvent(published.capture());
        // The first paragraph's - the one streamed word by word before the tool call.
        List<ChatEvent> texts = published.getAllValues().stream().map(ChatEvent.class::cast)
                .filter(e -> "sub-agent-text".equals(e.streamType()) && e.data().contains("\"segment\":1,")).toList();
        assertThat(texts).hasSizeBetween(1, 4);
        assertThat(texts.get(texts.size() - 1).data()).contains("\"text\":\"" + "word ".repeat(60) + "\"");
    }

    @Test
    @DisplayName("telemetry-dff32853: a sub-agent whose provider fails hands back the error AND what it had done")
    void aFailedRunReportsItsWork() {
        session.script.add((m, t) -> AssistantMessage.builder().content("Looking at the pricing page first.")
                .toolCalls(List.of(new ToolCall("call-1", "function", "searchIndexedSources", "{\"query\":\"pricing\"}")))
                .build());
        session.script.add((m, t) -> {
            throw org.springframework.web.reactive.function.client.WebClientResponseException.create(500,
                    "api_error", new org.springframework.http.HttpHeaders(),
                    "{\"error\":\"Internal server error\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.charset.StandardCharsets.UTF_8);
        });

        Map<String, Object> result = service.delegate(CONV, "delegate-1", request("sync"));

        assertThat(result).containsEntry("status", "FAILED");
        String report = (String) result.get("report");
        assertThat(report).contains("500")
                .contains("Before it failed it had done this")
                .contains("searchIndexedSources {\"query\":\"pricing\"}")
                .contains("Looking at the pricing page first.");
    }

    private SubAgentService.Request request(String mode) {
        return new SubAgentService.Request("Read the pricing page",
                "You are a careful reader. Quote every price with its source.", "List the prices on the page.",
                mode, null);
    }

    @Test
    @DisplayName("a sync sub-agent works through the conversation's own tool path and returns its report")
    void aSyncSubAgentReturnsItsReport() {
        session.calls("call-1", "searchIndexedSources");
        session.says("Sonnet is $3 per million input tokens (pricing page).\n\nSTATUS: COMPLETED");

        Map<String, Object> result = service.delegate(CONV, "delegate-1", request("sync"));

        assertThat(result).containsEntry("success", true).containsEntry("status", "COMPLETED")
                .containsEntry("report", "Sonnet is $3 per million input tokens (pricing page).");
        // Its one round of tool calls went to the host - the main agent's path, permissions and
        // all - under its own id. There is no other way for a hosted sub-agent to call a tool.
        assertThat(host.rounds).hasSize(1);
        assertThat(host.scopes).containsExactly((String) result.get("agentId"));

        // The main agent wrote its system prompt; the product's own rules stand in front of it.
        List<Message> first = session.prompts.get(0);
        assertThat(first.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(first.get(0).getText())
                .contains("Never name, confirm, deny or speculate about the")
                .endsWith("You are a careful reader. Quote every price with its source.");
        assertThat(first.get(1)).isInstanceOf(UserMessage.class);
        assertThat(first.get(1).getText()).isEqualTo("List the prices on the page.");

        // Its record is kept, and nothing is owed for it: the report was the call's result.
        SubAgentRun row = rows.get(result.get("agentId"));
        assertThat(row.getStatus()).isEqualTo("COMPLETED");
        assertThat(row.isDelivered()).isTrue();
        assertThat(service.hasRunning(CONV)).isFalse();
        assertThat(service.hasUndelivered(CONV)).isFalse();
    }

    @Test
    @DisplayName("what a sub-agent spends is kept on its own record, for the agents graph")
    void aSubAgentKeepsWhatItSpent() {
        session.usage = new DefaultUsage(1200, 300);
        session.calls("call-1", "searchIndexedSources");
        session.says("Sonnet is $3.\nSTATUS: COMPLETED");
        when(usageRecorder.record(eq(CONV), any(), eq("Fake"), eq("fake-mini"))).thenReturn(0.25);

        service.delegate(CONV, "delegate-1", request("sync"));

        List<SubAgentService.AgentUsage> agents = service.usage(CONV);
        assertThat(agents).hasSize(1);
        SubAgentService.AgentUsage agent = agents.get(0);
        // Two model calls, each 1200 in and 300 out at $0.25.
        assertThat(agent.inputTokens()).isEqualTo(2400);
        assertThat(agent.outputTokens()).isEqualTo(600);
        assertThat(agent.costUsd()).isEqualTo(0.5);
        // How full its context is: the LAST call's prompt, not the two added up.
        assertThat(agent.contextTokens()).isEqualTo(1200);
        assertThat(agent.provider()).isEqualTo("Fake");
        assertThat(agent.model()).isEqualTo("fake-mini");
        assertThat(agent.status()).isEqualTo("completed");
        assertThat(agent.mode()).isEqualTo("sync");
        // Read back from the stored row once it has ended, not only from memory.
        assertThat(rows.get(agent.id()).getInputTokens()).isEqualTo(2400);
        assertThat(rows.get(agent.id()).getCostUsd()).isEqualTo(0.5);
        assertThat(rows.get(agent.id()).getContextTokens()).isEqualTo(1200);
    }

    @Test
    @DisplayName("inside a conversation it is offered the main agent's tools, writes included - but not delegate or useServer")
    void writeToolsAreOfferedInsideAConversation() {
        session.says("Nothing to do.\nSTATUS: COMPLETED");

        service.delegate(CONV, "delegate-1", request("sync"));

        assertThat(session.offered.get(0))
                .containsExactlyInAnyOrder("searchIndexedSources", "createJiraIssue", "runPython", "askUser");
    }

    @Test
    @DisplayName("a sub-agent of a conversation bound to a key account is offered that account's tools, and only there")
    void aBoundConversationsToolsReachItsSubAgents() {
        ToolCallback[] registered = {tool("searchIndexedSources"), tool("getKeyAccount"), tool("proposeFact")};
        when(tools.getToolCallbacks()).thenReturn(registered);
        // A product module whose own tools are offered only in CONV - what the key-account module
        // answers for a conversation bound to an account (ChatModule.offersToolsIn).
        List<String> askedAbout = new CopyOnWriteArrayList<>();
        ai.proxyma.core.chat.module.ChatModule module = new ai.proxyma.core.chat.module.ChatModule() {
            @Override public List<Object> toolObjects() { return List.of(); }
            @Override public Map<String, java.util.Set<String>> skillToolGroups() {
                return Map.of("Key account review", java.util.Set.of("getKeyAccount", "proposeFact"));
            }
            @Override public java.util.Set<String> ownToolNames() { return java.util.Set.of("getKeyAccount", "proposeFact"); }
            @Override public boolean offersToolsIn(String conversationId, String userId) {
                askedAbout.add(String.valueOf(conversationId));
                return CONV.equals(conversationId);
            }
            @Override public Optional<String> systemContext(String conversationId, String userId) { return Optional.empty(); }
            @Override public boolean licensed() { return true; }
        };
        LicenseToolScope license = mock(LicenseToolScope.class);
        when(license.isToolIncluded(anyString(), org.mockito.ArgumentMatchers.anySet())).thenReturn(true);
        ChatSkillService skills = mock(ChatSkillService.class);
        when(skills.toolsForSkillName(anyString())).thenReturn(Optional.empty());
        ToolSkillGating real = new ToolSkillGating(skills, mock(SubAgentService.class),
                mock(ai.proxyma.core.graph.service.KnowledgeGraphService.class), license, List.of(module));
        when(gating.getIfAvailable()).thenReturn(real);
        // Both conversations have the skill loaded; only CONV is bound.
        for (String id : List.of(CONV, "conv-unbound")) {
            ai.proxyma.core.chat.model.Conversation c = new ai.proxyma.core.chat.model.Conversation();
            c.setUnlockedSkillNames("Key account review");
            when(memory.findConversation(id)).thenReturn(Optional.of(c));
        }
        session.says("Nothing to do.\nSTATUS: COMPLETED");

        service.delegate(CONV, "delegate-1", request("sync"));
        service.delegate("conv-unbound", "delegate-2", request("sync"));

        // Asked about the PARENT conversation - the id its tool calls also run under, so the tools
        // resolve the same account the main agent works on.
        assertThat(askedAbout).contains(CONV, "conv-unbound");
        assertThat(session.offered.get(0)).contains("getKeyAccount", "proposeFact");
        assertThat(session.offered.get(1)).doesNotContain("getKeyAccount", "proposeFact").contains("searchIndexedSources");
    }

    @Test
    @DisplayName("outside a conversation there is nobody to approve a write, so it is offered none")
    void anUnattendedSubAgentIsReadOnly() {
        session.calls("call-1", "searchIndexedSources");
        session.says("Nothing to do.\nSTATUS: COMPLETED");

        Map<String, Object> result = service.delegate(null, null, request("async"));

        assertThat(session.offered.get(0)).containsExactly("searchIndexedSources");
        // And it is waited for, whatever was asked: there is no conversation to report back into.
        assertThat(result).containsEntry("status", "COMPLETED");
        assertThat(host.rounds).as("no conversation, so no conversation tool path").isEmpty();
        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("a sub-agent runs in the background unless asked to wait, and its report is handed over exactly once")
    void anAsyncSubAgentReportsLater() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        session.script.add((messages, tools) -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new AssistantMessage("Gemini Pro is $1.25.\nSTATUS: COMPLETED");
        });

        // No mode: async is the default (maintainer's feedback, 2026-10-03).
        Map<String, Object> started = service.delegate(CONV, "delegate-1", request(null));

        assertThat(started).containsEntry("status", "RUNNING").containsEntry("success", true);
        assertThat(started).doesNotContainKey("report");
        assertThat(service.hasRunning(CONV)).isTrue();
        assertThat(service.runningTitles(CONV)).containsExactly("Read the pricing page");
        assertThat(service.takeReports(CONV)).as("nothing is owed while it runs").isEmpty();

        release.countDown();
        assertThat(host.settled.await(10, TimeUnit.SECONDS)).as("the conversation is told it has reported").isTrue();

        assertThat(service.hasRunning(CONV)).isFalse();
        assertThat(service.hasUndelivered(CONV)).isTrue();
        List<SubAgentService.Settled> reports = service.takeReports(CONV);
        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).agentId()).isEqualTo(started.get("agentId"));
        assertThat(reports.get(0).status()).isEqualTo("COMPLETED");
        assertThat(reports.get(0).report()).isEqualTo("Gemini Pro is $1.25.");
        assertThat(service.takeReports(CONV)).as("a report is handed over once").isEmpty();
        assertThat(service.hasUndelivered(CONV)).isFalse();
    }

    @Test
    @DisplayName("stopping a sub-agent ends it without another model call, and wakes nobody")
    void stoppingSpendsNothingMore() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        session.script.add((messages, tools) -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new AssistantMessage("too late");
        });

        Map<String, Object> started = service.delegate(CONV, "delegate-1", request("async"));
        String agentId = (String) started.get("agentId");
        // The model call is out before the stop lands.
        for (int i = 0; i < 100 && session.prompts.isEmpty(); i++) Thread.sleep(20);

        assertThat(service.stop("another-conversation", agentId)).as("only its own conversation may stop it").isFalse();
        assertThat(service.stop(CONV, agentId)).isTrue();
        for (int i = 0; i < 200 && service.hasRunning(CONV); i++) Thread.sleep(20);
        release.countDown();

        assertThat(service.hasRunning(CONV)).isFalse();
        assertThat(rows.get(agentId).getStatus()).isEqualTo("STOPPED");
        assertThat(session.prompts).as("no last words for a run somebody stopped").hasSize(1);
        assertThat(host.settled.getCount()).as("a Stop does not make the assistant speak").isEqualTo(1);
        // The main agent still learns of it, with its next turn.
        assertThat(service.takeReports(CONV)).extracting(SubAgentService.Settled::status).containsExactly("STOPPED");
    }

    @Test
    @DisplayName("out of steps, it is asked once for what it found - and that is PARTIAL whatever it claims")
    void outOfStepsItReportsWhatItHas() {
        settings.setMaxIterations(2);
        session.calls("call-1", "searchIndexedSources");
        // The last budgeted step ignores the instruction and asks for another tool.
        session.calls("call-2", "searchIndexedSources");
        session.says("I read the first sheet only.\nSTATUS: COMPLETED");

        Map<String, Object> result = service.delegate(CONV, "delegate-1", request("sync"));

        assertThat(result).containsEntry("status", "PARTIAL").containsEntry("success", true)
                .containsEntry("report", "I read the first sheet only.");
        assertThat(host.rounds).as("the call past the limit is answered, not run").hasSize(1);
        assertThat(session.prompts).hasSize(3);
        List<Message> last = session.prompts.get(2);
        assertThat(last.get(last.size() - 1).getText()).contains("You have no steps left after this one");
        // The refused call has its answer in the history, or the provider would reject the request.
        assertThat(last.get(last.size() - 2)).isInstanceOf(ToolResponseMessage.class);
    }

    @Test
    @DisplayName("what it does reaches the page as events under its own id, the report last")
    void itsEventsCarryItsId() {
        session.says("Done.\nSTATUS: COMPLETED");

        Map<String, Object> result = service.delegate(CONV, "delegate-1", request("sync"));

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events, atLeastOnce()).publishEvent(published.capture());
        List<ChatEvent> chatEvents = published.getAllValues().stream().map(ChatEvent.class::cast).toList();
        assertThat(chatEvents).extracting(ChatEvent::streamType)
                .containsExactly("sub-agent-start", "sub-agent-wait", "sub-agent-text", "sub-agent-done");
        assertThat(chatEvents).allMatch(e -> result.get("agentId").equals(e.parentCallId()));
        // The first names the delegate call it belongs to - how the page finds its row.
        assertThat(chatEvents.get(0).callId()).isEqualTo("delegate-1");
        assertThat(chatEvents.get(3).data()).contains("\"status\":\"completed\"").contains("\"report\":\"Done.\"");
    }

    @Test
    @DisplayName("time spent waiting for the user's answer is not the sub-agent's")
    void waitingForTheUserStopsItsClock() throws Exception {
        SubAgentService.Run run = new SubAgentService.Run("agent-1", CONV, "delegate-1", "t",
                SubAgentService.Mode.SYNC, 50, null);

        run.waitingForUser(true);
        assertThat(run.outOfTime(run.startedAt + 60_000)).as("an approval may take the user a while").isFalse();
        Thread.sleep(30);
        run.waitingForUser(false);

        assertThat(run.deadline()).isGreaterThanOrEqualTo(run.startedAt + 50 + 30);
        assertThat(run.outOfTime(run.startedAt + 60_000)).isTrue();
    }

    @Test
    @DisplayName("what it had done is reported as of a position in the event stream, exactly")
    void theViewIsExactAsOfAPosition() {
        SubAgentService.Run run = new SubAgentService.Run("agent-1", CONV, "delegate-1", "t",
                SubAgentService.Mode.ASYNC, 60_000, null);
        run.startSeq = 3;
        run.toolStarted("call-1", "searchIndexedSources", "{}", 5, 1_000);
        run.toolFinished("call-1", "{\"results\":[]}", 9, 2_000);

        // A page that already holds everything up to event 2 has not heard of it: the stream will.
        assertThat(run.viewAsOf(2)).isEmpty();
        // Started, and nothing done yet.
        assertThat(run.viewAsOf(4).orElseThrow().items()).isEmpty();
        // The call had started and not returned: the stream brings its result.
        SubAgentView.Item running = run.viewAsOf(6).orElseThrow().items().get(0);
        assertThat(running.done()).isFalse();
        assertThat(running.result()).isNull();
        // And by event 9 it had.
        SubAgentView.Item done = run.viewAsOf(9).orElseThrow().items().get(0);
        assertThat(done.done()).isTrue();
        assertThat(done.result()).isEqualTo("{\"results\":[]}");
        assertThat(done.endedAt()).isEqualTo(2_000L);
    }
}
