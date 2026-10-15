package ai.proxyma.core.chat.controller;

import ai.proxyma.contracts.feedback.FeedbackAttachmentKind;
import ai.proxyma.core.chat.dto.FeedbackRequest;
import ai.proxyma.core.chat.model.Conversation;
import ai.proxyma.core.chat.model.Feedback;
import ai.proxyma.core.chat.repository.FeedbackAttachmentRepository;
import ai.proxyma.core.chat.repository.FeedbackRepository;
import ai.proxyma.core.chat.service.ChatService;
import ai.proxyma.core.chat.service.SmartChatMemory;
import ai.proxyma.core.telemetryclient.FeedbackShipper;
import ai.proxyma.core.telemetryclient.LogTailProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SEC-50: feedback names only a conversation the caller can open, and the server log leaves only
 * with a super admin's feedback on a multi-user server.
 *
 * <p>POST /api/v1/feedback is open to visitors. It attached the row to whatever conversationId it
 * was given - so a retracted rating or a deleted conversation then acted on someone else's - and
 * {@code includeLogs=true} from an anonymous caller shipped the server's log tail, which on a
 * multi-user server is every user's activity, to telemetry.
 *
 * <p>Driven through {@code submit()} itself rather than the private checks, because the bug was
 * never in a check - it was a check that did not exist, and only the endpoint shows whether the
 * one that now exists is actually consulted.
 */
class FeedbackScopeTest {

    private static final String ALICE_CONV = "conv-alice";

    private FeedbackRepository feedbackRepository;
    private FeedbackShipper shipper;
    private LogTailProvider logTail;
    private ChatService chatService;
    private SmartChatMemory chatMemory;
    private ai.proxyma.core.chat.quota.GuestChatQuotaService guestQuota;
    private FeedbackController controller;
    private final List<Feedback> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        feedbackRepository = mock(FeedbackRepository.class);
        when(feedbackRepository.save(any())).thenAnswer(inv -> {
            Feedback f = inv.getArgument(0);
            if (f.getId() == null) f.setId(1L);
            saved.add(f);
            return f;
        });
        FeedbackAttachmentRepository attachments = mock(FeedbackAttachmentRepository.class);
        when(attachments.findByFeedbackIdIn(anyList())).thenReturn(List.of());
        shipper = mock(FeedbackShipper.class);
        when(shipper.ship(any(), anyList())).thenReturn(true);
        logTail = mock(LogTailProvider.class);
        when(logTail.readTail()).thenReturn("2026-09-25 INFO every user's activity");
        when(logTail.fileName()).thenReturn("proxyma.log");
        chatService = mock(ChatService.class);
        Conversation alices = new Conversation();
        alices.setUserId("alice");
        // Any other id is unknown, the way ChatService.getConversation reports it.
        when(chatService.getConversation(anyString())).thenAnswer(inv -> {
            if (ALICE_CONV.equals(inv.getArgument(0))) return alices;
            throw new NoSuchElementException();
        });
        chatMemory = mock(SmartChatMemory.class);
        when(chatMemory.isMultiUser()).thenReturn(true);
        // A server with guest chat ON, so a visitor reaches the checks below; the door itself is
        // pinned by visitorsAreRefusedWhereGuestChatIsOff.
        guestQuota = mock(ai.proxyma.core.chat.quota.GuestChatQuotaService.class);
        when(guestQuota.isAnonymousChatAllowed()).thenReturn(true);
        controller = new FeedbackController(feedbackRepository, attachments, new ObjectMapper(),
                shipper, logTail, chatService, chatMemory, guestQuota);
    }

    private static FeedbackRequest request(String conversationId, boolean includeLogs) {
        return new FeedbackRequest("NEUTRAL", "the answer was wrong", true, null, includeLogs,
                conversationId, 42L, false, null, null, null, false);
    }

    /** Submits as nobody - a visitor with no principal and no security context. */
    private void submitAnonymously(FeedbackRequest request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/feedback"));
        controller.submit(request, null, exchange).block();
    }

    /** Submits as {@code user} holding exactly {@code role}, as JwtAuthFilter would present them. */
    private void submitAs(String user, String role, FeedbackRequest request) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                user, null, List.of(new SimpleGrantedAuthority(role)));
        // JwtAuthFilter always sets the display name as the details; submit() maps it and a null
        // there would fail the request before any check under test is reached.
        auth.setDetails(user);
        MockServerWebExchange exchange = MockServerWebExchange.builder(
                MockServerHttpRequest.post("/api/v1/feedback")).principal(auth).build();
        controller.submit(request, null, exchange)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                .block();
    }

    private Feedback lastSaved() {
        assertThat(saved).isNotEmpty();
        return saved.get(saved.size() - 1);
    }

    @SuppressWarnings("unchecked")
    private List<FeedbackShipper.Attachment> shippedAttachments() {
        ArgumentCaptor<List<FeedbackShipper.Attachment>> captor = ArgumentCaptor.forClass(List.class);
        verify(shipper).ship(any(), captor.capture());
        return captor.getValue();
    }

    private static boolean hasLog(List<FeedbackShipper.Attachment> attachments) {
        return attachments.stream().anyMatch(a -> a.kind() == FeedbackAttachmentKind.LOG);
    }

    @Test
    @DisplayName("a visitor's feedback naming someone else's conversation is kept, but detached from it")
    void anonymousFeedbackCannotNameAnotherUsersConversation() {
        submitAnonymously(request(ALICE_CONV, false));

        assertThat(lastSaved().getConversationId()).isNull();
        assertThat(lastSaved().getMessageId()).isNull();
        assertThat(lastSaved().getComment()).isEqualTo("the answer was wrong");
    }

    @Test
    @DisplayName("another signed-in user cannot attach feedback to a conversation they cannot open")
    void anotherUserCannotNameTheConversation() {
        submitAs("mallory", "ROLE_USER", request(ALICE_CONV, false));

        assertThat(lastSaved().getConversationId()).isNull();
        assertThat(lastSaved().getMessageId()).isNull();
    }

    @Test
    @DisplayName("a conversation id that does not exist is not recorded either")
    void unknownConversationIsNotRecorded() {
        submitAs("alice", "ROLE_USER", request("conv-nobody", false));

        assertThat(lastSaved().getConversationId()).isNull();
    }

    @Test
    @DisplayName("the owner's feedback keeps its conversation and message")
    void ownerKeepsTheReference() {
        submitAs("alice", "ROLE_USER", request(ALICE_CONV, false));

        assertThat(lastSaved().getConversationId()).isEqualTo(ALICE_CONV);
        assertThat(lastSaved().getMessageId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("a visitor's feedback on a multi-user server stays on the server - and never reads the log")
    void anonymousFeedbackStaysHome() {
        submitAnonymously(request(null, true));

        verify(shipper, never()).ship(any(), anyList());
        verify(logTail, never()).readTail();
        assertThat(lastSaved().isDeliveredToProxyma()).isFalse();
    }

    @Test
    @DisplayName("where guest chat is off, a visitor cannot send feedback at all, and nothing is stored (swx-05)")
    void visitorsAreRefusedWhereGuestChatIsOff() {
        when(guestQuota.isAnonymousChatAllowed()).thenReturn(false);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/feedback"));

        var response = controller.submit(request(null, false), null, exchange).block();

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(saved).isEmpty();
        verify(shipper, never()).ship(any(), anyList());
    }

    @Test
    @DisplayName("an admin who is not the super admin cannot ship the server log either")
    void adminCannotShipTheServerLog() {
        submitAs("bob", "ROLE_ADMIN", request(null, true));

        assertThat(hasLog(shippedAttachments())).isFalse();
        verify(logTail, never()).readTail();
    }

    @Test
    @DisplayName("the super admin on a multi-user server can ship the server log")
    void superAdminShipsTheServerLog() {
        submitAs("root", "ROLE_SUPER_ADMIN", request(null, true));

        assertThat(hasLog(shippedAttachments())).isTrue();
    }

    @Test
    @DisplayName("on a single-user edition the log is the one person's own and still ships")
    void singleUserEditionShipsItsOwnLog() {
        when(chatMemory.isMultiUser()).thenReturn(false);

        submitAnonymously(request(null, true));

        assertThat(hasLog(shippedAttachments())).isTrue();
    }
    private static FeedbackRequest rating() {
        return new FeedbackRequest("NEUTRAL", null, true, null, false,
                null, null, false, null, null, null, true);
    }

    @Test
    @DisplayName("on a multi-user server another user's answer rating stays on the server")
    void anotherUsersRatingStaysHome() {
        when(shipper.ratingsMayLeave()).thenReturn(true);

        submitAs("bob", "ROLE_USER", rating());

        verify(shipper, never()).ship(any(), anyList());
        assertThat(lastSaved().isDeliveredToProxyma()).isFalse();
    }

    @Test
    @DisplayName("the super admin who turned sharing on sends their own ratings")
    void superAdminsRatingLeaves() {
        when(shipper.ratingsMayLeave()).thenReturn(true);

        submitAs("root", "ROLE_SUPER_ADMIN", rating());

        verify(shipper).ship(any(), anyList());
    }
}
