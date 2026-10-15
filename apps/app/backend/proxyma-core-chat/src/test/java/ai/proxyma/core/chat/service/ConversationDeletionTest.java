package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.domain.FeedbackMood;
import ai.proxyma.core.chat.domain.FeedbackSource;
import ai.proxyma.core.chat.event.ConversationsDeletedEvent;
import ai.proxyma.core.chat.model.ConversationShare;
import ai.proxyma.core.chat.model.Feedback;
import ai.proxyma.core.chat.repository.ChatMessageRepository;
import ai.proxyma.core.chat.repository.ConversationRepository;
import ai.proxyma.core.chat.repository.ConversationShareRepository;
import ai.proxyma.core.chat.repository.FeedbackRepository;
import ai.proxyma.core.guardrail.GuardrailService;
import ai.proxyma.core.provider.gateway.LlmGateway;
import ai.proxyma.core.settings.config.ProductIdentity;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import ai.proxyma.core.settings.service.McpGuardService;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.settings.usage.GlobalQuotaGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Deleting a conversation, against the repositories that ship and with no transaction around the
 * call - which is how ChatController and the retention sweep call it.
 *
 * <p>chat-agent-01: the feedback deletes were derived deletes with no transaction, which the shared
 * entity manager refuses as soon as there is a row to delete. So deleting any conversation somebody
 * had rated failed after its messages were already gone, and the share links, workflow runs,
 * sub-agents and the deleted event behind it never followed; un-clicking a thumb failed the same way.
 * Every earlier test mocked FeedbackRepository and could not see it.
 *
 * <p>chat-agent-03: on a multi-user server the folder was looked up from the owner AFTER the row was
 * deleted, found none, and deleted users/_anonymous/... instead - so no signed-in user's files went.
 *
 * <p>tools-core-01: a conversation id is a folder name, and one that is a path is refused before
 * anything is stored or written; nothing deleted ever leaves the conversations root.
 */
class ConversationDeletionTest {

    @TempDir
    Path data;

    private ChatJpa jpa;

    @AfterEach
    void tearDown() {
        if (jpa != null) jpa.close();
    }

    private record Wired(ChatService chat, SmartChatMemory memory, ApplicationEventPublisher events,
                         SubAgentService subAgents, WorkflowRunService workflowRuns) {}

    private Wired wire(boolean multiUser) {
        jpa = new ChatJpa(data, multiUser);
        SmartChatMemory memory = jpa.get(SmartChatMemory.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        ToolCallbackProvider tools = mock(ToolCallbackProvider.class);
        when(tools.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        SubAgentService subAgents = mock(SubAgentService.class);
        WorkflowRunService workflowRuns = mock(WorkflowRunService.class);
        ChatService chat = new ChatService(mock(LlmGateway.class), mock(SettingsService.class),
                mock(GlobalQuotaGate.class), mock(McpGuardService.class), memory, events,
                mock(ProductIdentity.class), mock(ConversationUsageRecorder.class), tools,
                mock(ChatSkillService.class), mock(ToolSkillGating.class), mock(SystemPromptService.class),
                jpa.get(FeedbackRepository.class), jpa.get(ConversationShareRepository.class),
                mock(ChatEventSink.class), mock(ConversationTitleService.class), mock(ModelPricingService.class),
                subAgents, workflowRuns, mock(WorkflowService.class), mock(WorkflowRunner.class),
                mock(GuardrailService.class));
        return new Wired(chat, memory, events, subAgents, workflowRuns);
    }

    // A conversation as a turn leaves it: a row, two messages, a folder with an upload in it.
    private Path conversation(SmartChatMemory memory, String id, String userId) throws Exception {
        memory.ensureConversation(id, "hello", userId, null, null, null);
        memory.add(id, List.of(new UserMessage("hello"), new AssistantMessage("hi")));
        memory.saveAttachment(id, userId, "notes.txt", "secret".getBytes());
        return memory.resolveStoredAttachment(id, "notes.txt").orElseThrow().getParent().getParent();
    }

    private Feedback rating(String conversationId, Long messageId, String comment) {
        Feedback f = new Feedback();
        f.setMood(FeedbackMood.GOOD);
        f.setSource(FeedbackSource.GENERAL);
        f.setComment(comment);
        f.setAnonymous(true);
        f.setConversationId(conversationId);
        f.setMessageId(messageId);
        f.setCreatedAt(Instant.now());
        return jpa.get(FeedbackRepository.class).save(f);
    }

    private void share(String conversationId, Long upto) {
        ConversationShare s = new ConversationShare();
        s.setToken("t-" + conversationId);
        s.setConversationId(conversationId);
        s.setUptoMessageId(upto);
        s.setCreatedAt(Instant.now());
        jpa.get(ConversationShareRepository.class).save(s);
    }

    private Long lastMessageId(String conversationId) {
        return jpa.get(ChatMessageRepository.class).findMaxIdByConversationId(conversationId);
    }

    @Test
    @DisplayName("a rated, shared conversation is deleted whole: messages, feedback, links, folder, and everything after them")
    void ratedConversationIsDeletedWhole() throws Exception {
        Wired w = wire(false);
        Path folder = conversation(w.memory(), "conv-1", null);
        Path other = conversation(w.memory(), "conv-2", null);
        Long answer = lastMessageId("conv-1");
        rating("conv-1", answer, null);
        rating("conv-1", answer, "a comment somebody wrote");
        rating("conv-2", lastMessageId("conv-2"), null);
        share("conv-1", answer);

        w.chat().deleteConversation("conv-1");

        assertThat(jpa.get(ConversationRepository.class).existsById("conv-1")).isFalse();
        assertThat(jpa.get(ChatMessageRepository.class).countByConversationId("conv-1")).isZero();
        assertThat(jpa.get(FeedbackRepository.class).findAll())
                .extracting(Feedback::getConversationId).containsExactly("conv-2");
        assertThat(jpa.get(ConversationShareRepository.class).findAllByConversationId("conv-1")).isEmpty();
        assertThat(folder).doesNotExist();
        assertThat(other).exists();
        verify(w.workflowRuns()).deleteForConversation("conv-1");
        verify(w.subAgents()).forget("conv-1");
        verify(w.events()).publishEvent(new ConversationsDeletedEvent(List.of("conv-1")));
    }

    @Test
    @DisplayName("a step of the cascade that fails does not keep the rest from running")
    void oneFailedStepDoesNotStopTheRest() throws Exception {
        Wired w = wire(false);
        conversation(w.memory(), "conv-1", null);
        doThrow(new IllegalStateException("database went away")).when(w.workflowRuns()).deleteForConversation("conv-1");

        w.chat().deleteConversation("conv-1");

        verify(w.subAgents()).forget("conv-1");
        verify(w.events()).publishEvent(new ConversationsDeletedEvent(List.of("conv-1")));
    }

    @Test
    @DisplayName("taking a thumb back deletes that rating and nothing anybody wrote")
    void retractingARating() throws Exception {
        Wired w = wire(false);
        conversation(w.memory(), "conv-1", null);
        Long answer = lastMessageId("conv-1");
        rating("conv-1", answer, null);
        rating("conv-1", answer, "a comment somebody wrote");

        w.chat().retractRating("conv-1", answer);

        assertThat(jpa.get(FeedbackRepository.class).findAll())
                .extracting(Feedback::getComment).containsExactly("a comment somebody wrote");
    }

    @Test
    @DisplayName("on a multi-user server the OWNER's folder goes with the conversation, and nobody else's")
    void multiUserDeletesTheOwnersFolder() throws Exception {
        Wired w = wire(true);
        Path alices = conversation(w.memory(), "conv-1", "alice");
        Path bobs = conversation(w.memory(), "conv-2", "bob");
        assertThat(alices).isEqualTo(data.toAbsolutePath().normalize()
                .resolve("users").resolve("alice").resolve("conversations").resolve("conv-1"));

        w.chat().deleteConversation("conv-1");

        assertThat(alices).doesNotExist();
        assertThat(bobs).exists();
        assertThat(data.resolve("users").resolve("_anonymous")).doesNotExist();
    }

    @Test
    @DisplayName("deleting an account removes that user's folder and only that one")
    void accountDeletionStaysInItsFolder() throws Exception {
        Wired w = wire(true);
        conversation(w.memory(), "conv-1", "alice");
        Path bobs = conversation(w.memory(), "conv-2", "bob");
        Path outside = Files.writeString(data.resolve("keep.txt"), "keep");

        w.chat().deleteAllByUserId("alice");

        assertThat(data.resolve("users").resolve("alice")).doesNotExist();
        assertThat(bobs).exists();
        assertThat(outside).exists();
    }

    @Test
    @DisplayName("tools-core-01: a conversation id that is a path is refused before anything is stored or written")
    void traversalIdIsRefused() throws Exception {
        Wired w = wire(true);
        Path bobs = conversation(w.memory(), "conv-2", "bob");
        Path outside = Files.writeString(data.resolve("keep.txt"), "keep");

        for (String id : new String[]{"../../..", "../../bob/conversations/conv-2", "..", "a/b", "a\\b"}) {
            assertThatThrownBy(() -> w.memory().ensureConversation(id, "hi", "alice", null, null, null)).as(id)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> w.memory().saveAttachment(id, "alice", "x.txt", new byte[]{1})).as(id)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> w.chat().getConversation(id)).as(id)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(w.memory().resolveOwnAttachment("conv-2", "alice", "notes.txt")).isEmpty();
        }
        assertThat(jpa.get(ConversationRepository.class).findAllIds()).containsExactly("conv-2");
        assertThat(bobs.resolve("attachments").resolve("notes.txt")).exists();
        assertThat(outside).exists();
    }

    @Test
    @DisplayName("a folder no conversation owns goes once it is a day old; a fresh upload's stays")
    void orphanFoldersAreSwept() throws Exception {
        Wired w = wire(true);
        Path kept = conversation(w.memory(), "conv-1", "alice");
        w.memory().saveAttachment("conv-fresh", "alice", "a.txt", new byte[]{1});
        w.memory().saveAttachment("conv-old", "alice", "a.txt", new byte[]{1});
        Path fresh = kept.resolveSibling("conv-fresh");
        Path old = kept.resolveSibling("conv-old");
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(3 * 86_400)));

        w.memory().cleanOrphanFolders(java.time.Duration.ofDays(1));

        assertThat(old).doesNotExist();
        assertThat(fresh).exists();
        assertThat(kept).exists();
    }
}
