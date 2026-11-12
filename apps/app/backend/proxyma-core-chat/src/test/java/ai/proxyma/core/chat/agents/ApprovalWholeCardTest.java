package ai.proxyma.core.chat.agents;

import ai.proxyma.core.chat.approval.ApprovalContext;
import ai.proxyma.core.chat.approval.ApprovalContext.Field;
import ai.proxyma.core.chat.approval.ApprovalContextProvider;
import ai.proxyma.core.chat.approval.ApprovalContexts;
import ai.proxyma.core.chat.model.AgentInboxEntry;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EVERY CARD IS WHOLE (2026-11-05, the maintainer, on the Approvals page: "Some requests are lack information. The
 * cards must always have all the fields. In case the agents may request without providing enough fields, those
 * requests must already auto rejected by invalid params."). A write whose tool requires fields
 * (ApprovalContextProvider.requiredFields) is refused before anything is kept when its card would lack one - for
 * certain (the comment is gone) or for now (a spent quota) alike, nothing on the Approvals page; through the
 * project's automatic approval too. And the waiting requests kept before the rule - no video, a video named by its
 * id, no card at all - are looked up again by the inbox's check: made whole where YouTube answers, WITHDRAWN with the
 * provider's reason and their agent told once where YouTube says for certain it cannot be, and left as they are where
 * it failed for now.
 */
class ApprovalWholeCardTest {

    static final String REPLY = "replyToYouTubeComment";
    static final String GONE = "Missing details: the comment could not be read.";
    static final ObjectMapper JSON = new ObjectMapper();

    ToolApprovalRequestRepository rows;
    AgentInboxRepository told;
    ApprovalInbox approvals;
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-05T08:00:00Z"));
    /** What YouTube says of each comment: "ok", "gone", "quota" or "partial" (a card without its video). */
    final Map<String, String> world = new ConcurrentHashMap<>();
    /** Off: the provider requires nothing, as before the rule - how the old, incomplete rows were kept. */
    final AtomicBoolean strict = new AtomicBoolean(true);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        rows = InMemoryRepositories.of(ToolApprovalRequestRepository.class, Map.of(
                "conversationsWithPending", (store, args) -> List.of(),
                "decide", (store, args) -> {
                    ToolApprovalRequest r = (ToolApprovalRequest) store.get(args[0]);
                    if (r == null || !r.isPending()) return 0;
                    r.setStatus((String) args[1]);
                    r.setDecidedAt((Instant) args[2]);
                    r.setDecidedBy((String) args[3]);
                    r.setNote((String) args[4]);
                    return 1;
                },
                "completeContext", (store, args) -> {
                    ToolApprovalRequest r = (ToolApprovalRequest) store.get(args[0]);
                    if (r == null || !r.isPending()) return 0;
                    r.setContext((String) args[1]);
                    return 1;
                }));
        ApplicationEventPublisher events = e -> { };
        told = InMemoryRepositories.of(AgentInboxRepository.class);
        AgentInbox inbox = new AgentInbox(told, events);
        ChatService chat = mock(ChatService.class);
        ObjectProvider<ChatService> chats = mock(ObjectProvider.class);
        when(chats.getObject()).thenReturn(chat);
        SettingsService settings = mock(SettingsService.class);
        when(settings.getSettings(SubAgentSettings.PREFIX, SubAgentSettings.class)).thenReturn(new SubAgentSettings());
        AgentTree tree = new AgentTree(InMemoryRepositories.of(AgentNodeRepository.class), inbox,
                InMemoryRepositories.of(AgentAlarmRepository.class), chats, settings, mock(ChatEventSink.class), events);
        approvals = new ApprovalInbox(rows, inbox, tree, chats);
        approvals.runInline();
        approvals.setClockForTest(now::get);
        approvals.setContexts(ApprovalContexts.of(List.of(new Replies()), Duration.ofSeconds(5)));
    }

    /** The YouTube reply's provider in miniature: its card is the video, the viewer, the comment and the reply. */
    final class Replies implements ApprovalContextProvider {
        @Override
        public Optional<ApprovalContext> contextFor(String tool, Map<String, Object> args, String conv, String user) {
            if (!REPLY.equals(tool)) return Optional.empty();
            String state = world.getOrDefault(String.valueOf(args.get("commentId")), "ok");
            if ("gone".equals(state)) throw new Unavailable("YouTube has no comment with that commentId.", GONE);
            if ("quota".equals(state)) throw new Unavailable("YouTube's daily quota is spent; try again later.", null);
            List<Field> fields = new java.util.ArrayList<>();
            if (!"partial".equals(state)) fields.add(Field.link("video", "Hoa Tau", "https://www.youtube.com/watch?v=v2"));
            fields.add(Field.link("viewer", "Minh", "https://www.youtube.com/@minh"));
            fields.add(Field.quote("comment", "Sheet music please"));
            fields.add(Field.editable("reply", String.valueOf(args.get("text")), "text"));
            return Optional.of(new ApprovalContext("YouTube", fields));
        }

        @Override
        public Set<String> requiredFields(String tool) {
            return strict.get() && REPLY.equals(tool) ? Set.of("video", "viewer", "comment", "reply") : Set.of();
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

    JsonNode park(String commentId) {
        try {
            return JSON.readTree(approvals.park("conv-writer", "u-1", call(commentId)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static ToolCall call(String commentId) {
        return new ToolCall("call-" + UUID.randomUUID(), "function", REPLY,
                "{\"commentId\":\"" + commentId + "\",\"text\":\"Cam on ban\"}");
    }

    ToolApprovalRequest row(String id) {
        return rows.findById(UUID.fromString(id)).orElseThrow();
    }

    @Test
    @DisplayName("a whole card is kept with the request; one YouTube says is gone, or cannot read for now, is refused and nothing is kept")
    void refusedBeforeItIsKept() {
        JsonNode kept = park("c-ok");
        assertThat(kept.get("status").asText()).isEqualTo("WAITING_FOR_APPROVAL");
        assertThat(ApprovalContext.fromJson(row(kept.get("requestId").asText()).getContext()).orElseThrow().fields())
                .extracting(Field::key).containsExactly("video", "viewer", "comment", "reply");

        world.put("c-gone", "gone");
        JsonNode gone = park("c-gone");
        assertThat(gone.get("status").asText()).isEqualTo("REFUSED");
        assertThat(gone.get("error").asText()).startsWith("YouTube has no comment with that commentId.")
                .endsWith("Nothing was kept or changed.");
        assertThat(gone.has("requestId")).isFalse();

        world.put("c-quota", "quota");
        assertThat(park("c-quota").get("error").asText()).startsWith("YouTube's daily quota is spent");

        world.put("c-partial", "partial");
        assertThat(park("c-partial").get("error").asText()).contains("missing: video");

        assertThat(rows.findAll()).as("only the whole one").hasSize(1);
    }

    @Test
    @DisplayName("approved automatically, a card that cannot be whole is refused the same: no row, nothing made")
    void automaticApprovalRefusedToo() {
        world.put("c-gone", "gone");
        ApprovalInbox.AutoApproval auto = approvals.approveAutomatically("conv-writer", "u-1", call("c-gone"),
                ApprovalInbox.AUTO_PROJECT);
        assertThat(auto.proceed()).isFalse();
        assertThat(auto.refusal()).contains("REFUSED").contains("YouTube has no comment with that commentId.");
        assertThat(rows.findAll()).isEmpty();

        assertThat(approvals.approveAutomatically("conv-writer", "u-1", call("c-ok"), ApprovalInbox.AUTO_PROJECT).proceed())
                .isTrue();
    }

    @Test
    @DisplayName("the check: an old card without its video is made whole, one whose comment is gone is withdrawn and its agent told once, one YouTube cannot read now stays")
    void waitingCardsCompletedOrWithdrawn() {
        // Kept before the rule: cards lacking the video, and one for a comment whose card YouTube cannot read now.
        strict.set(false);
        world.put("c-fixable", "partial");
        world.put("c-deleted", "partial");
        world.put("c-later", "partial");
        String fixable = park("c-fixable").get("requestId").asText();
        String deleted = park("c-deleted").get("requestId").asText();
        String later = park("c-later").get("requestId").asText();
        String whole = park("c-whole").get("requestId").asText();
        assertThat(ApprovalContext.fromJson(row(fixable).getContext()).orElseThrow().fields())
                .extracting(Field::key).doesNotContain("video");
        String wholeContext = row(whole).getContext();

        // The rule in force; YouTube now answers the first, says the second is gone, and fails the third for now.
        strict.set(true);
        world.put("c-fixable", "ok");
        world.put("c-deleted", "gone");
        world.put("c-later", "quota");
        int withdrawn = approvals.recheckWaiting("u-1");

        assertThat(withdrawn).isEqualTo(1);
        assertThat(row(fixable).isPending()).isTrue();
        assertThat(ApprovalContext.fromJson(row(fixable).getContext()).orElseThrow().fields())
                .extracting(Field::key).containsExactly("video", "viewer", "comment", "reply");
        assertThat(row(deleted).getStatus()).isEqualTo(ToolApprovalRequest.WITHDRAWN);
        assertThat(row(deleted).getNote()).isEqualTo(GONE);
        assertThat(row(deleted).getDecidedBy()).as("nobody decided it").isNull();
        assertThat(row(later).isPending()).as("a limit may pass: never withdrawn for it").isTrue();
        assertThat(row(whole).getContext()).as("a whole card is not looked up again").isEqualTo(wholeContext);
        assertThat(told.findAll()).as("the drafting agent told once, why").singleElement().satisfies(e -> {
            assertThat(e.getConversationId()).isEqualTo("conv-writer");
            assertThat(e.getText()).contains("WITHDRAWN").contains(GONE).contains("YouTube has no comment with that commentId.");
        });

        // The next check, past the throttle, tells nobody again.
        now.set(now.get().plus(ApprovalInbox.RECHECK_EVERY).plusSeconds(1));
        assertThat(approvals.recheckWaiting("u-1")).isZero();
        assertThat(told.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("a card naming its video only by the id in its own link lacks the video; a title does not")
    void videoNamedByItsIdIsMissing() {
        Set<String> needs = Set.of("video", "comment");
        ApprovalContext byId = new ApprovalContext("YouTube", List.of(
                Field.link("video", "8QFVNhzw5GE", "https://www.youtube.com/watch?v=8QFVNhzw5GE"),
                Field.quote("comment", "Hi")));
        ApprovalContext byTitle = new ApprovalContext("YouTube", List.of(
                Field.link("video", "Biet Tri Ky - dizi cover", "https://www.youtube.com/watch?v=8QFVNhzw5GE"),
                Field.quote("comment", "Hi")));
        assertThat(ApprovalContexts.missing(needs, byId)).containsExactly("video");
        assertThat(ApprovalContexts.missing(needs, byTitle)).isEmpty();
        assertThat(ApprovalContexts.missing(needs, null)).containsExactlyInAnyOrderElementsOf(needs);
        assertThat(ApprovalContexts.missing(Set.of(), null)).as("a tool that requires nothing").isEmpty();
    }

    @Test
    @DisplayName("an old row whose video is named by its id is looked up again and made whole")
    void oldVideoIdCardMadeWhole() {
        String id = park("c-old").get("requestId").asText();
        ToolApprovalRequest r = row(id);
        r.setContext(new ApprovalContext("YouTube", List.of(
                Field.link("video", "2mVcl_pLhK0", "https://www.youtube.com/watch?v=2mVcl_pLhK0"),
                Field.link("viewer", "Minh", "https://www.youtube.com/@minh"),
                Field.quote("comment", "Sheet music please"),
                Field.editable("reply", "Cam on ban", "text"))).toJson());

        assertThat(approvals.recheckWaiting("u-1")).isZero();
        assertThat(ApprovalContext.fromJson(row(id).getContext()).orElseThrow().fields().get(0).value()).isEqualTo("Hoa Tau");
        assertThat(told.findAll()).extracting(AgentInboxEntry::getConversationId).isEmpty();
    }
}
