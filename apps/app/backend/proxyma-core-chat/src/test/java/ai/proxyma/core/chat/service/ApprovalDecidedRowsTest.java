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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * HOW OFTEN THE PERSON STEPPED IN, ITS READ (2026-11-05, the maintainer: "We need to track the rejection, modification,
 * notes, etc. rate from the user"). ApprovalInbox.decidedSince reads a project's trees' requests approved, denied or
 * withdrawn since a moment, newest first and bounded - with only what the Constellations step-in figures need: who
 * decided (a person or the server), whether a note was written, a withdrawal's reason, and the draft and what was sent
 * for an edited request ONLY. Against H2, through Spring Data's own repository proxy, so the JPQL is the one a database
 * runs (as ApprovalFiguresTest).
 */
class ApprovalDecidedRowsTest {

    static final String REPLY = "replyToYouTubeComment";

    @TempDir
    Path data;

    private ChatJpa jpa;
    private ToolApprovalRequestRepository repo;
    private ApprovalInbox approvals;
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private int n;

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

    private ToolApprovalRequest request(String root, String status, String by, Duration ago) {
        ToolApprovalRequest r = new ToolApprovalRequest();
        r.setId(new UUID(0, ++n));
        r.setConversationId(root + "-child");
        r.setRootConversationId(root);
        r.setUserId(n % 2 == 0 ? "u-1" : null);
        r.setToolName(REPLY);
        r.setArguments("{\"commentId\":\"c" + n + "\",\"text\":\"Thanks for watching!\"}");
        r.setSubject("youtube-comment:c" + n);
        r.setStatus(status);
        r.setRequestedAt(now.minus(ago).minusSeconds(60));
        if (!ToolApprovalRequest.PENDING.equals(status)) r.setDecidedAt(now.minus(ago));
        r.setDecidedBy(by);
        return repo.save(r);
    }

    @Test
    @DisplayName("approved, denied and withdrawn since the moment, newest first, every person's, with the edit's two sides only when edited")
    void readsWhatTheFiguresNeed() {
        Duration hour = Duration.ofHours(1);
        ToolApprovalRequest drafted = request("conv-writer", ToolApprovalRequest.APPROVED, "u-1", hour);
        ToolApprovalRequest edited = request("conv-writer", ToolApprovalRequest.APPROVED, null, Duration.ofHours(2));
        edited.setDraftArguments("{\"commentId\":\"c2\",\"text\":\"Thanks!\"}");
        edited.setNote("Warmer, please.");
        repo.save(edited);
        ToolApprovalRequest refused = request("conv-quick", ToolApprovalRequest.DENIED, "u-1", Duration.ofHours(3));
        refused.setNote("Not this one.");
        repo.save(refused);
        request("conv-writer", ToolApprovalRequest.APPROVED, "auto:project", Duration.ofHours(4));
        ToolApprovalRequest answered = request("conv-writer", ToolApprovalRequest.WITHDRAWN, null, Duration.ofHours(5));
        answered.setNote("Already answered on YouTube.");
        repo.save(answered);
        // Not read: waiting, replaced, expired, before the moment, another project's tree.
        request("conv-writer", ToolApprovalRequest.PENDING, null, hour);
        request("conv-writer", ToolApprovalRequest.REPLACED, null, hour);
        request("conv-writer", ToolApprovalRequest.EXPIRED, null, hour);
        request("conv-writer", ToolApprovalRequest.APPROVED, "u-1", Duration.ofDays(20));
        request("conv-other", ToolApprovalRequest.APPROVED, "u-1", hour);

        List<ApprovalInbox.DecidedRow> rows = approvals.decidedSince(List.of("conv-writer", "conv-quick"),
                now.minus(Duration.ofDays(14)), 100);

        assertThat(rows).extracting(ApprovalInbox.DecidedRow::status).containsExactly(ToolApprovalRequest.APPROVED,
                ToolApprovalRequest.APPROVED, ToolApprovalRequest.DENIED, ToolApprovalRequest.APPROVED,
                ToolApprovalRequest.WITHDRAWN);
        ApprovalInbox.DecidedRow first = rows.get(0);
        assertThat(first.decidedAt()).isEqualTo(drafted.getDecidedAt());
        assertThat(first.automatic()).isFalse();
        assertThat(first.noted()).isFalse();
        assertThat(first.edited()).isFalse();
        assertThat(first.arguments()).as("what was sent is read for an edited request only").isNull();
        assertThat(first.withdrawalReason()).isNull();
        assertThat(first.toolName()).isEqualTo(REPLY);
        assertThat(first.rootConversationId()).isEqualTo("conv-writer");

        ApprovalInbox.DecidedRow e = rows.get(1);
        assertThat(e.edited()).isTrue();
        assertThat(e.noted()).isTrue();
        assertThat(e.draftArguments()).isEqualTo("{\"commentId\":\"c2\",\"text\":\"Thanks!\"}");
        assertThat(e.arguments()).isEqualTo(edited.getArguments());
        assertThat(e.withdrawalReason()).as("a note is a flag, never text, unless it is a withdrawal's reason").isNull();

        assertThat(rows.get(2).noted()).isTrue();
        assertThat(rows.get(2).rootConversationId()).isEqualTo("conv-quick");
        assertThat(rows.get(3).automatic()).isTrue();
        assertThat(rows.get(4).withdrawalReason()).isEqualTo("Already answered on YouTube.");
    }

    @Test
    @DisplayName("the read is bounded, keeps the newest, and is empty with no trees")
    void bounded() {
        for (int i = 0; i < 5; i++) request("conv-writer", ToolApprovalRequest.APPROVED, "u-1", Duration.ofMinutes(10L * (i + 1)));
        List<ApprovalInbox.DecidedRow> rows = approvals.decidedSince(List.of("conv-writer"), now.minus(Duration.ofDays(1)), 3);
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).decidedAt()).isEqualTo(now.minus(Duration.ofMinutes(10)));
        assertThat(approvals.decidedSince(List.of(), now.minus(Duration.ofDays(1)), 3)).isEmpty();
    }
}
