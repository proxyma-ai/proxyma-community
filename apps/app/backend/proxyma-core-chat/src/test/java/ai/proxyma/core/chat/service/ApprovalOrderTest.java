package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.agents.AgentInbox;
import ai.proxyma.core.chat.agents.AgentTree;
import ai.proxyma.core.chat.agents.ApprovalInbox;
import ai.proxyma.core.chat.model.ToolApprovalRequest;
import ai.proxyma.core.chat.repository.ToolApprovalRequestRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * THE APPROVALS PAGE LISTS ITS ROWS IN ONE ORDER (2026-11-03, the maintainer: "the list items jump
 * around, up down, randomly like every 5-10s"). The page reads the inbox again every few seconds; the
 * list was ordered by the time a request was asked alone, so requests of the same instant - a burst of
 * drafts - could come back in another order on every read, and a page boundary could show one twice
 * and another never. Each list now ends its order on the id, and the Decided tab is ordered by the time
 * it shows - when a request was decided - rather than when it was asked.
 *
 * <p>Against H2, through Spring Data's own repository proxy, so the Pageable's sort is the one a
 * database applies.
 */
class ApprovalOrderTest {

    private static final Instant T = Instant.parse("2026-11-03T08:00:00Z");

    @TempDir
    Path data;

    private ChatJpa jpa;
    private ToolApprovalRequestRepository repo;
    private ApprovalInbox approvals;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jpa = new ChatJpa(data, true);
        repo = jpa.get(ToolApprovalRequestRepository.class);
        approvals = new ApprovalInbox(repo, mock(AgentInbox.class), mock(AgentTree.class), mock(ObjectProvider.class));
    }

    @AfterEach
    void tearDown() {
        if (jpa != null) jpa.close();
    }

    private static UUID id(int n) {
        return new UUID(0, n);
    }

    private void request(int n, String user, String status, Instant requestedAt, Instant decidedAt) {
        ToolApprovalRequest r = new ToolApprovalRequest();
        r.setId(id(n));
        r.setConversationId("conv-1");
        r.setRootConversationId("conv-1");
        r.setUserId(user);
        r.setToolName("replyToYouTubeComment");
        r.setArguments("{\"commentId\":\"c" + n + "\",\"text\":\"Thanks\"}");
        r.setStatus(status);
        r.setRequestedAt(requestedAt);
        r.setDecidedAt(decidedAt);
        repo.save(r);
    }

    private static List<UUID> ids(List<ApprovalInbox.View> views) {
        return views.stream().map(v -> UUID.fromString(v.id())).toList();
    }

    // Every page of the list, in turn, at this page size.
    private List<UUID> paged(ApprovalInbox.Show show, int size) {
        List<UUID> out = new ArrayList<>();
        for (int page = 0; page < 10; page++) {
            List<ApprovalInbox.View> rows = approvals.forTrees(List.of("conv-1"), show, page, size).getContent();
            if (rows.isEmpty()) break;
            out.addAll(ids(rows));
        }
        return out;
    }

    @Test
    @DisplayName("waiting requests: newest first, those of the same instant by id, the same on every read and across pages")
    void waitingOrderIsStable() {
        // Saved out of order, three of them in the same microsecond.
        request(2, "u-1", ToolApprovalRequest.PENDING, T, null);
        request(9, "u-1", ToolApprovalRequest.PENDING, T.minusSeconds(1), null);
        request(5, "u-1", ToolApprovalRequest.PENDING, T, null);
        request(1, "u-1", ToolApprovalRequest.PENDING, T.plusSeconds(1), null);
        request(7, "u-1", ToolApprovalRequest.PENDING, T, null);
        request(3, "u-1", ToolApprovalRequest.APPROVED, T.plusSeconds(5), T.plusSeconds(6));

        List<UUID> expected = List.of(id(1), id(7), id(5), id(2), id(9));
        for (int read = 0; read < 3; read++) {
            assertThat(ids(approvals.forTrees(List.of("conv-1"), ApprovalInbox.Show.PENDING, 0, 20).getContent()))
                    .containsExactlyElementsOf(expected);
        }
        assertThat(paged(ApprovalInbox.Show.PENDING, 2)).as("no row twice, none missed, across pages")
                .containsExactlyElementsOf(expected);
        assertThat(ids(approvals.forUser("u-1", ApprovalInbox.Show.PENDING, 0, 20).getContent()))
                .containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("decided requests: by when they were decided - the time the row shows - then when asked, then id")
    void decidedOrderIsTheTimeShown() {
        // Asked in one order, decided in another: the page shows when each was decided.
        request(1, null, ToolApprovalRequest.WITHDRAWN, T.minusSeconds(60), T.minusSeconds(30));
        request(2, null, ToolApprovalRequest.APPROVED, T.minusSeconds(120), T.plusSeconds(10));
        request(3, null, ToolApprovalRequest.APPROVED, T.minusSeconds(90), T.plusSeconds(10));
        request(4, null, ToolApprovalRequest.REPLACED, T.minusSeconds(90), T.plusSeconds(10));
        request(5, null, ToolApprovalRequest.DENIED, T.minusSeconds(10), T.minusSeconds(5));
        request(6, null, ToolApprovalRequest.PENDING, T.plusSeconds(30), null);

        // 3 and 4 tie on both times; 2 was asked earlier than them.
        List<UUID> expected = List.of(id(4), id(3), id(2), id(5), id(1));
        for (int read = 0; read < 3; read++) {
            assertThat(ids(approvals.forUser(null, ApprovalInbox.Show.DECIDED, 0, 20).getContent()))
                    .containsExactlyElementsOf(expected);
        }
        assertThat(paged(ApprovalInbox.Show.DECIDED, 2)).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("the stored instant keeps its microseconds, so the order is not decided by a rounding")
    void instantsAreKept() {
        Instant fine = T.plus(123_456, ChronoUnit.MICROS);
        request(1, "u-1", ToolApprovalRequest.PENDING, fine, null);
        request(2, "u-1", ToolApprovalRequest.PENDING, fine.minus(1, ChronoUnit.MICROS), null);
        assertThat(ids(approvals.forTrees(List.of("conv-1"), ApprovalInbox.Show.PENDING, 0, 20).getContent()))
                .containsExactly(id(1), id(2));
    }
}
