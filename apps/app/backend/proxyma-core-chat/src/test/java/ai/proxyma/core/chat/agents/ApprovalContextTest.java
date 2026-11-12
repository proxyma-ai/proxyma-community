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
import ai.proxyma.core.search.domain.SearchContext;
import ai.proxyma.core.settings.dto.SubAgentSettings;
import ai.proxyma.core.settings.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WHAT AN APPROVAL SHOWS: the context a provider builds when a write is kept is stored with the request
 * and handed back with it; a provider that throws, is slow or answers nothing leaves the request with
 * no context - created all the same, at once; and every context is held to what a page may be handed.
 */
class ApprovalContextTest {

    static final String CONV = "conv-run";
    static final String REPLY = "replyToYouTubeComment";
    static final String ARGS = "{\"commentId\":\"c3\",\"text\":\"The sheet is on the site.\"}";

    ToolApprovalRequestRepository rows;
    ApprovalInbox approvals;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        rows = InMemoryRepositories.of(ToolApprovalRequestRepository.class);
        ApplicationEventPublisher events = e -> { };
        AgentInbox inbox = new AgentInbox(InMemoryRepositories.of(AgentInboxRepository.class), events);
        ObjectProvider<ChatService> chats = mock(ObjectProvider.class);
        when(chats.getObject()).thenReturn(mock(ChatService.class));
        SettingsService settings = mock(SettingsService.class);
        when(settings.getSettings(SubAgentSettings.PREFIX, SubAgentSettings.class)).thenReturn(new SubAgentSettings());
        AgentTree tree = new AgentTree(InMemoryRepositories.of(AgentNodeRepository.class), inbox,
                InMemoryRepositories.of(AgentAlarmRepository.class), chats, settings, mock(ChatEventSink.class), events);
        approvals = new ApprovalInbox(rows, inbox, tree, chats);
    }

    ToolApprovalRequest park(ApprovalContexts contexts) {
        approvals.setContexts(contexts);
        String out = approvals.park(CONV, "u-1", new ToolCall("call-1", "function", REPLY, ARGS));
        assertThat(out).contains("WAITING_FOR_APPROVAL");
        return rows.findAll().stream().reduce((a, b) -> b).orElseThrow();
    }

    static ApprovalContext youtube(String reply) {
        return new ApprovalContext("YouTube", List.of(
                Field.link("video", "Hoa Tau Sao Truc", "https://www.youtube.com/watch?v=v2&lc=c3"),
                Field.link("viewer", "Minh", "https://www.youtube.com/channel/UCfan2"),
                Field.quote("comment", "Sheet music please"),
                Field.quote("reply", reply)));
    }

    @Test
    @DisplayName("a provider's context is stored with the request, read back with it, and asked with the call's arguments and user")
    void storedWithTheRequest() {
        AtomicReference<Map<String, Object>> seenArgs = new AtomicReference<>();
        AtomicReference<String> seenUser = new AtomicReference<>();
        AtomicReference<String> seenContextUser = new AtomicReference<>();
        ApprovalContextProvider other = (tool, args, conv, user) -> Optional.empty();
        ApprovalContextProvider provider = (tool, args, conv, user) -> {
            seenArgs.set(args);
            seenUser.set(user);
            seenContextUser.set(SearchContext.getUserId() + "/" + SearchContext.getConversationId());
            return REPLY.equals(tool) ? Optional.of(youtube(String.valueOf(args.get("text")))) : Optional.empty();
        };

        ToolApprovalRequest r = park(ApprovalContexts.of(List.of(other, provider), Duration.ofSeconds(5)));

        assertThat(r.getContext()).contains("\"key\":\"video\"").contains("Sheet music please");
        assertThat(seenArgs.get()).containsEntry("commentId", "c3");
        assertThat(seenUser.get()).isEqualTo("u-1");
        assertThat(seenContextUser.get()).as("the lookup runs as the conversation's user, in it").isEqualTo("u-1/" + CONV);

        ApprovalInbox.View view = approvals.forTrees(List.of(CONV), ApprovalInbox.Show.PENDING, 0, 20).getContent().get(0);
        assertThat(view.context().fields()).extracting(Field::key).containsExactly("video", "viewer", "comment", "reply");
        assertThat(view.context().fields().get(3)).satisfies(f -> {
            assertThat(f.value()).isEqualTo("The sheet is on the site.");
            assertThat(f.kind()).isEqualTo(ApprovalContext.QUOTE);
        });
        assertThat(view.context().fields().get(0).href()).isEqualTo("https://www.youtube.com/watch?v=v2&lc=c3");
    }

    @Test
    @DisplayName("a provider that throws leaves the context null, and the request is still kept")
    void throwingProvider() {
        ApprovalContextProvider broken = (tool, args, conv, user) -> {
            throw new IllegalStateException("YouTube said no");
        };
        ToolApprovalRequest r = park(ApprovalContexts.of(List.of(broken), Duration.ofSeconds(5)));
        assertThat(r.getContext()).isNull();
        assertThat(r.isPending()).isTrue();
        assertThat(r.getArguments()).isEqualTo(ARGS);
        assertThat(approvals.hasPending(CONV)).isTrue();
        assertThat(approvals.forTrees(List.of(CONV), ApprovalInbox.Show.PENDING, 0, 20).getContent().get(0).context()).isNull();
    }

    @Test
    @DisplayName("a slow provider is given its limit and no more: no context, and the request kept in time")
    void slowProvider() {
        ApprovalContextProvider slow = (tool, args, conv, user) -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Optional.of(youtube("late"));
        };
        long started = System.nanoTime();
        ToolApprovalRequest r = park(ApprovalContexts.of(List.of(slow), Duration.ofMillis(200)));
        long ms = (System.nanoTime() - started) / 1_000_000;
        assertThat(r.getContext()).isNull();
        assertThat(r.isPending()).isTrue();
        assertThat(ms).as("the request waited for the limit, not for the provider").isLessThan(3_000);
    }

    @Test
    @DisplayName("no provider at all, or none that answers: no context, nothing else changes")
    void noProvider() {
        assertThat(park(ApprovalContexts.of(List.of(), Duration.ofSeconds(5))).getContext()).isNull();
        setUp();
        assertThat(park(null).getContext()).isNull();
    }

    @Test
    @DisplayName("a context is bounded: a link only as a whole http(s) address, values clipped and screened, empty fields dropped")
    void bounded() {
        String longText = "x".repeat(ApprovalContext.VALUE_MAX + 500);
        ApprovalContext raw = new ApprovalContext("t", List.of(
                Field.link("video", "Clip", "javascript:alert(1)"),
                Field.link("viewer", "Minh", " https://www.youtube.com/channel/UCfan2 "),
                new Field("comment", longText, null, "SHOUT"),
                Field.quote("reply", "   "),
                Field.text("path", "Saved to C:\\Users\\someone\\AppData\\Local\\proxyma\\secret.txt")));
        ApprovalContext c = raw.bounded().orElseThrow();
        assertThat(c.fields()).extracting(Field::key).containsExactly("video", "viewer", "comment", "path");
        assertThat(c.fields().get(0)).satisfies(f -> {
            assertThat(f.href()).isNull();
            assertThat(f.kind()).isEqualTo(ApprovalContext.TEXT);
        });
        assertThat(c.fields().get(1).href()).isEqualTo("https://www.youtube.com/channel/UCfan2");
        assertThat(c.fields().get(2).value()).hasSize(ApprovalContext.VALUE_MAX);
        assertThat(c.fields().get(2).kind()).isEqualTo(ApprovalContext.TEXT);
        assertThat(c.fields().get(3).value()).doesNotContain("someone");

        assertThat(ApprovalContext.fromJson(c.toJson())).contains(c);
        assertThat(ApprovalContext.fromJson("not json")).isEmpty();
        assertThat(ApprovalContext.fromJson(null)).isEmpty();
        assertThat(new ApprovalContext("t", List.of(Field.quote("reply", " "))).bounded()).isEmpty();
    }

    @Test
    @DisplayName("ApprovalContexts never throws: unreadable arguments are an empty map")
    void unreadableArguments() {
        AtomicReference<Map<String, Object>> seen = new AtomicReference<>();
        ApprovalContexts contexts = ApprovalContexts.of(List.of((tool, args, conv, user) -> {
            seen.set(args);
            return Optional.empty();
        }), Duration.ofSeconds(5));
        assertThat(contexts.resolve(REPLY, "{not json", CONV, null)).isEmpty();
        assertThat(seen.get()).isEmpty();
        assertThat(contexts.resolve(null, ARGS, CONV, null)).isEmpty();
    }
}
