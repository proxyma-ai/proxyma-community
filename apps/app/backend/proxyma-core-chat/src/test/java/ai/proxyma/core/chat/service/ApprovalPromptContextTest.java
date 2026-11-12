package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.approval.ApprovalContext;
import ai.proxyma.core.chat.approval.ApprovalContext.Field;
import ai.proxyma.core.chat.approval.ApprovalContextProvider;
import ai.proxyma.core.chat.approval.ApprovalContexts;
import ai.proxyma.core.chat.event.ChatEvent;
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

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * THE CHAT'S OWN APPROVAL CARD says what the write is about too: the prompt is asked at once, and the
 * context follows as a 'tool-approval-context' event once its lookup answers - kept with the open
 * prompt, so a page that loads the conversation meanwhile gets it with the prompt. A lookup that fails
 * sends nothing, and the prompt is decided exactly as before.
 */
class ApprovalPromptContextTest {

    static final String CONV = "conv-live";
    static final ToolCall WRITE = new ToolCall("call-1", "function", "replyToYouTubeComment",
            "{\"commentId\":\"c3\",\"text\":\"Thanks!\"}");

    List<Object> published;
    ChatService chatService;

    @BeforeEach
    void setUp() {
        ToolCallbackProvider tools = mock(ToolCallbackProvider.class);
        when(tools.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        published = new CopyOnWriteArrayList<>();
        ApplicationEventPublisher events = published::add;
        ChatEventSink sink = mock(ChatEventSink.class);
        when(sink.currentSequence(anyString())).thenReturn(100L);
        when(sink.turnStartSequence(anyString())).thenReturn(100L);
        chatService = new ChatService(mock(LlmGateway.class), mock(SettingsService.class),
                mock(GlobalQuotaGate.class), mock(McpGuardService.class), mock(SmartChatMemory.class),
                events, mock(ProductIdentity.class),
                mock(ConversationUsageRecorder.class), tools, mock(ChatSkillService.class),
                mock(ToolSkillGating.class), mock(SystemPromptService.class), mock(FeedbackRepository.class),
                mock(ConversationShareRepository.class), sink,
                mock(ConversationTitleService.class), mock(ModelPricingService.class),
                mock(WorkflowRunService.class), mock(WorkflowService.class),
                mock(WorkflowRunner.class), mock(GuardrailService.class));
    }

    List<ChatEvent> events(ChatEvent.Type type) {
        return published.stream().filter(e -> e instanceof ChatEvent c && type.value().equals(c.streamType()))
                .map(e -> (ChatEvent) e).toList();
    }

    void waitFor(ChatEvent.Type type) throws InterruptedException {
        long until = System.currentTimeMillis() + 5_000;
        while (events(type).isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20);
        assertThat(events(type)).as(type.value()).isNotEmpty();
    }

    CompletableFuture<String> ask() {
        return CompletableFuture.supplyAsync(() ->
                chatService.awaitApprovalIfNeeded(CONV, WRITE, WRITE.id(), new AtomicBoolean(false)));
    }

    @Test
    @DisplayName("the prompt is asked before the lookup answers; the context follows as its own event and with the open prompt")
    void contextFollowsThePrompt() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ApprovalContextProvider provider = (tool, args, conv, user) -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Optional.of(new ApprovalContext("YouTube", List.of(Field.quote("comment", "Sheet music please"),
                    Field.quote("reply", String.valueOf(args.get("text"))))));
        };
        chatService.setApprovalContexts(ApprovalContexts.of(List.of(provider), Duration.ofSeconds(5)));

        CompletableFuture<String> decision = ask();
        waitFor(ChatEvent.Type.TOOL_APPROVAL_REQUEST);
        assertThat(events(ChatEvent.Type.TOOL_APPROVAL_CONTEXT)).as("the prompt did not wait for the lookup").isEmpty();

        release.countDown();
        waitFor(ChatEvent.Type.TOOL_APPROVAL_CONTEXT);
        ChatEvent context = events(ChatEvent.Type.TOOL_APPROVAL_CONTEXT).get(0);
        assertThat(context.callId()).isEqualTo(WRITE.id());
        assertThat(context.data()).contains("\"key\":\"reply\"").contains("Thanks!");

        assertThat(chatService.getTranscript(CONV).prompts()).singleElement().satisfies(p -> {
            assertThat(p.callId()).isEqualTo(WRITE.id());
            assertThat(p.context().fields()).extracting(Field::key).containsExactly("comment", "reply");
        });

        chatService.resolveApproval(CONV, WRITE.id(), "deny");
        assertThat(decision.get(5, TimeUnit.SECONDS)).contains("denied");
        assertThat(chatService.getTranscript(CONV).prompts()).isEmpty();
    }

    @Test
    @DisplayName("a lookup that fails sends no context, and the prompt is decided as before")
    void failedLookupSendsNothing() throws Exception {
        ApprovalContextProvider broken = (tool, args, conv, user) -> {
            throw new IllegalStateException("YouTube said no");
        };
        chatService.setApprovalContexts(ApprovalContexts.of(List.of(broken), Duration.ofSeconds(5)));

        CompletableFuture<String> decision = ask();
        waitFor(ChatEvent.Type.TOOL_APPROVAL_REQUEST);
        Thread.sleep(300);
        assertThat(events(ChatEvent.Type.TOOL_APPROVAL_CONTEXT)).isEmpty();
        assertThat(chatService.getTranscript(CONV).prompts()).singleElement()
                .satisfies(p -> assertThat(p.context()).isNull());

        chatService.resolveApproval(CONV, WRITE.id(), "accept_once");
        assertThat(decision.get(5, TimeUnit.SECONDS)).isNull();
    }

    /** A provider whose cards must be whole (requiredFields): the comment, and the reply - or it throws. */
    static ApprovalContextProvider whole(boolean found) {
        return new ApprovalContextProvider() {
            @Override
            public Optional<ApprovalContext> contextFor(String tool, java.util.Map<String, Object> args, String conv,
                                                        String user) {
                if (!found) {
                    throw new Unavailable("YouTube has no comment with that commentId.",
                            "Missing details: the comment could not be read.");
                }
                return Optional.of(new ApprovalContext("YouTube", List.of(Field.quote("comment", "Sheet music please"),
                        Field.quote("reply", String.valueOf(args.get("text"))))));
            }

            @Override
            public java.util.Set<String> requiredFields(String tool) {
                return java.util.Set.of("comment", "reply");
            }
        };
    }

    @Test
    @DisplayName("a write whose card must be whole and cannot be is refused before the person is asked, with the reason the agent can act on")
    void incompleteCardIsNeverAsked() throws Exception {
        // 2026-11-05, the maintainer: "those requests must already auto rejected by invalid params".
        chatService.setApprovalContexts(ApprovalContexts.of(List.of(whole(false)), Duration.ofSeconds(5)));

        String answer = ask().get(5, TimeUnit.SECONDS);

        assertThat(answer).contains("\"status\":\"REFUSED\"").contains("YouTube has no comment with that commentId.")
                .contains("Nothing was kept or changed.");
        assertThat(events(ChatEvent.Type.TOOL_APPROVAL_REQUEST)).as("nobody was asked").isEmpty();
        assertThat(chatService.getTranscript(CONV).prompts()).isEmpty();
    }

    @Test
    @DisplayName("a write whose subject the person already approved, held for the daily quota, is not asked again: the agent is told it posts by itself")
    void heldApprovedIsNeverAskedAgain() throws Exception {
        // 2026-11-05, the maintainer: approved replies refused for YouTube's limit "should be queued to retry, not to ask
        // for approval again".
        ai.proxyma.core.chat.agents.ApprovalInbox inbox = mock(ai.proxyma.core.chat.agents.ApprovalInbox.class);
        when(inbox.alreadyHeld(org.mockito.ArgumentMatchers.eq("replyToYouTubeComment"), anyString(),
                org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenReturn(Optional.of("{\"success\":false,\"status\":\"ALREADY_APPROVED\"}"));
        chatService.setApprovalInbox(inbox);
        chatService.setApprovalContexts(ApprovalContexts.of(List.of(whole(true)), Duration.ofSeconds(5)));

        String answer = ask().get(5, TimeUnit.SECONDS);

        assertThat(answer).contains("ALREADY_APPROVED");
        assertThat(events(ChatEvent.Type.TOOL_APPROVAL_REQUEST)).as("nobody was asked").isEmpty();
        assertThat(chatService.getTranscript(CONV).prompts()).isEmpty();
    }

    @Test
    @DisplayName("a write whose card must be whole is asked with its card, the context sent with the prompt rather than after it")
    void wholeCardGoesWithThePrompt() throws Exception {
        chatService.setApprovalContexts(ApprovalContexts.of(List.of(whole(true)), Duration.ofSeconds(5)));

        CompletableFuture<String> decision = ask();
        waitFor(ChatEvent.Type.TOOL_APPROVAL_REQUEST);
        waitFor(ChatEvent.Type.TOOL_APPROVAL_CONTEXT);
        assertThat(chatService.getTranscript(CONV).prompts()).singleElement()
                .satisfies(p -> assertThat(p.context().fields()).extracting(Field::key).containsExactly("comment", "reply"));

        chatService.resolveApproval(CONV, WRITE.id(), "accept_once");
        assertThat(decision.get(5, TimeUnit.SECONDS)).isNull();
    }
}
