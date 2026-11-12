package ai.proxyma.core.chat.agents;

import ai.proxyma.core.chat.approval.ApprovalContext;
import ai.proxyma.core.chat.approval.ApprovalContext.Field;
import ai.proxyma.core.chat.approval.ApprovalContextProvider;
import ai.proxyma.core.chat.approval.ApprovalContexts;
import ai.proxyma.core.chat.model.ToolApprovalRequest;
import ai.proxyma.core.chat.repository.AgentAlarmRepository;
import ai.proxyma.core.chat.repository.AgentInboxRepository;
import ai.proxyma.core.chat.repository.AgentNodeRepository;
import ai.proxyma.core.chat.repository.ToolApprovalRequestRepository;
import ai.proxyma.core.chat.service.ChatEventSink;
import ai.proxyma.core.chat.service.ChatService;
import ai.proxyma.core.settings.dto.SubAgentSettings;
import ai.proxyma.core.settings.service.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * THE APPROVALS PAGE SHOWS ONE DRAFT PER COMMENT, AND WHAT EACH IS ABOUT (2026-11-02, the maintainer's
 * review of the YouTube assistant's Approvals page: rows shown as raw arguments, forty waiting, and
 * "will the rework be reflected here?").
 *
 * <ul>
 *   <li>A newer draft for the same subject replaces the waiting one WHOEVER wrote it - another agent,
 *       another run - and the one replaced is listed as REPLACED with the decided ones, its agent told.</li>
 *   <li>Two agents drafting for one comment at the same moment leave one request: the context lookup
 *       used to sit between the check for a waiting draft and the save, a window both walked through.</li>
 *   <li>An agent withdraws a request of its own person's; never another person's, never outside the trees
 *       it was given.</li>
 *   <li>A request kept without its context - a lookup that failed or ran past its seconds in a burst of
 *       drafts - is looked up again, bounded, until it has one; a revised draft whose own lookup failed
 *       shows the old one's, with its own words. Since 2026-11-03 a DECIDED one too - withdrawn or replaced
 *       while the service failed - but only while a page lists it, within the same attempts; and when the
 *       lookup fails, it borrows what a request of the same person about the same subject was found to be about.</li>
 *   <li>A write that ran links its words to where they went, so the page shows no raw result.</li>
 * </ul>
 */
class ApprovalRevisionTest {

    static final String REPLY = "replyToYouTubeComment";
    static final ObjectMapper JSON = new ObjectMapper();

    ToolApprovalRequestRepository rows;
    AgentInbox inbox;
    ChatService chat;
    ApprovalInbox approvals;
    Replies replies = new Replies();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        rows = InMemoryRepositories.of(ToolApprovalRequestRepository.class, Map.of(
                "conversationsWithPending", (store, args) -> List.of(),
                "decide", (store, args) -> {
                    synchronized (store) {
                        ToolApprovalRequest r = (ToolApprovalRequest) store.get(args[0]);
                        if (r == null || !r.isPending()) return 0;
                        r.setStatus((String) args[1]);
                        r.setDecidedAt((Instant) args[2]);
                        r.setDecidedBy((String) args[3]);
                        r.setNote((String) args[4]);
                        return 1;
                    }
                },
                "fillContext", (store, args) -> {
                    ToolApprovalRequest r = (ToolApprovalRequest) store.get(args[0]);
                    if (r == null || r.getContext() != null) return 0;
                    r.setContext((String) args[1]);
                    r.setContextAttempts(r.getContextAttempts() + 1);
                    return 1;
                },
                "save", (store, args) -> {
                    synchronized (store) {
                        ToolApprovalRequest r = (ToolApprovalRequest) args[0];
                        store.put(r.getId(), r);
                        return r;
                    }
                }));
        AgentInboxRepository inboxRows = InMemoryRepositories.of(AgentInboxRepository.class, Map.of(
                "markDelivered", (store, args) -> {
                    int n = 0;
                    for (Object id : (Collection<?>) args[0]) {
                        ai.proxyma.core.chat.model.AgentInboxEntry e = (ai.proxyma.core.chat.model.AgentInboxEntry) store.get(id);
                        if (e != null && e.getDeliveredAt() == null) {
                            e.setDeliveredAt((Instant) args[1]);
                            n++;
                        }
                    }
                    return n;
                }));
        ApplicationEventPublisher events = e -> { };
        inbox = new AgentInbox(inboxRows, events);
        chat = mock(ChatService.class);
        ObjectProvider<ChatService> chats = mock(ObjectProvider.class);
        when(chats.getObject()).thenReturn(chat);
        SettingsService settings = mock(SettingsService.class);
        when(settings.getSettings(SubAgentSettings.PREFIX, SubAgentSettings.class)).thenReturn(new SubAgentSettings());
        AgentTree tree = new AgentTree(InMemoryRepositories.of(AgentNodeRepository.class), inbox,
                InMemoryRepositories.of(AgentAlarmRepository.class), chats, settings, mock(ChatEventSink.class), events);
        approvals = new ApprovalInbox(rows, inbox, tree, chats);
        approvals.runInline();
        approvals.setContexts(ApprovalContexts.of(List.of(replies), Duration.ofSeconds(5)));
    }

    /** The YouTube reply's provider in miniature; it can be made to fail, or to take its time. */
    static final class Replies implements ApprovalContextProvider {
        final AtomicBoolean down = new AtomicBoolean();
        final AtomicInteger asked = new AtomicInteger();
        volatile long slowMs;

        @Override
        public Optional<ApprovalContext> contextFor(String tool, Map<String, Object> args, String conv, String user) {
            if (!REPLY.equals(tool)) return Optional.empty();
            asked.incrementAndGet();
            if (slowMs > 0) {
                try {
                    Thread.sleep(slowMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (down.get()) throw new IllegalStateException("YouTube did not answer");
            return Optional.of(new ApprovalContext("YouTube", List.of(
                    Field.link("video", "Hoa Tau", "https://www.youtube.com/watch?v=v9"),
                    Field.link("viewer", "Minh", "https://www.youtube.com/channel/UCfan2"),
                    new Field("comment", "Sheet music please", "https://www.youtube.com/watch?v=v9&lc=" + args.get("commentId"),
                            ApprovalContext.QUOTE),
                    Field.editable("reply", String.valueOf(args.get("text")), "text"))));
        }

        @Override
        public boolean describes(String tool) {
            return REPLY.equals(tool);
        }

        @Override
        public Optional<String> subjectOf(String tool, Map<String, Object> args) {
            return REPLY.equals(tool) ? Optional.of("youtube-comment:" + args.get("commentId")) : Optional.empty();
        }
    }

    JsonNode park(String conversationId, String user, String commentId, String text) {
        try {
            return JSON.readTree(approvals.park(conversationId, user, new ToolCall("call-" + UUID.randomUUID(), "function", REPLY,
                    "{\"commentId\":\"" + commentId + "\",\"text\":\"" + text + "\"}")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    ToolApprovalRequest row(String id) {
        return rows.findById(UUID.fromString(id)).orElseThrow();
    }

    List<ToolApprovalRequest> waiting() {
        return rows.findAll().stream().filter(ToolApprovalRequest::isPending).toList();
    }

    @Test
    @DisplayName("another agent's newer draft for the comment replaces the waiting one, which Decided lists as REPLACED, its agent told")
    void anotherAgentsDraftReplacesTheWaitingOne() {
        String first = park("conv-quick-reactions", "u-1", "c3", "<3").get("requestId").asText();
        JsonNode second = park("conv-reply-writer", "u-1", "c3", "Cam on ban nhieu nhe");

        assertThat(waiting()).hasSize(1).first().satisfies(r -> {
            assertThat(r.getId().toString()).isEqualTo(second.get("requestId").asText());
            assertThat(r.getConversationId()).isEqualTo("conv-reply-writer");
        });
        assertThat(row(first).getStatus()).isEqualTo(ToolApprovalRequest.REPLACED);
        assertThat(second.get("replaced").get(0).asText()).isEqualTo(first);
        assertThat(second.get("note").asText()).contains("REPLACED").contains("To revise it")
                .contains(ApprovalInbox.WITHDRAW_TOOL);

        String told = inbox.takeWhere("conv-quick-reactions", t -> true).get(0).text();
        assertThat(told).contains("REPLACED").contains("Do not draft it again");
        assertThat(approvals.hasPending("conv-quick-reactions")).isFalse();
        assertThat(inbox.takeWhere("conv-reply-writer", t -> true)).as("the agent that revised it knows already").isEmpty();

        assertThat(approvals.forTrees(List.of("conv-quick-reactions", "conv-reply-writer"), ApprovalInbox.Show.DECIDED, 0, 20)
                .getContent()).extracting(ApprovalInbox.View::status).containsExactly(ToolApprovalRequest.REPLACED);
        assertThat(approvals.pendingInTrees(List.of("conv-quick-reactions", "conv-reply-writer"))).isEqualTo(1);
    }

    @Test
    @DisplayName("another person's draft for the same comment is theirs: nothing of this person's is replaced")
    void anotherPersonsDraftIsTheirs() {
        park("conv-a", "u-1", "c3", "One");
        park("conv-b", "u-2", "c3", "Two");
        assertThat(waiting()).hasSize(2);
    }

    @Test
    @DisplayName("two agents drafting for one comment at the same moment leave one request waiting")
    void draftsAtOnceLeaveOne() throws Exception {
        replies.slowMs = 150;
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch go = new CountDownLatch(1);
        List<Exception> failed = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 6; i++) {
            int n = i;
            pool.execute(() -> {
                try {
                    go.await();
                    park("conv-" + n, "u-1", "c3", "Draft " + n);
                } catch (Exception e) {
                    failed.add(e);
                }
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        assertThat(failed).isEmpty();
        assertThat(waiting()).hasSize(1);
        assertThat(rows.findAll()).filteredOn(r -> ToolApprovalRequest.REPLACED.equals(r.getStatus())).hasSize(5);
    }

    @Test
    @DisplayName("an agent withdraws a request of its own person's within its trees, and the asking agent hears so")
    void withdraw() {
        String id = park("conv-reply-writer", "u-1", "c3", "Thanks").get("requestId").asText();

        assertThat(approvals.withdraw(id, "u-2", null, "conv-lead", null).withdrawn()).as("another person's").isFalse();
        assertThat(approvals.withdraw(id, "u-1", List.of("some-other-project"), "conv-lead", null).withdrawn())
                .as("outside the trees given").isFalse();
        assertThat(approvals.withdraw("not-an-id", "u-1", null, "conv-lead", null).withdrawn()).isFalse();
        assertThat(row(id).isPending()).isTrue();

        ApprovalInbox.Withdrawal w = approvals.withdraw(id, "u-1", List.of("conv-reply-writer"), "conv-lead",
                "The person asked for warmer replies");
        assertThat(w.withdrawn()).isTrue();
        assertThat(row(id).getStatus()).isEqualTo(ToolApprovalRequest.WITHDRAWN);
        assertThat(row(id).getNote()).isEqualTo("The person asked for warmer replies");
        assertThat(inbox.takeWhere("conv-reply-writer", t -> true).get(0).text()).contains("WITHDRAWN").contains("warmer");
        assertThat(approvals.hasPending("conv-reply-writer")).isFalse();
        assertThat(approvals.withdraw(id, "u-1", null, "conv-lead", null).withdrawn()).as("once").isFalse();
        assertThat(approvals.forTrees(List.of("conv-reply-writer"), ApprovalInbox.Show.DECIDED, 0, 20).getContent())
                .extracting(ApprovalInbox.View::status).containsExactly(ToolApprovalRequest.WITHDRAWN);
    }

    @Test
    @DisplayName("a request kept without its context is looked up again when listed, and then shows it")
    void missingContextIsFilledIn() {
        replies.down.set(true);
        String id = park("conv-1", "u-1", "c3", "Thanks").get("requestId").asText();
        assertThat(row(id).getContext()).isNull();
        // Listed while the service is still down: pending, and asked once more.
        ApprovalInbox.View shown = approvals.forTrees(List.of("conv-1"), ApprovalInbox.Show.PENDING, 0, 20).getContent().get(0);
        assertThat(shown.contextPending()).isTrue();

        replies.down.set(false);
        approvals.forTrees(List.of("conv-1"), ApprovalInbox.Show.PENDING, 0, 20);
        ApprovalInbox.View filled = approvals.forTrees(List.of("conv-1"), ApprovalInbox.Show.PENDING, 0, 20).getContent().get(0);
        assertThat(filled.context()).isNotNull();
        assertThat(filled.context().fields()).extracting(Field::key).containsExactly("video", "viewer", "comment", "reply");
        assertThat(filled.contextPending()).isFalse();
    }

    @Test
    @DisplayName("the minute's pass fills in what nobody listed, and a lookup that never answers stops after its attempts")
    void fillingIsBounded() {
        replies.down.set(true);
        String id = park("conv-1", "u-1", "c3", "Thanks").get("requestId").asText();
        int before = replies.asked.get();
        for (int i = 0; i < ApprovalInbox.CONTEXT_ATTEMPTS + 3; i++) approvals.fillMissingContexts();
        assertThat(replies.asked.get() - before).isEqualTo(ApprovalInbox.CONTEXT_ATTEMPTS - 1);
        assertThat(approvals.forTrees(List.of("conv-1"), ApprovalInbox.Show.PENDING, 0, 20).getContent().get(0).contextPending())
                .isFalse();
        assertThat(row(id).isPending()).as("it still waits, with its arguments").isTrue();

        replies.down.set(false);
        String other = park("conv-2", "u-1", "c4", "Hi").get("requestId").asText();
        assertThat(row(other).getContext()).isNotNull();
    }

    List<ApprovalInbox.View> decided(String... conversations) {
        return approvals.forTrees(List.of(conversations), ApprovalInbox.Show.DECIDED, 0, 20).getContent();
    }

    @Test
    @DisplayName("a draft withdrawn without its context is looked up again when Decided lists it - never by the minute's pass")
    void decidedRequestIsFilledInWhenListed() {
        replies.down.set(true);
        String id = park("conv-1", "u-1", "c3", "Thanks").get("requestId").asText();
        assertThat(approvals.withdraw(id, "u-1", null, "conv-1", null).withdrawn()).isTrue();
        int before = replies.asked.get();
        approvals.fillMissingContexts();
        assertThat(replies.asked.get()).as("old history costs the minute's pass nothing").isEqualTo(before);

        assertThat(decided("conv-1").get(0).contextPending()).as("listed while the service is down").isTrue();
        replies.down.set(false);
        ApprovalInbox.View filled = decided("conv-1").get(0);
        assertThat(filled.status()).isEqualTo(ToolApprovalRequest.WITHDRAWN);
        assertThat(filled.context()).isNotNull();
        assertThat(filled.context().fields()).extracting(Field::key).containsExactly("video", "viewer", "comment", "reply");
        assertThat(filled.contextPending()).isFalse();
    }

    @Test
    @DisplayName("a decided request whose lookup never answers is asked its few attempts in all, then shown with its arguments")
    void decidedLookupIsBounded() {
        replies.down.set(true);
        String id = park("conv-1", "u-1", "c3", "Thanks").get("requestId").asText();
        approvals.withdraw(id, "u-1", null, "conv-1", null);
        int before = replies.asked.get();
        for (int i = 0; i < ApprovalInbox.CONTEXT_ATTEMPTS + 3; i++) decided("conv-1");
        assertThat(replies.asked.get() - before).isEqualTo(ApprovalInbox.CONTEXT_ATTEMPTS - 1);
        assertThat(decided("conv-1").get(0).contextPending()).isFalse();
        assertThat(row(id).getContext()).isNull();
    }

    @Test
    @DisplayName("a draft replaced while the service failed borrows its revision's context with its own words, never another person's")
    void decidedRequestBorrowsItsRevisionsContext() {
        replies.down.set(true);
        String first = park("conv-1", "u-1", "c3", "First words").get("requestId").asText();
        replies.down.set(false);
        park("conv-2", "u-1", "c3", "Second words");
        assertThat(row(first).getStatus()).isEqualTo(ToolApprovalRequest.REPLACED);
        replies.down.set(true);

        ApprovalInbox.View shown = decided("conv-1", "conv-2").get(0);
        assertThat(shown.id()).isEqualTo(first);
        assertThat(shown.context()).as("borrowed: a database read, no lookup answered").isNotNull();
        assertThat(shown.context().fields()).extracting(Field::key).containsExactly("video", "viewer", "comment", "reply");
        assertThat(shown.context().editable().get("text").value()).isEqualTo("First words");
        assertThat(shown.subject()).isEqualTo("youtube-comment:c3");

        replies.down.set(false);
        park("conv-9", "u-2", "c9", "Theirs");
        replies.down.set(true);
        String mine = park("conv-3", "u-1", "c9", "Mine").get("requestId").asText();
        approvals.withdraw(mine, "u-1", null, "conv-3", null);
        for (int i = 0; i < ApprovalInbox.CONTEXT_ATTEMPTS + 1; i++) decided("conv-3");
        assertThat(row(mine).getContext()).as("another person's request lends nothing").isNull();
    }

    @Test
    @DisplayName("a write no provider describes is never looked up again")
    void undescribedToolIsNotRetried() {
        approvals.park("conv-1", "u-1", new ToolCall("call-1", "function", "createJiraIssue", "{\"summary\":\"x\"}"));
        int before = replies.asked.get();
        approvals.fillMissingContexts();
        assertThat(replies.asked.get()).isEqualTo(before);
        assertThat(approvals.forTrees(List.of("conv-1"), ApprovalInbox.Show.PENDING, 0, 20).getContent().get(0).contextPending())
                .isFalse();
    }

    @Test
    @DisplayName("a revised draft whose own lookup failed shows the replaced draft's context with its own words")
    void revisionCarriesTheContext() {
        park("conv-1", "u-1", "c3", "First words");
        replies.down.set(true);
        String id = park("conv-2", "u-1", "c3", "Second words").get("requestId").asText();
        ApprovalContext c = ApprovalContext.fromJson(row(id).getContext()).orElseThrow();
        assertThat(c.fields()).extracting(Field::key).containsExactly("video", "viewer", "comment", "reply");
        assertThat(c.editable().get("text").value()).isEqualTo("Second words");
    }

    @Test
    @DisplayName("a reply that ran links its words to where it was posted, so no raw result needs showing")
    void postedReplyIsLinked() {
        String id = park("conv-1", "u-1", "c3", "Thanks").get("requestId").asText();
        when(chat.callTools(anyString(), anyList(), any())).thenAnswer(inv -> {
            List<ToolCall> calls = inv.getArgument(1);
            return ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(calls.get(0).id(),
                    REPLY, "{\"success\":true,\"commentId\":\"c3\",\"replyId\":\"c3.r9\","
                    + "\"link\":\"https://www.youtube.com/watch?v=v9&lc=c3.r9\"}"))).build();
        });
        approvals.decide(row(id), ApprovalInbox.Decision.APPROVE, null, "u-1");
        ApprovalContext c = ApprovalContext.fromJson(row(id).getContext()).orElseThrow();
        assertThat(c.editable().get("text").href()).isEqualTo("https://www.youtube.com/watch?v=v9&lc=c3.r9");
        assertThat(ApprovalInbox.linked(row(id).getContext(), "{\"success\":true}")).as("no link, no change")
                .isEqualTo(row(id).getContext());
    }
}
