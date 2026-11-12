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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * ONE SOURCE OF TRUTH FOR APPROVALS (2026-11-04, the maintainer: "Status seems outdated between agents and actual
 * status" - a lead told the person "37 items waiting" and "16 approved drafts not posted", counted from its team's
 * own notes). ApprovalInbox.figures counts one person's requests of a project's trees as they stand - waiting, held
 * for a limit and when retried, and in the last 24 hours made, failed and withdrawn with the commonest reasons -
 * which the team status gives every saved agent (TeamStatus.approvalsLine). Against H2, through Spring Data's own
 * repository proxy, so the JPQL is the one a database runs.
 */
class ApprovalFiguresTest {

    static final String REPLY = "replyToYouTubeComment";

    @TempDir
    Path data;

    private ChatJpa jpa;
    private ToolApprovalRequestRepository repo;
    private ApprovalInbox approvals;
    private final Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
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

    private ToolApprovalRequest request(String root, String user, String tool, String status, String ran, Duration ago) {
        ToolApprovalRequest r = new ToolApprovalRequest();
        r.setId(new UUID(0, ++n));
        r.setConversationId(root);
        r.setRootConversationId(root);
        r.setUserId(user);
        r.setToolName(tool);
        r.setArguments("{\"commentId\":\"c" + n + "\"}");
        r.setSubject("youtube-comment:c" + n);
        r.setStatus(status);
        r.setRequestedAt(now.minus(ago).minusSeconds(60));
        if (!ToolApprovalRequest.PENDING.equals(status)) r.setDecidedAt(now.minus(ago));
        if (ran != null) {
            r.setRunOutcome(ran);
            r.setRanAt(now.minus(ago));
            r.setRunAttempts(1);
        }
        return repo.save(r);
    }

    private void failed(String error, Duration ago) {
        ToolApprovalRequest r = request("conv-writer", "u-1", REPLY, ToolApprovalRequest.APPROVED, ToolApprovalRequest.RAN_FAILED, ago);
        r.setResult("{\"success\":false,\"error\":\"" + error + "\"}");
        repo.save(r);
    }

    private void withdrawn(String why, Duration ago) {
        ToolApprovalRequest r = request("conv-writer", "u-1", REPLY, ToolApprovalRequest.WITHDRAWN, null, ago);
        r.setNote(why);
        repo.save(r);
    }

    @Test
    @DisplayName("the live figures: waiting, held with its soonest retry, and the last day's made, failed and withdrawn with their commonest reasons")
    void countsAsTheyStand() {
        Duration hour = Duration.ofHours(1);
        for (int i = 0; i < 3; i++) request("conv-writer", "u-1", REPLY, ToolApprovalRequest.PENDING, null, hour);
        for (int i = 0; i < 4; i++) request("conv-writer", "u-1", REPLY, ToolApprovalRequest.APPROVED, ToolApprovalRequest.RAN_DONE, hour);
        // Held: two, retried from the sooner one's time - whenever they were approved.
        ToolApprovalRequest h1 = request("conv-writer", "u-1", REPLY, ToolApprovalRequest.APPROVED, ToolApprovalRequest.RAN_HELD,
                Duration.ofDays(3));
        h1.setRetryAt(now.plus(Duration.ofHours(5)));
        repo.save(h1);
        ToolApprovalRequest h2 = request("conv-writer", "u-1", REPLY, ToolApprovalRequest.APPROVED, ToolApprovalRequest.RAN_HELD, hour);
        h2.setRetryAt(now.plus(Duration.ofHours(7)));
        repo.save(h2);
        failed("YouTube has no comment with that commentId.", hour);
        failed("YouTube has no comment with that commentId.", hour);
        failed("YouTube refused the reply: comments are turned off on that video.", hour);
        withdrawn("Already answered on YouTube.", hour);
        withdrawn("Already answered on YouTube.", hour);
        withdrawn("The comment is gone.", hour);
        request("conv-writer", "u-1", REPLY, ToolApprovalRequest.DENIED, null, hour);
        // Older than the day, another person's, another project's: none of this person's figures.
        request("conv-writer", "u-1", REPLY, ToolApprovalRequest.APPROVED, ToolApprovalRequest.RAN_DONE, Duration.ofDays(2));
        request("conv-writer", "u-2", REPLY, ToolApprovalRequest.PENDING, null, hour);
        request("conv-other", "u-1", REPLY, ToolApprovalRequest.PENDING, null, hour);
        // An older build's approved row that ran - no outcome recorded - is never counted as being made now.
        ToolApprovalRequest legacy = request("conv-writer", "u-1", REPLY, ToolApprovalRequest.APPROVED, null, hour);
        legacy.setResult("{\"success\":true}");
        repo.save(legacy);
        // Another tool of the same person, listed after the one with more.
        request("conv-writer", "u-1", "createJiraIssue", ToolApprovalRequest.PENDING, null, hour);

        List<ApprovalInbox.Figures> out = approvals.figures(List.of("conv-writer"), "u-1");

        assertThat(out).extracting(ApprovalInbox.Figures::toolName).containsExactly(REPLY, "createJiraIssue");
        ApprovalInbox.Figures f = out.get(0);
        assertThat(f.waiting()).isEqualTo(3);
        assertThat(f.beingEdited()).isZero();
        assertThat(f.running()).isZero();
        assertThat(f.held()).isEqualTo(2);
        assertThat(f.nextRetry()).isEqualTo(h1.getRetryAt());
        assertThat(f.posted()).isEqualTo(4);
        assertThat(f.failed()).isEqualTo(3);
        assertThat(f.withdrawn()).isEqualTo(3);
        assertThat(f.refused()).isEqualTo(1);
        assertThat(f.failures()).containsExactly(Map.entry("YouTube has no comment with that commentId.", 2L),
                Map.entry("YouTube refused the reply: comments are turned off on that video.", 1L));
        assertThat(f.withdrawals()).containsExactly(Map.entry("Already answered on YouTube.", 2L),
                Map.entry("The comment is gone.", 1L));
        assertThat(out.get(1).waiting()).isEqualTo(1);
    }

    @Test
    @DisplayName("the drafts the person is editing are counted among those waiting")
    void beingEditedIsCounted() {
        ToolApprovalRequest a = request("conv-writer", "u-1", REPLY, ToolApprovalRequest.PENDING, null, Duration.ofMinutes(5));
        request("conv-writer", "u-1", REPLY, ToolApprovalRequest.PENDING, null, Duration.ofMinutes(5));
        assertThat(approvals.holdEditing(a, "u-1")).isEqualTo(ApprovalInbox.Hold.HELD);

        ApprovalInbox.Figures f = approvals.figures(List.of("conv-writer"), "u-1").get(0);
        assertThat(f.waiting()).isEqualTo(2);
        assertThat(f.beingEdited()).isEqualTo(1);
    }

    @Test
    @DisplayName("Desktop's one person has requests with no user; no tree, no figures; and approved or withdrawn subjects are read back")
    void desktopAndSubjects() {
        request("conv-writer", null, REPLY, ToolApprovalRequest.PENDING, null, Duration.ofMinutes(5));
        ToolApprovalRequest a = request("conv-writer", null, REPLY, ToolApprovalRequest.APPROVED, ToolApprovalRequest.RAN_DONE,
                Duration.ofMinutes(5));
        assertThat(approvals.figures(List.of("conv-writer"), null)).singleElement()
                .satisfies(f -> assertThat(f.waiting() + f.posted()).isEqualTo(2));
        assertThat(approvals.figures(List.of(), "u-1")).isEmpty();
        assertThat(approvals.subjectsIn(null, Set.of(a.getSubject(), "youtube-comment:zzz"), ToolApprovalRequest.APPROVED))
                .containsExactly(a.getSubject());
    }
}
