package ai.proxyma.core.chat.agents;

import ai.proxyma.core.chat.domain.PermissionMode;
import ai.proxyma.core.chat.model.AgentDeliberation;
import ai.proxyma.core.chat.model.AgentEvent;
import ai.proxyma.core.chat.model.AgentFanOut;
import ai.proxyma.core.chat.model.AgentInboxEntry;
import ai.proxyma.core.chat.model.AgentNode;
import ai.proxyma.core.chat.repository.AgentAlarmRepository;
import ai.proxyma.core.chat.repository.AgentDeliberationRepository;
import ai.proxyma.core.chat.repository.AgentEventRepository;
import ai.proxyma.core.chat.repository.AgentFanOutRepository;
import ai.proxyma.core.chat.repository.AgentInboxRepository;
import ai.proxyma.core.chat.repository.AgentNodeRepository;
import ai.proxyma.core.chat.service.ChatEventSink;
import ai.proxyma.core.chat.service.ChatService;
import ai.proxyma.core.settings.dto.SubAgentSettings;
import ai.proxyma.core.settings.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the Constellations samples asked of the tree (2026-10-29): a budget or a deadline stops an
 * agent and everything it deployed, once, and its parent is told; a fan-out runs one agent per item
 * past every cap and reports once; a verifier's scorecard travels with the report it checked; a
 * deliberation's panel argues over rounds, the asker decides and the dissent is kept; a back-and-forth
 * between two agents is bounded; and all of it is recorded for the timeline. Map-backed repositories
 * and a mocked chat, so no turn runs: a test ends a turn by saying what it answered.
 */
class AgentTreeWorkTest {

    static final String ROOT = "conv-root";

    AgentNodeRepository nodes;
    AgentInboxRepository inboxRows;
    AgentFanOutRepository fanOutRows;
    AgentDeliberationRepository deliberationRows;
    AgentEventRepository eventRows;
    ChatService chat;
    AgentInbox inbox;
    AgentTree tree;
    AgentFanOuts fanOuts;
    AgentVerifier verifier;
    AgentDeliberations deliberations;
    final Map<String, Double> costs = new HashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        nodes = InMemoryRepositories.of(AgentNodeRepository.class);
        inboxRows = InMemoryRepositories.of(AgentInboxRepository.class, Map.of(
                "markDelivered", (store, args) -> {
                    int n = 0;
                    for (Object id : (Collection<?>) args[0]) {
                        AgentInboxEntry e = (AgentInboxEntry) store.get(id);
                        if (e != null && e.getDeliveredAt() == null) {
                            e.setDeliveredAt((Instant) args[1]);
                            n++;
                        }
                    }
                    return n;
                }));
        fanOutRows = InMemoryRepositories.of(AgentFanOutRepository.class);
        deliberationRows = InMemoryRepositories.of(AgentDeliberationRepository.class);
        eventRows = InMemoryRepositories.of(AgentEventRepository.class, Map.of(
                "findByRootConversationIdInAndAtGreaterThanEqualOrderByAtAsc", (store, args) -> store.values().stream()
                        .map(o -> (AgentEvent) o).filter(e -> ((Collection<?>) args[0]).contains(e.getRootConversationId()))
                        .toList()));
        chat = mock(ChatService.class);
        when(chat.permissionMode(anyString())).thenReturn(PermissionMode.ASK);
        ObjectProvider<ChatService> chats = mock(ObjectProvider.class);
        when(chats.getObject()).thenReturn(chat);
        SettingsService settings = mock(SettingsService.class);
        when(settings.getSettings(SubAgentSettings.PREFIX, SubAgentSettings.class)).thenAnswer(inv -> new SubAgentSettings());
        List<Object> published = new ArrayList<>();
        ApplicationEventPublisher events = published::add;
        inbox = new AgentInbox(inboxRows, events);
        tree = new AgentTree(nodes, inbox, InMemoryRepositories.of(AgentAlarmRepository.class), chats, settings,
                mock(ChatEventSink.class), events);
        tree.runInline();
        tree.spendTtlMs = 0;
        tree.costsFrom(ids -> ids.stream().mapToDouble(id -> costs.getOrDefault(id, 0.0)).sum());
        AgentEvents record = new AgentEvents(eventRows);
        tree.setRecord(record);
        fanOuts = new AgentFanOuts(fanOutRows, nodes, tree, inbox);
        fanOuts.setRecord(record);
        verifier = new AgentVerifier(nodes, tree, inbox);
        deliberations = new AgentDeliberations(deliberationRows, fanOuts, tree, inbox);
        fanOuts.setDeliberations(provider(deliberations));
        tree.setHelpers(provider(fanOuts), provider(verifier));
    }

    @SuppressWarnings("unchecked")
    static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(bean);
        when(p.getObject()).thenReturn(bean);
        return p;
    }

    String deploy(String parent, String title, AgentTree.Deploy in) {
        Map<String, Object> out = tree.deploy(parent, "u-1", in);
        assertThat(out).as(String.valueOf(out)).containsEntry("success", true);
        return (String) out.get("agentId");
    }

    String deploy(String parent, String title) {
        return deploy(parent, title, new AgentTree.Deploy(title, "Do " + title, null, null, null));
    }

    AgentNode node(String agentId) {
        return nodes.findById(UUID.fromString(agentId)).orElseThrow();
    }

    /** Ends a turn of this agent the tree started, with this answer. */
    void answer(AgentNode n, String text) {
        tree.turnEnded(n.getConversationId(), null, text, null);
    }

    List<AgentInbox.Taken> mail(String conversationId) {
        return inbox.takeWhere(conversationId, t -> true);
    }

    List<AgentNode> children(String parent) {
        return nodes.findByParentConversationIdOrderByCreatedAtAsc(parent);
    }

    @Nested
    @DisplayName("budgets and deadlines")
    class Limits {

        @Test
        @DisplayName("a spent budget stops the agent and what it deployed, once, and its parent hears why")
        void budget() {
            String a = deploy(ROOT, "Wide search", new AgentTree.Deploy("Wide search", "Search", null, null, null, 1.0, null, false));
            String b = deploy(node(a).getConversationId(), "Deep dig");
            assertThat(tree.checkLimits(node(b).getConversationId())).isEmpty();
            costs.put(node(b).getConversationId(), 0.7);
            costs.put(node(a).getConversationId(), 0.4);

            assertThat(tree.checkLimits(node(b).getConversationId())).hasValueSatisfying(r -> assertThat(r)
                    .contains("Wide search was stopped").contains("budget of $1.00"));
            assertThat(node(a).getStatus()).isEqualTo(AgentNode.STOPPED);
            assertThat(node(b).getStatus()).isEqualTo(AgentNode.STOPPED);
            assertThat(node(a).getStopNote()).contains("budget");
            // Asked again (the other agent's next step): the same reason, and no second report.
            assertThat(tree.checkLimits(node(a).getConversationId())).isPresent();
            List<AgentInbox.Taken> got = mail(ROOT);
            assertThat(got).hasSize(1);
            assertThat(got.get(0).kind()).isEqualTo(AgentInbox.Kind.REPORT);
            assertThat(got.get(0).text()).contains("Stopped by the tree").contains("It had not reported yet");
        }

        @Test
        @DisplayName("a passed deadline stops an agent even while nothing of it runs")
        void deadline() {
            String a = deploy(ROOT, "Slow job", new AgentTree.Deploy("Slow job", "Wait", "LONG", null, null, null, 5, false));
            assertThat(node(a).getDeadlineAt()).isAfter(Instant.now());
            AgentNode n = node(a);
            n.setDeadlineAt(Instant.now().minusSeconds(1));
            nodes.save(n);
            tree.sweepDeadlines();
            assertThat(node(a).getStatus()).isEqualTo(AgentNode.STOPPED);
            assertThat(node(a).getStopNote()).contains("deadline");
        }

        @Test
        @DisplayName("a budget or deadline out of range is refused at deployment")
        void ranges() {
            assertThat(tree.deploy(ROOT, "u-1", new AgentTree.Deploy("x", "x", null, null, null, 0.0, null, false)))
                    .containsEntry("success", false);
            assertThat(tree.deploy(ROOT, "u-1", new AgentTree.Deploy("x", "x", null, null, null, null, 0, false)))
                    .containsEntry("success", false);
        }
    }

    @Nested
    @DisplayName("fan-out")
    class FanOut {

        List<AgentFanOuts.Item> items(int n) {
            List<AgentFanOuts.Item> out = new ArrayList<>();
            for (int i = 1; i <= n; i++) out.add(new AgentFanOuts.Item("ECU " + i, "Check ECU " + i));
            return out;
        }

        @Test
        @DisplayName("items run a few at a time, each taking the place of one that ends, and the parent reads ONE report")
        void runsAndReportsOnce() {
            Map<String, Object> started = fanOuts.start(ROOT, "u-1", "Check each ECU", items(10), 3, null, null, null, false);
            assertThat(started).containsEntry("success", true).containsEntry("total", 10);
            assertThat(children(ROOT)).hasSize(3);
            for (int guard = 0; guard < 20; guard++) {
                List<AgentNode> busy = children(ROOT).stream().filter(n -> !n.isEnded()).toList();
                if (busy.isEmpty()) break;
                assertThat(busy.size()).isLessThanOrEqualTo(3);
                answer(busy.get(0), "Not affected: " + busy.get(0).getTitle());
            }
            assertThat(children(ROOT)).hasSize(10).allMatch(n -> AgentNode.DONE.equals(n.getStatus()));
            List<AgentInbox.Taken> got = mail(ROOT);
            assertThat(got).hasSize(1);
            assertThat(got.get(0).fromAgentId().toString()).isEqualTo(started.get("fanOutId"));
            assertThat(got.get(0).text()).contains("10 of 10 items reported").contains("Not affected: ECU 1")
                    .contains("Not affected: ECU 10");
            assertThat(fanOutRows.findAll()).allMatch(f -> AgentFanOut.DONE.equals(f.getStatus()));
        }

        @Test
        @DisplayName("past the parent's eight working agents an item waits for a place instead of being refused")
        void queuedPastTheCap() {
            List<String> others = new ArrayList<>();
            for (int i = 0; i < 7; i++) others.add(deploy(ROOT, "Other " + i));
            fanOuts.start(ROOT, "u-1", "Many", items(3), 4, null, null, null, false);
            assertThat(children(ROOT).stream().filter(n -> n.getFanOutId() != null)).hasSize(1);
            answer(node(others.get(0)), "done");
            assertThat(children(ROOT).stream().filter(n -> n.getFanOutId() != null)).hasSize(2);
        }

        @Test
        @DisplayName("stopping a fan-out stops its running items and reports what was done")
        void stop() {
            Map<String, Object> started = fanOuts.start(ROOT, "u-1", "Many", items(5), 2, null, null, null, false);
            answer(children(ROOT).get(0), "first done");
            assertThat(tree.control(ROOT, (String) started.get("fanOutId"), "stop")).containsEntry("success", true);
            assertThat(children(ROOT).stream().filter(n -> !n.isEnded())).isEmpty();
            List<AgentInbox.Taken> got = mail(ROOT);
            assertThat(got).hasSize(1);
            assertThat(got.get(0).text()).contains("was stopped").contains("first done").contains("never started");
        }

        @Test
        @DisplayName("awaitAgents takes a fanOutId and returns its one report")
        void awaitFanOut() {
            Map<String, Object> started = fanOuts.start(ROOT, "u-1", "Two", items(2), 2, null, null, null, false);
            children(ROOT).forEach(n -> answer(n, "ok " + n.getTitle()));
            Map<String, Object> out = tree.await(ROOT, List.of((String) started.get("fanOutId")), 5);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> reports = (List<Map<String, Object>>) out.get("reports");
            assertThat(reports).hasSize(1);
            assertThat((String) reports.get(0).get("report")).contains("2 of 2 items reported");
        }

        @Test
        @DisplayName("more than two hundred items, or none, is refused")
        void bounds() {
            assertThat(fanOuts.start(ROOT, "u-1", "x", items(201), 2, null, null, null, false)).containsEntry("success", false);
            assertThat(fanOuts.start(ROOT, "u-1", "x", List.of(), 2, null, null, null, false)).containsEntry("success", false);
        }
    }

    @Nested
    @DisplayName("the verifier")
    class Verifying {

        static final String SCORE = "Checked.\n```json\n{\"claims\": ["
                + "{\"claim\": \"Revenue rose 12%\", \"status\": \"SUPPORTED\", \"source\": \"q3.pdf\"},"
                + "{\"claim\": \"Churn fell\", \"status\": \"UNSUPPORTED\", \"source\": \"q3.pdf\", \"note\": \"it rose\"},"
                + "{\"claim\": \"The CEO left\", \"status\": \"UNCITED\"}]}\n```";

        @Test
        @DisplayName("an agent deployed with verify holds its report until the scorecard is in, then both reach its parent")
        void checksBeforeDelivering() {
            String a = deploy(ROOT, "Analyst", new AgentTree.Deploy("Analyst", "Analyse", null, null, null, null, null, true));
            answer(node(a), "Revenue rose 12% [q3.pdf]. Churn fell [q3.pdf]. The CEO left.");
            assertThat(node(a).getStatus()).isEqualTo(AgentNode.WAITING);
            assertThat(mail(ROOT)).isEmpty();
            AgentNode check = children(ROOT).stream().filter(AgentNode::isVerifier).findFirst().orElseThrow();
            assertThat(check.getVerifiesAgentId().toString()).isEqualTo(a);
            assertThat(check.getTask()).contains("Revenue rose 12%");

            answer(check, SCORE);

            assertThat(node(a).getStatus()).isEqualTo(AgentNode.DONE);
            assertThat(node(a).getVerification()).contains("\"supported\":1");
            List<AgentInbox.Taken> got = mail(ROOT);
            assertThat(got).hasSize(1);
            assertThat(got.get(0).fromAgentId().toString()).isEqualTo(a);
            assertThat(got.get(0).text()).contains("Revenue rose 12%")
                    .contains("[Verification: 1 supported, 1 unsupported, 1 uncited of 3 claims]")
                    .contains("UNSUPPORTED: Churn fell (it rose)");
        }

        @Test
        @DisplayName("verifyReport checks a report already in, and the score reaches the caller")
        void onDemand() {
            String a = deploy(ROOT, "Analyst");
            answer(node(a), "Revenue rose 12% [q3.pdf].");
            mail(ROOT);
            Map<String, Object> out = verifier.verifyReport(ROOT, a);
            assertThat(out).containsEntry("success", true);
            answer(node((String) out.get("agentId")), SCORE);
            List<AgentInbox.Taken> got = mail(ROOT);
            assertThat(got).hasSize(1);
            assertThat(got.get(0).text()).startsWith("[Verification: 1 supported");
            assertThat(node(a).getVerification()).isNotNull();
        }

        @Test
        @DisplayName("a scorecard is read from a fenced block or bare JSON, and an unreadable one says so")
        void parsing() {
            assertThat(AgentVerifier.parse(SCORE).supported()).isEqualTo(1);
            assertThat(AgentVerifier.parse("{\"claims\":[{\"claim\":\"x\",\"status\":\"maybe\"}]}").unsupported()).isEqualTo(1);
            assertThat(AgentVerifier.parse("I could not check anything.").note()).contains("no scorecard");
        }
    }

    @Nested
    @DisplayName("deliberation")
    class Deliberating {

        @Test
        @DisplayName("panelists argue over rounds, the asker decides, and who voted otherwise is kept as dissent")
        void roundsVotesDissent() {
            Map<String, Object> started = deliberations.start(ROOT, "u-1", "Ship the hotfix tonight?",
                    List.of("Go", "No-go"), List.of("Skeptic: find what could go wrong", "Release manager: weigh the schedule",
                            "Safety engineer: weigh the hazard"), 2, null);
            assertThat(started).containsEntry("success", true);
            String id = (String) started.get("deliberationId");
            List<AgentNode> round1 = children(ROOT);
            assertThat(round1).hasSize(3);
            assertThat(round1.get(0).getTask()).contains("Skeptic").contains("Go | No-go").contains("round 1");
            answer(round1.get(0), "Untested path.\nVOTE: No-go");
            answer(round1.get(1), "The window is tonight.\nVOTE: Go");
            answer(round1.get(2), "Hazard is low.\nVOTE: Go");

            List<AgentNode> round2 = children(ROOT).stream().filter(n -> !n.isEnded()).toList();
            assertThat(round2).hasSize(3);
            assertThat(round2.get(0).getTask()).contains("round 2 of 2").contains("Untested path").contains("voted Go");
            answer(round2.get(0), "Still untested.\nVOTE: No-go");
            answer(round2.get(1), "Agreed, risky.\n**VOTE:** No-go");
            answer(round2.get(2), "Low hazard.\nVOTE: Go");

            List<AgentInbox.Taken> got = mail(ROOT);
            assertThat(got).hasSize(1);
            assertThat(got.get(0).text()).contains("No-go: 2 (Skeptic, Release manager)").contains("Go: 1 (Safety engineer)")
                    .contains("recordDecision");
            assertThat(deliberations.decide(ROOT, id, "No-go", "")).containsEntry("success", false);
            Map<String, Object> decided = deliberations.decide(ROOT, id, "No-go", "The path is untested.");
            assertThat(decided).containsEntry("success", true);
            assertThat((List<?>) decided.get("dissent")).hasSize(1).first().asString().contains("Safety engineer");
            assertThat(deliberationRows.findAll()).allMatch(d -> AgentDeliberation.DECIDED.equals(d.getStatus()));
            assertThat(deliberations.decide(ROOT, id, "Go", "changed my mind")).containsEntry("success", false);
        }

        @Test
        @DisplayName("a panel of one, a repeated name or a single option is refused")
        void bounds() {
            assertThat(deliberations.start(ROOT, "u-1", "q", null, List.of("Only: me"), 1, null)).containsEntry("success", false);
            assertThat(deliberations.start(ROOT, "u-1", "q", null, List.of("A: x", "a: y"), 1, null)).containsEntry("success", false);
            assertThat(deliberations.start(ROOT, "u-1", "q", List.of("Go"), List.of("A: x", "B: y"), 1, null))
                    .containsEntry("success", false);
        }

        @Test
        @DisplayName("a vote is read off its line, and agrees with a decision that names it")
        void votes() {
            assertThat(AgentDeliberations.vote("text\nVOTE: Go\nmore\nVOTE: No-go")).isEqualTo("No-go");
            assertThat(AgentDeliberations.vote("no vote here")).isNull();
            assertThat(AgentDeliberations.agrees("No-go", "No-go, pending tests")).isTrue();
            assertThat(AgentDeliberations.agrees("Go", "No-go")).isFalse();
        }
    }

    @Test
    @DisplayName("two agents can trade at most twenty messages in ten minutes - a back-and-forth is bounded")
    void messagesAreRateLimited() {
        String a = deploy(ROOT, "Worker");
        for (int i = 0; i < AgentTree.PAIR_RATE; i++) {
            assertThat(tree.tell(ROOT, a, "more " + i)).containsEntry("success", true);
        }
        assertThat(tree.tell(ROOT, a, "one more")).containsEntry("success", false);
        assertThat(tree.tell(node(a).getConversationId(), "parent", "back")).containsEntry("success", true);
    }

    @Test
    @DisplayName("what happens is recorded for the timeline: a deployment, its statuses, its report")
    void recordsTheTimeline() {
        String a = deploy(ROOT, "Worker");
        answer(node(a), "Found it.");
        List<String> kinds = eventRows.findAll().stream().map(AgentEvent::getKind).toList();
        assertThat(kinds).contains(AgentEvent.DEPLOYED, AgentEvent.STATUS, AgentEvent.REPORT);
        assertThat(new AgentEvents(eventRows).of(List.of(ROOT), null)).isNotEmpty();
        assertThat(eventRows.findAll()).allMatch(e -> ROOT.equals(e.getRootConversationId()));
        assertThat(Optional.ofNullable(eventRows.findAll().stream().filter(e -> AgentEvent.REPORT.equals(e.getKind()))
                .findFirst().orElseThrow().getDetail())).contains("Found it.");
    }
}
