package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.agents.AgentInbox;
import ai.proxyma.core.chat.agents.AgentTree;
import ai.proxyma.core.chat.agents.ApprovalInbox;
import ai.proxyma.core.chat.agents.ApprovalInbox.DecidedFilter;
import ai.proxyma.core.chat.agents.ApprovalInbox.WaitingSort;
import ai.proxyma.core.chat.approval.ApprovalContext;
import ai.proxyma.core.chat.approval.ApprovalContext.Field;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * THE APPROVALS PAGE'S FILTERS AND SORTS ARE THE SERVER'S (2026-11-05, the maintainer: "The Decided page should have
 * filters, e.g. manual approved, auto approved, rejected, withdraw" and "Waiting for approval should have a sort by date,
 * by video, by user"). The list is paged at twenty, so a filter over one page in the browser would lie: the Decided list
 * is filtered and counted by the database, each filter a fixed query named by a whitelisted enum, in the Decided order
 * (ApprovalOrderTest); the Waiting list by video, viewer or agent is ordered from each request's stored context, newest
 * first within one key and by id after that, the same on every read and across pages. Against H2, through Spring Data's
 * own repository proxy, so the JPQL is the one a database runs.
 */
class ApprovalFiltersTest {

    static final Instant T = Instant.parse("2026-11-05T08:00:00Z");
    static final String ROOT = "conv-1";

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

    private ToolApprovalRequest request(int n, String root, String status, String decidedBy, String ran, int secondsAgo) {
        ToolApprovalRequest r = new ToolApprovalRequest();
        r.setId(id(n));
        r.setConversationId(root);
        r.setRootConversationId(root);
        r.setUserId("u-1");
        r.setToolName("replyToYouTubeComment");
        r.setArguments("{\"commentId\":\"c" + n + "\",\"text\":\"Thanks\"}");
        r.setStatus(status);
        r.setRequestedAt(T.minusSeconds(secondsAgo + 60L));
        if (!ToolApprovalRequest.PENDING.equals(status)) {
            r.setDecidedAt(T.minusSeconds(secondsAgo));
            r.setDecidedBy(decidedBy);
        }
        r.setRunOutcome(ran);
        return repo.save(r);
    }

    private ToolApprovalRequest waiting(int n, String root, String video, String viewer, int secondsAgo) {
        ToolApprovalRequest r = request(n, root, ToolApprovalRequest.PENDING, null, null, secondsAgo);
        if (video != null) {
            r.setContext(new ApprovalContext("YouTube", List.of(Field.link("video", video, "https://www.youtube.com/watch?v=" + n),
                    Field.text("viewer", viewer), Field.quote("comment", "Sheet music please"))).toJson());
            r = repo.save(r);
        }
        return r;
    }

    private static List<UUID> ids(List<ApprovalInbox.View> views) {
        return views.stream().map(v -> UUID.fromString(v.id())).toList();
    }

    private void decidedSet() {
        request(1, ROOT, ToolApprovalRequest.APPROVED, "u-1", ToolApprovalRequest.RAN_DONE, 10);
        request(2, ROOT, ToolApprovalRequest.APPROVED, ApprovalInbox.AUTO_PROJECT, ToolApprovalRequest.RAN_DONE, 20);
        request(3, ROOT, ToolApprovalRequest.APPROVED, ApprovalInbox.AUTO_AGENT, ToolApprovalRequest.RAN_FAILED, 30);
        request(4, ROOT, ToolApprovalRequest.APPROVED, null, ToolApprovalRequest.RAN_HELD, 40);     // Desktop: no decider
        request(5, ROOT, ToolApprovalRequest.APPROVED, ApprovalInbox.AUTO_PROJECT, ToolApprovalRequest.RAN_RETRYING, 50);
        request(6, ROOT, ToolApprovalRequest.DENIED, "u-1", null, 60);
        request(7, ROOT, ToolApprovalRequest.WITHDRAWN, null, null, 70);
        request(8, ROOT, ToolApprovalRequest.REPLACED, null, null, 80);
        request(9, ROOT, ToolApprovalRequest.EXPIRED, null, null, 90);
        request(10, ROOT, ToolApprovalRequest.APPROVED, "u-1", ToolApprovalRequest.RAN_FAILED, 100);
        request(11, ROOT, ToolApprovalRequest.PENDING, null, null, 0);
        // Another project's tree: in no count and no page.
        request(12, "conv-other", ToolApprovalRequest.APPROVED, ApprovalInbox.AUTO_PROJECT, ToolApprovalRequest.RAN_DONE, 5);
    }

    @Test
    @DisplayName("each Decided filter keeps its own rows, in the Decided order, and its count is its total")
    void filtersAndCounts() {
        decidedSet();
        Map<DecidedFilter, List<UUID>> expected = Map.of(
                DecidedFilter.ALL, List.of(id(1), id(2), id(3), id(4), id(5), id(6), id(7), id(8), id(9), id(10)),
                DecidedFilter.PERSON, List.of(id(1), id(4), id(10)),
                DecidedFilter.AUTO, List.of(id(2), id(3), id(5)),
                DecidedFilter.DENIED, List.of(id(6)),
                DecidedFilter.WITHDRAWN, List.of(id(7)),
                DecidedFilter.REPLACED, List.of(id(8)),
                DecidedFilter.EXPIRED, List.of(id(9)),
                DecidedFilter.FAILED, List.of(id(3), id(10)),
                DecidedFilter.HELD, List.of(id(4), id(5)));
        Map<DecidedFilter, Long> counts = approvals.decidedCounts(List.of(ROOT));
        for (DecidedFilter f : DecidedFilter.values()) {
            assertThat(ids(approvals.decided(List.of(ROOT), f, 0, 20).getContent())).as(f.name())
                    .containsExactlyElementsOf(expected.get(f));
            assertThat(approvals.decided(List.of(ROOT), f, 0, 20).getTotalElements()).as(f.name() + " total")
                    .isEqualTo(expected.get(f).size());
            assertThat(counts.get(f)).as(f.name() + " count").isEqualTo(expected.get(f).size());
        }
        // Held and being retried both read as held.
        assertThat(approvals.decided(List.of(ROOT), DecidedFilter.HELD, 0, 20).getContent())
                .allSatisfy(v -> assertThat(v.runOutcome()).isEqualTo(ToolApprovalRequest.RAN_HELD));
    }

    @Test
    @DisplayName("a filter pages: twenty of the filter's rows a page, none twice, none missed")
    void filtersPage() {
        for (int n = 1; n <= 45; n++) {
            request(n, ROOT, ToolApprovalRequest.APPROVED, n % 3 == 0 ? "u-1" : ApprovalInbox.AUTO_PROJECT,
                    ToolApprovalRequest.RAN_DONE, n);
        }
        List<UUID> seen = new ArrayList<>();
        for (int page = 0; page < 5; page++) {
            List<ApprovalInbox.View> rows = approvals.decided(List.of(ROOT), DecidedFilter.AUTO, page, 20).getContent();
            assertThat(rows.size()).isLessThanOrEqualTo(20);
            rows.forEach(v -> assertThat(v.autoApproved()).isEqualTo("PROJECT"));
            seen.addAll(ids(rows));
        }
        assertThat(seen).hasSize(30).doesNotHaveDuplicates();
        assertThat(approvals.decided(List.of(ROOT), DecidedFilter.AUTO, 0, 20).getTotalElements()).isEqualTo(30);
        assertThat(approvals.decidedCounts(List.of(ROOT)))
                .containsEntry(DecidedFilter.AUTO, 30L).containsEntry(DecidedFilter.PERSON, 15L).containsEntry(DecidedFilter.ALL, 45L);
    }

    @Test
    @DisplayName("about these subjects only (2026-11-07, a team page's filters by tag, viewer or video): every Decided filter and "
            + "its count keep only them, an empty set keeps nothing, and the Waiting list keeps what the owner's predicate keeps")
    void aboutSubjects() {
        decidedSet();
        for (int n : new int[]{1, 2, 3, 6, 7, 11}) {
            ToolApprovalRequest r = repo.findById(id(n)).orElseThrow();
            r.setSubject("youtube-comment:c" + n);
            repo.save(r);
        }
        java.util.Set<String> about = java.util.Set.of("youtube-comment:c1", "youtube-comment:c2", "youtube-comment:c3",
                "youtube-comment:c6");
        assertThat(ids(approvals.decided(List.of(ROOT), DecidedFilter.ALL, about, 0, 20).getContent()))
                .containsExactly(id(1), id(2), id(3), id(6));
        assertThat(ids(approvals.decided(List.of(ROOT), DecidedFilter.PERSON, about, 0, 20).getContent())).containsExactly(id(1));
        assertThat(ids(approvals.decided(List.of(ROOT), DecidedFilter.AUTO, about, 0, 20).getContent())).containsExactly(id(2), id(3));
        assertThat(ids(approvals.decided(List.of(ROOT), DecidedFilter.FAILED, about, 0, 20).getContent())).containsExactly(id(3));
        assertThat(ids(approvals.decided(List.of(ROOT), DecidedFilter.DENIED, about, 0, 20).getContent())).containsExactly(id(6));
        assertThat(approvals.decidedCounts(List.of(ROOT), about))
                .containsEntry(DecidedFilter.ALL, 4L).containsEntry(DecidedFilter.PERSON, 1L).containsEntry(DecidedFilter.AUTO, 2L)
                .containsEntry(DecidedFilter.FAILED, 1L).containsEntry(DecidedFilter.DENIED, 1L)
                .containsEntry(DecidedFilter.WITHDRAWN, 0L);
        assertThat(approvals.decided(List.of(ROOT), DecidedFilter.ALL, java.util.Set.of(), 0, 20).getTotalElements()).isZero();
        assertThat(approvals.decidedCounts(List.of(ROOT), java.util.Set.of())).containsEntry(DecidedFilter.ALL, 0L);

        // The counts a filter's choices show: decided only, by tree and subject together.
        List<ApprovalInbox.DecidedGroup> groups = approvals.decidedGroups(List.of(ROOT, "conv-other"));
        assertThat(groups).contains(new ApprovalInbox.DecidedGroup(ROOT, "youtube-comment:c7", 1L))
                .noneMatch(g -> "youtube-comment:c11".equals(g.subject()));
        assertThat(groups.stream().filter(g -> ROOT.equals(g.rootConversationId())).mapToLong(ApprovalInbox.DecidedGroup::count)
                .sum()).isEqualTo(10L);
        assertThat(groups.stream().filter(g -> "conv-other".equals(g.rootConversationId()))
                .mapToLong(ApprovalInbox.DecidedGroup::count).sum()).isEqualTo(1L);
        // The step-in figures' rows say what each was about - what a draft's confidence is checked against.
        assertThat(approvals.decidedSince(List.of(ROOT), T.minusSeconds(1_000), 100))
                .filteredOn(r -> "youtube-comment:c1".equals(r.subject())).hasSize(1);

        // Waiting: what the predicate keeps, newest first, paged, its total the kept ones'.
        for (int n = 21; n <= 25; n++) waiting(n, ROOT, null, null, n);
        java.util.function.Predicate<ToolApprovalRequest> odd = r -> r.getId().getLeastSignificantBits() % 2 == 1;
        ApprovalInbox.Sorted kept = approvals.waitingInTrees(List.of(ROOT), WaitingSort.DATE, null, odd, 0, 2);
        assertThat(kept.page().getTotalElements()).isEqualTo(4);   // 11, 21, 23, 25
        assertThat(ids(kept.page().getContent())).containsExactly(id(11), id(21));
        assertThat(ids(approvals.waitingInTrees(List.of(ROOT), WaitingSort.DATE, null, odd, 1, 2).page().getContent()))
                .containsExactly(id(23), id(25));
        assertThat(approvals.waitingInTrees(List.of(ROOT), WaitingSort.DATE, null, r -> false, 0, 20).page().getTotalElements())
                .isZero();
    }

    @Test
    @DisplayName("a filter or sort is a name from a fixed list - anything else, a column name included, is no filter")
    void whitelisted() {
        assertThat(DecidedFilter.parse("auto")).contains(DecidedFilter.AUTO);
        assertThat(DecidedFilter.parse(null)).contains(DecidedFilter.ALL);
        assertThat(DecidedFilter.parse("decidedBy")).isEmpty();
        assertThat(DecidedFilter.parse("status; drop table x")).isEmpty();
        assertThat(WaitingSort.parse("Video")).contains(WaitingSort.VIDEO);
        assertThat(WaitingSort.parse("")).contains(WaitingSort.DATE);
        assertThat(WaitingSort.parse("requestedAt")).isEmpty();
    }

    @Test
    @DisplayName("waiting by video or viewer: grouped by it, newest first within one, a request without one last; the same across reads and pages")
    void waitingSorts() {
        waiting(1, ROOT, "Biet Tri Ky - dizi cover", "@minh", 50);
        waiting(2, ROOT, "anh ba - flute", "@lan", 10);
        waiting(3, ROOT, "Biet Tri Ky - dizi cover", "@an", 30);
        waiting(4, ROOT, null, null, 5);                       // no context yet
        waiting(5, ROOT, "anh ba - flute", "@minh", 40);
        // Three asked in the same instant: the id decides between them.
        waiting(6, ROOT, "Zen", "@zed", 20);
        waiting(7, ROOT, "Zen", "@zed", 20);
        waiting(8, ROOT, "Zen", "@zed", 20);

        List<UUID> byVideo = List.of(id(2), id(5), id(3), id(1), id(8), id(7), id(6), id(4));
        List<UUID> byViewer = List.of(id(3), id(2), id(5), id(1), id(8), id(7), id(6), id(4));
        for (int read = 0; read < 3; read++) {
            ApprovalInbox.Sorted s = approvals.waitingInTrees(List.of(ROOT), WaitingSort.VIDEO, null, 0, 20);
            assertThat(s.sort()).isEqualTo(WaitingSort.VIDEO);
            assertThat(ids(s.page().getContent())).containsExactlyElementsOf(byVideo);
        }
        assertThat(ids(approvals.waitingInTrees(List.of(ROOT), WaitingSort.VIEWER, null, 0, 20).page().getContent()))
                .containsExactlyElementsOf(byViewer);
        List<UUID> paged = new ArrayList<>();
        for (int page = 0; page < 4; page++) {
            ApprovalInbox.Sorted s = approvals.waitingInTrees(List.of(ROOT), WaitingSort.VIDEO, null, page, 3);
            assertThat(s.page().getTotalElements()).isEqualTo(8);
            paged.addAll(ids(s.page().getContent()));
        }
        assertThat(paged).as("pages of three, none twice, none missed").containsExactlyElementsOf(byVideo);
        // By date it is the database's own order.
        assertThat(ids(approvals.waitingInTrees(List.of(ROOT), WaitingSort.DATE, null, 0, 20).page().getContent()))
                .containsExactly(id(4), id(2), id(8), id(7), id(6), id(3), id(5), id(1));
    }

    @Test
    @DisplayName("waiting by agent: the name each tree's owner gives its root, a deployed agent's title under it")
    void waitingByAgent() {
        waiting(1, "conv-writer", "V", "@a", 10);
        waiting(2, "conv-scout", "V", "@a", 20);
        ToolApprovalRequest deployed = waiting(3, "conv-writer", "V", "@a", 30);
        deployed.setConversationId("conv-child");
        deployed.setAgentTitle("Drafter");
        repo.save(deployed);
        waiting(4, "conv-gone", "V", "@a", 5);
        List<List<String>> asked = new ArrayList<>();
        ApprovalInbox.Sorted s = approvals.waitingInTrees(List.of("conv-writer", "conv-scout", "conv-gone"), WaitingSort.AGENT,
                roots -> {
                    asked.add(List.copyOf(roots));
                    return Map.of("conv-writer", "Reply Writer", "conv-scout", "Comment Scout");
                }, 0, 20);
        assertThat(asked).as("asked once, for the roots read").hasSize(1);
        assertThat(ids(s.page().getContent())).containsExactly(id(2), id(1), id(3), id(4));
    }

    @Test
    @DisplayName("more waiting than can be sorted here: by date, and the answer says so")
    void tooManyToSort() {
        waiting(1, ROOT, "B", "@b", 10);
        waiting(2, ROOT, "A", "@a", 20);
        assertThat(approvals.waitingInTrees(List.of(ROOT), WaitingSort.VIDEO, null, 0, 20).sort()).isEqualTo(WaitingSort.VIDEO);

        List<ToolApprovalRequest> more = new ArrayList<>();
        for (int n = 3; n <= ApprovalInbox.SORT_READ_MAX + 1; n++) {
            ToolApprovalRequest r = new ToolApprovalRequest();
            r.setId(id(n));
            r.setConversationId(ROOT);
            r.setRootConversationId(ROOT);
            r.setUserId("u-1");
            r.setToolName("replyToYouTubeComment");
            r.setArguments("{}");
            r.setRequestedAt(T.minusSeconds(1_000 + n));
            more.add(r);
        }
        repo.saveAll(more);
        ApprovalInbox.Sorted s = approvals.waitingInTrees(List.of(ROOT), WaitingSort.VIDEO, null, 0, 20);
        assertThat(s.sort()).isEqualTo(WaitingSort.DATE);
        assertThat(ids(s.page().getContent()).subList(0, 2)).containsExactly(id(1), id(2));
        assertThat(s.page().getTotalElements()).isEqualTo(ApprovalInbox.SORT_READ_MAX + 1);
    }
}
