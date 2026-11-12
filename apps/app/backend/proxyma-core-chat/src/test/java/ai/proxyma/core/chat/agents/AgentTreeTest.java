package ai.proxyma.core.chat.agents;

import ai.proxyma.core.chat.domain.PermissionMode;
import ai.proxyma.core.chat.model.AgentAlarm;
import ai.proxyma.core.chat.model.AgentInboxEntry;
import ai.proxyma.core.chat.model.AgentNode;
import ai.proxyma.core.chat.module.AgentProfile;
import ai.proxyma.core.chat.module.ChatModule;
import ai.proxyma.core.chat.repository.AgentAlarmRepository;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * THE AGENT TREE: every agent is a conversation of its own and may deploy children down to the
 * configured depth; a child reports by ending a turn that worked for its parent - not while work of
 * its own still runs - and a person's message into a child is refused, since a person reads a
 * child and talks to its root (2026-11-04); a parent controls its children and theirs, and nobody else's; and a child can never do more than the
 * root it works under. Driven over map-backed repositories and a mocked chat, so no turn runs.
 */
class AgentTreeTest {

    static final String ROOT = "conv-root";

    AgentNodeRepository nodes;
    AgentInboxRepository inboxRows;
    AgentAlarmRepository alarmRows;
    ChatService chat;
    SettingsService settings;
    SubAgentSettings child;
    AgentInbox inbox;
    AgentTree tree;
    List<Object> published;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        nodes = InMemoryRepositories.of(AgentNodeRepository.class);
        inboxRows = InMemoryRepositories.of(AgentInboxRepository.class, Map.of(
                "conversationsWithPending", (store, args) -> store.values().stream()
                        .map(o -> (AgentInboxEntry) o).filter(e -> e.getDeliveredAt() == null)
                        .map(AgentInboxEntry::getConversationId).distinct().toList(),
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
        alarmRows = InMemoryRepositories.of(AgentAlarmRepository.class);
        chat = mock(ChatService.class);
        when(chat.permissionMode(anyString())).thenReturn(PermissionMode.ASK);
        ObjectProvider<ChatService> chats = mock(ObjectProvider.class);
        when(chats.getObject()).thenReturn(chat);
        settings = mock(SettingsService.class);
        child = new SubAgentSettings();
        when(settings.getSettings(SubAgentSettings.PREFIX, SubAgentSettings.class)).thenAnswer(inv -> child);
        published = new ArrayList<>();
        ApplicationEventPublisher events = published::add;
        inbox = new AgentInbox(inboxRows, events);
        tree = new AgentTree(nodes, inbox, alarmRows, chats, settings, mock(ChatEventSink.class), events);
        tree.runInline();
    }

    String deploy(String parent, String title, String lifetime) {
        Map<String, Object> out = tree.deploy(parent, "u-1", new AgentTree.Deploy(title, "Do " + title, lifetime, null, null));
        assertThat(out).as(String.valueOf(out)).containsEntry("success", true);
        return (String) out.get("agentId");
    }

    AgentNode node(String agentId) {
        return nodes.findById(UUID.fromString(agentId)).orElseThrow();
    }

    List<AgentInbox.Taken> mail(String conversationId) {
        return inbox.takeWhere(conversationId, t -> true);
    }

    @Nested
    @DisplayName("deploying")
    class Deploying {

        @Test
        @DisplayName("a child is a conversation of its own, started on its task in the parent's permission mode, never on the parent's thread")
        void startsOnItsOwn() {
            when(chat.permissionMode(ROOT)).thenReturn(PermissionMode.AUTO);
            String id = deploy(ROOT, "Read the diff", null);
            AgentNode n = node(id);
            assertThat(n.getDepth()).isEqualTo(1);
            assertThat(n.getRootConversationId()).isEqualTo(ROOT);
            assertThat(n.getConversationId()).isNotEqualTo(ROOT);
            assertThat(n.getLifetime()).isEqualTo(AgentNode.SHORT);
            verify(chat).setPermissionMode(n.getConversationId(), PermissionMode.AUTO);
            verify(chat).runUnattended(eq(n.getConversationId()), eq("Do Read the diff"), eq("u-1"),
                    eq(AgentTree.SURFACE), any(), anyList());
        }

        @Test
        @DisplayName("children deploy their own, down to the configured depth - 2 by default, at most 5")
        void depth() {
            String a = deploy(ROOT, "Level one", null);
            String b = deploy(node(a).getConversationId(), "Level two", null);
            Map<String, Object> third = tree.deploy(node(b).getConversationId(), "u-1",
                    new AgentTree.Deploy("Level three", "x", null, null, null));
            assertThat(third).containsEntry("success", false);
            assertThat((String) third.get("error")).contains("deepest level");

            child.setMaxDepth(3);
            assertThat(deploy(node(b).getConversationId(), "Level three", null)).isNotNull();
            child.setMaxDepth(99);
            assertThat(tree.maxDepth()).isEqualTo(SubAgentSettings.MAX_DEPTH_CEILING);
            child.setMaxDepth(0);
            assertThat(tree.maxDepth()).isEqualTo(1);
        }

        @Test
        @DisplayName("at most eight work at once for one parent")
        void runningCap() {
            for (int i = 0; i < AgentTree.RUNNING_PER_PARENT; i++) deploy(ROOT, "Agent " + i, null);
            assertThat(tree.deploy(ROOT, "u-1", new AgentTree.Deploy("One more", "x", null, null, null)))
                    .containsEntry("success", false);
        }
    }

    @Nested
    @DisplayName("reporting")
    class Reporting {

        @Test
        @DisplayName("a turn the tree started reports its answer to the parent, and a SHORT agent is then done")
        void reportsAndEnds() {
            String id = deploy(ROOT, "Summarize", null);
            String conv = node(id).getConversationId();
            tree.turnEnded(conv, "Three findings.", null);
            List<AgentInbox.Taken> got = mail(ROOT);
            assertThat(got).singleElement().satisfies(t -> {
                assertThat(t.kind()).isEqualTo(AgentInbox.Kind.REPORT);
                assertThat(t.fromAgentId()).hasToString(id);
                assertThat(t.text()).isEqualTo("Three findings.");
            });
            assertThat(node(id).getStatus()).isEqualTo(AgentNode.DONE);
            assertThat(node(id).isReportOwed()).isFalse();
        }

        @Test
        @DisplayName("the report reaches the parent's inbox before the parent's page hears the agent is done (0.40.1 feedback)")
        void reportBeforeDoneStatus() {
            String id = deploy(ROOT, "List tasks", null);
            published.clear();
            tree.turnEnded(node(id).getConversationId(), "Eleven open.", null);
            // The inbox's event is what wakes the parent (ChatService.onInboxPosted), synchronously: the
            // turn it starts is announced to the page ('turn-start') at that point. The page closes its
            // stream on the status saying the agent is done and owes nothing, so that status must come
            // AFTER the wake, or the turn the report starts reaches nobody until a reload.
            int posted = published.indexOf(new AgentInboxPostedEvent(ROOT));
            int done = -1;
            for (int i = 0; i < published.size(); i++) {
                if (published.get(i) instanceof ai.proxyma.core.chat.event.ChatEvent e
                        && ROOT.equals(e.conversationId())
                        && ai.proxyma.core.chat.event.ChatEvent.Type.AGENT_STATUS.value().equals(e.streamType())
                        && e.data().contains("\"status\":\"" + AgentNode.DONE + "\"")) {
                    done = i;
                }
            }
            assertThat(posted).as("the report was posted").isNotNegative();
            assertThat(done).as("the parent's page was told the agent is done").isNotNegative();
            assertThat(posted).as("delivered before the final status").isLessThan(done);
            assertThat(published.get(done)).asString().contains("\"owesReport\":false");
        }

        @Test
        @DisplayName("a verified agent's checked report also reaches the parent before its final status")
        void checkedReportBeforeDoneStatus() {
            String id = deploy(ROOT, "Checked", null);
            AgentNode n = node(id);
            n.setLastReport("Draft.");
            nodes.save(n);
            published.clear();
            tree.deliverChecked(UUID.fromString(id), "Draft.\n\nScore: 9/10");
            int posted = published.indexOf(new AgentInboxPostedEvent(ROOT));
            int status = -1;
            for (int i = 0; i < published.size(); i++) {
                if (published.get(i) instanceof ai.proxyma.core.chat.event.ChatEvent e
                        && ai.proxyma.core.chat.event.ChatEvent.Type.AGENT_STATUS.value().equals(e.streamType())) {
                    status = i;
                }
            }
            assertThat(posted).isNotNegative();
            assertThat(posted).isLessThan(status);
        }

        @Test
        @DisplayName("a LONG agent stays, idle, for more work")
        void longStays() {
            String id = deploy(ROOT, "Watcher", "long");
            tree.turnEnded(node(id).getConversationId(), "Watching.", null);
            assertThat(node(id).getStatus()).isEqualTo(AgentNode.IDLE);
        }

        @Test
        @DisplayName("a child whose own work still runs does not report yet; the turn that ends after it does")
        void waitsForItsOwnWork() {
            String id = deploy(ROOT, "Lead", null);
            String conv = node(id).getConversationId();
            when(chat.hasBackgroundWork(conv)).thenReturn(true);
            tree.turnEnded(conv, "I've started two helpers.", null);
            assertThat(mail(ROOT)).as("an interim answer is no report").isEmpty();
            assertThat(node(id).getStatus()).isEqualTo(AgentNode.WAITING);

            when(chat.hasBackgroundWork(conv)).thenReturn(false);
            tree.turnEnded(conv, "Both helpers agree: ship it.", null);
            assertThat(mail(ROOT)).singleElement().extracting(AgentInbox.Taken::text).isEqualTo("Both helpers agree: ship it.");
        }

        @Test
        @DisplayName("a failed turn still reports, so the parent is never left waiting")
        void failureReports() {
            String id = deploy(ROOT, "Fetch", null);
            tree.turnEnded(node(id).getConversationId(), null, "429 Too Many Requests");
            assertThat(mail(ROOT)).singleElement().extracting(AgentInbox.Taken::text).asString().contains("429");
            assertThat(node(id).getStatus()).isEqualTo(AgentNode.FAILED);
            assertThat(node(id).getStopReason()).isEqualTo(AgentNode.REASON_ERROR);
        }

        @Test
        @DisplayName("a turn that gave up on a rate limit fails with that reason as a code, for the agents map to word")
        void rateLimitedFailureIsCoded() {
            String id = deploy(ROOT, "Fetch", null);
            tree.turnEnded(node(id).getConversationId(), null,
                    AgentTree.RATE_LIMITED_PREFIX + " - the provider kept refusing for its rate limit. (429)");
            assertThat(node(id).getStatus()).isEqualTo(AgentNode.FAILED);
            assertThat(node(id).getStopReason()).isEqualTo(AgentNode.REASON_RATE_LIMITED);
        }

        @Test
        @DisplayName("an agent that reports carries no stop reason, and one given work again loses the one it had")
        void reportCarriesNoReason() {
            String id = deploy(ROOT, "Summarize", null);
            tree.turnEnded(node(id).getConversationId(), "Done.", null);
            assertThat(node(id).getStatus()).isEqualTo(AgentNode.DONE);
            assertThat(node(id).getStopReason()).isNull();

            String l = deploy(ROOT, "Watcher", "LONG");
            AgentNode n = node(l);
            n.setStopReason(AgentNode.REASON_ERROR);
            nodes.save(n);
            tree.tell(ROOT, l, "Look again.");
            assertThat(node(l).getStopReason()).isNull();
        }

        @Test
        @DisplayName("a child is read-only to a person: it is told apart from the root, and its turn sends its parent the report and nothing else (2026-11-04)")
        void childIsReadOnlyToAPerson() {
            String id = deploy(ROOT, "Researcher", null);
            String conv = node(id).getConversationId();
            // What ChatService.isDeployedAgent asks before it refuses a person's message
            // (DeployedAgentReadOnlyTest pins the refusal itself, a new turn and a typed message alike).
            assertThat(tree.isChild(conv)).isTrue();
            assertThat(tree.isChild(ROOT)).as("the conversation the person opened").isFalse();
            tree.turnEnded(conv, "First pass.", null);
            List<AgentInbox.Taken> got = mail(ROOT);
            assertThat(got).extracting(AgentInbox.Taken::kind).containsExactly(AgentInbox.Kind.REPORT);
            assertThat(got.get(0).text()).isEqualTo("First pass.");
        }
    }

    @Nested
    @DisplayName("talking and control")
    class Control {

        @Test
        @DisplayName("a parent messages its children and theirs; siblings only with peer messaging; nobody else")
        void whoMayTalk() {
            String a = deploy(ROOT, "A", null);
            String b = deploy(ROOT, "B", null);
            String a1 = deploy(node(a).getConversationId(), "A1", null);
            assertThat(tree.tell(ROOT, a1, "hi")).as("a grandchild").containsEntry("success", true);
            assertThat(tree.tell(node(a).getConversationId(), "parent", "question")).containsEntry("success", true);
            assertThat(tree.tell(node(a).getConversationId(), b, "hi")).as("a sibling, peers off").containsEntry("success", false);
            assertThat(tree.tell(node(b).getConversationId(), a1, "hi")).as("a cousin").containsEntry("success", false);
            assertThat(tree.tell(ROOT, "parent", "hi")).as("the root has no parent").containsEntry("success", false);
            child.setPeerMessaging(true);
            assertThat(tree.tell(node(a).getConversationId(), b, "hi")).containsEntry("success", true);
        }

        @Test
        @DisplayName("a message gives an agent work again: it owes a report and is woken")
        void messageIsWork() {
            String id = deploy(ROOT, "Long one", "LONG");
            String conv = node(id).getConversationId();
            tree.turnEnded(conv, "Ready.", null);
            published.clear();
            tree.tell(ROOT, id, "Now look at the logs.");
            assertThat(node(id).isReportOwed()).isTrue();
            assertThat(node(id).getStatus()).isEqualTo(AgentNode.RUNNING);
            assertThat(published).contains(new AgentInboxPostedEvent(conv));
            assertThat(inbox.take(conv)).startsWith("[Message from").contains("Now look at the logs.");
        }

        @Test
        @DisplayName("stop ends the agent and everything it deployed; a stranger cannot")
        void stopCascades() {
            String a = deploy(ROOT, "A", null);
            String a1 = deploy(node(a).getConversationId(), "A1", null);
            String b = deploy(ROOT, "B", null);
            assertThat(tree.control(node(b).getConversationId(), a, "stop")).containsEntry("success", false);
            assertThat(tree.control(ROOT, a, "stop")).containsEntry("success", true);
            assertThat(node(a).getStatus()).isEqualTo(AgentNode.STOPPED);
            assertThat(node(a1).getStatus()).isEqualTo(AgentNode.STOPPED);
            // Stopped by an agent above it - the code the map words as "by its parent".
            assertThat(node(a).getStopReason()).isEqualTo(AgentNode.REASON_PARENT);
            assertThat(node(a1).getStopReason()).isEqualTo(AgentNode.REASON_PARENT);
            verify(chat).stop(node(a1).getConversationId());
            assertThat(node(b).getStatus()).isEqualTo(AgentNode.RUNNING);
        }

        @Test
        @DisplayName("a paused agent starts no turn and its mail waits; resumed, it carries on")
        void pause() {
            String a = deploy(ROOT, "A", null);
            String conv = node(a).getConversationId();
            tree.control(ROOT, a, "pause");
            assertThat(tree.mayWake(conv)).isFalse();
            tree.tell(ROOT, a, "Wait for me.");
            assertThat(node(a).getStatus()).isEqualTo(AgentNode.PAUSED);
            tree.control(ROOT, a, "resume");
            assertThat(tree.mayWake(conv)).isTrue();
            assertThat(node(a).getStatus()).isEqualTo(AgentNode.RUNNING);
        }

        @Test
        @DisplayName("the person controls any agent of their own tree from any of its conversations, and none of another")
        void personControls() {
            String a = deploy(ROOT, "A", null);
            String a1 = deploy(node(a).getConversationId(), "A1", null);
            assertThat(tree.controlInTree(node(a).getConversationId(), a1, "pause")).containsEntry("success", true);
            assertThat(tree.controlInTree("conv-somebody-else", a1, "stop")).containsEntry("success", false);
            // What the person stops is coded as theirs, the agents under it included.
            String b = deploy(ROOT, "B", null);
            String b1 = deploy(node(b).getConversationId(), "B1", null);
            assertThat(tree.controlInTree(ROOT, b, "stop")).containsEntry("success", true);
            assertThat(node(b).getStopReason()).isEqualTo(AgentNode.REASON_USER);
            assertThat(node(b1).getStopReason()).isEqualTo(AgentNode.REASON_USER);
            // Dismissed is done, not stopped: no reason; what it deployed is stopped with it.
            String c = deploy(ROOT, "C", "LONG");
            String c1 = deploy(node(c).getConversationId(), "C1", null);
            tree.controlInTree(ROOT, c, "dismiss");
            assertThat(node(c).getStatus()).isEqualTo(AgentNode.DONE);
            assertThat(node(c).getStopReason()).isNull();
            assertThat(node(c1).getStopReason()).isEqualTo(AgentNode.REASON_USER);
        }

        @Test
        @DisplayName("awaitAgents takes the report it waited for, so the agent does not read it twice")
        void awaitTakes() {
            String a = deploy(ROOT, "A", null);
            tree.turnEnded(node(a).getConversationId(), "Done: 42.", null);
            Map<String, Object> out = tree.await(ROOT, List.of(a), 5);
            assertThat(out.get("reports").toString()).contains("Done: 42.");
            assertThat(inbox.hasPending(ROOT)).isFalse();
        }

        @Test
        @DisplayName("a wait in awaitAgents says how many agents it is still for, and nothing once it ends")
        void waitingOnCounts() throws Exception {
            String a = deploy(ROOT, "A", null);
            String b = deploy(ROOT, "B", null);
            assertThat(tree.waitingOn(ROOT)).isZero();
            Thread waiter = new Thread(() -> tree.await(ROOT, List.of(a, b), 30));
            waiter.start();
            long until = System.currentTimeMillis() + 5_000;
            while (tree.waitingOn(ROOT) != 2 && System.currentTimeMillis() < until) Thread.sleep(10);
            assertThat(tree.waitingOn(ROOT)).isEqualTo(2);

            tree.turnEnded(node(a).getConversationId(), "Done: 1.", null);
            until = System.currentTimeMillis() + 5_000;
            while (tree.waitingOn(ROOT) != 1 && System.currentTimeMillis() < until) Thread.sleep(10);
            assertThat(tree.waitingOn(ROOT)).isEqualTo(1);

            tree.turnEnded(node(b).getConversationId(), "Done: 2.", null);
            waiter.join(5_000);
            assertThat(waiter.isAlive()).isFalse();
            assertThat(tree.waitingOn(ROOT)).isZero();
        }

        @Test
        @DisplayName("a wait its caller gave up ends at once and takes nothing, so a later report reaches the agent as mail")
        void abandonedWaitTakesNothing() throws Exception {
            // Feedback 8a3e3c01: awaitAgents was given up by the chat's tool timeout while it still waited,
            // took the report when it came, and handed it to nobody - the agent read as reported.
            String a = deploy(ROOT, "A", null);
            java.util.concurrent.atomic.AtomicReference<Map<String, Object>> out = new java.util.concurrent.atomic.AtomicReference<>();
            Thread waiter = new Thread(() -> out.set(tree.await(ROOT, List.of(a), 30)));
            long started = System.currentTimeMillis();
            waiter.start();
            Thread.sleep(200);
            tree.abandonWaits(ROOT);
            waiter.join(5_000);
            assertThat(waiter.isAlive()).isFalse();
            assertThat(System.currentTimeMillis() - started).isLessThan(5_000);
            assertThat(out.get()).containsEntry("success", false);

            tree.turnEnded(node(a).getConversationId(), "Done: 42.", null);
            assertThat(inbox.hasPending(ROOT)).isTrue();
            assertThat(inbox.take(ROOT)).contains("Done: 42.");
            // And the next wait, started after the give-up, waits as before.
            String b = deploy(ROOT, "B", null);
            tree.turnEnded(node(b).getConversationId(), "Done: 7.", null);
            assertThat(tree.await(ROOT, List.of(b), 5).get("reports").toString()).contains("Done: 7.");
        }

        @Test
        @DisplayName("a named model is resolved to its provider, and one no provider lists is refused before anything starts")
        @SuppressWarnings("unchecked")
        void namedModelChecked() {
            AgentModels models = mock(AgentModels.class);
            when(models.choose(any(), eq("claude-haiku-5-5"))).thenReturn(new AgentModels.Choice("gw", "claude-haiku-5-5", null));
            when(models.choose(any(), eq("claude-opus-9"))).thenReturn(AgentModels.Choice.refused("No provider lists a model named claude-opus-9."));
            org.springframework.beans.factory.ObjectProvider<AgentModels> p = mock(org.springframework.beans.factory.ObjectProvider.class);
            when(p.getIfAvailable()).thenReturn(models);
            tree.setModels(p);

            Map<String, Object> refused = tree.deploy(ROOT, "u-1", new AgentTree.Deploy("X", "Do X", null, null, "claude-opus-9"));
            assertThat(refused).containsEntry("success", false);
            assertThat(refused.get("error").toString()).contains("claude-opus-9");
            assertThat(tree.modelRefusal("claude-opus-9")).contains("No provider lists");
            assertThat(tree.modelRefusal(null)).isNull();

            String a = (String) tree.deploy(ROOT, "u-1", new AgentTree.Deploy("Y", "Do Y", null, null, "claude-haiku-5-5")).get("agentId");
            assertThat(node(a).getProviderId()).isEqualTo("gw");
            assertThat(node(a).getModel()).isEqualTo("claude-haiku-5-5");
        }

        @Test
        @DisplayName("an agent deployed with no model runs on its parent's: a deployed parent's own, else what the root runs on")
        @SuppressWarnings("unchecked")
        void noModelNamedIsTheParents() {
            AgentModels models = mock(AgentModels.class);
            when(models.choose(any(), eq("claude-haiku-5-5"))).thenReturn(new AgentModels.Choice("gw", "claude-haiku-5-5", null));
            org.springframework.beans.factory.ObjectProvider<AgentModels> p = mock(org.springframework.beans.factory.ObjectProvider.class);
            when(p.getIfAvailable()).thenReturn(models);
            tree.setModels(p);
            child.setMaxDepth(3);

            // Under the conversation the user opened: no model of its own, so the root's or the chat's.
            String plain = deploy(ROOT, "Plain", null);
            assertThat(node(plain).getModel()).isNull();
            assertThat(node(plain).getProviderId()).isNull();

            // Under an agent deployed on a cheaper model: that model, two levels down.
            String cheap = (String) tree.deploy(ROOT, "u-1", new AgentTree.Deploy("Cheap", "Do it", null, null, "claude-haiku-5-5")).get("agentId");
            String kid = deploy(node(cheap).getConversationId(), "Kid", null);
            String grandkid = deploy(node(kid).getConversationId(), "Grandkid", null);
            for (String id : List.of(kid, grandkid)) {
                assertThat(node(id).getProviderId()).isEqualTo("gw");
                assertThat(node(id).getModel()).isEqualTo("claude-haiku-5-5");
            }
            // A model named for the child wins over the parent's.
            when(models.choose(any(), eq("claude-sonnet-5-5"))).thenReturn(new AgentModels.Choice("gw", "claude-sonnet-5-5", null));
            String named = (String) tree.deploy(node(cheap).getConversationId(), "u-1",
                    new AgentTree.Deploy("Named", "Do it", null, null, "claude-sonnet-5-5")).get("agentId");
            assertThat(node(named).getModel()).isEqualTo("claude-sonnet-5-5");
            // Under a deployed parent with no model of its own: still the root's or the chat's.
            String plainKid = deploy(node(plain).getConversationId(), "Plain kid", null);
            assertThat(node(plainKid).getModel()).isNull();
        }

        @Test
        @DisplayName("the chat gives awaitAgents a timeout longer than the longest wait it may ask for")
        void awaitOutlivesTheToolTimeout() {
            assertThat(AgentTree.AWAIT_TOOL).isEqualTo("awaitAgents");
            assertThat(ai.proxyma.core.chat.service.ChatService.awaitToolTimeoutSeconds())
                    .isGreaterThan((int) AgentTree.AWAIT_MAX.toSeconds());
        }
    }

    @Nested
    @DisplayName("what a child may do")
    class Narrowing {

        ChatModule rootModule(AgentProfile profile) {
            ChatModule m = mock(ChatModule.class);
            when(m.profileFor(eq(ROOT), any())).thenReturn(Optional.ofNullable(profile));
            when(m.profileFor(org.mockito.ArgumentMatchers.argThat(c -> !ROOT.equals(c)), any())).thenReturn(Optional.empty());
            return m;
        }

        @SuppressWarnings("unchecked")
        AgentTreeModule module(ChatModule... others) {
            ObjectProvider<ChatModule> provider = mock(ObjectProvider.class);
            AgentTreeModule[] self = new AgentTreeModule[1];
            when(provider.orderedStream()).thenAnswer(inv -> Stream.concat(Stream.of(self[0]), Stream.of(others)));
            self[0] = new AgentTreeModule(tree, provider, settings);
            return self[0];
        }

        @Test
        @DisplayName("a child works under its root's narrowing: the same withheld tools, sources and memory")
        void inheritsRoot() {
            UUID source = UUID.randomUUID();
            AgentTreeModule m = module(rootModule(new AgentProfile(null, null, null, Set.of("webFetch"),
                    Set.of(source), "agent:7", Set.of("Jira"))));
            String a = deploy(ROOT, "A", null);
            AgentProfile p = m.profileFor(node(a).getConversationId(), "u-1").orElseThrow();
            assertThat(p.allows("webFetch")).isFalse();
            assertThat(p.reads(source)).isTrue();
            assertThat(p.reads(UUID.randomUUID())).isFalse();
            assertThat(p.memoryKey()).isEqualTo("agent:7");
            assertThat(p.allowsSkill("Jira")).isFalse();
            assertThat(m.profileFor(ROOT, "u-1")).as("the root is the other modules' to describe").isEmpty();
        }

        @Test
        @DisplayName("a child of an agent given only some tool sets is given only those - a server connected since included")
        void inheritsRootAllowList() {
            AgentTreeModule m = module(rootModule(new AgentProfile(null, null, null, Set.of("searchIndexedSources"), null,
                    "agent:7", Set.of("Memory & Knowledge"), false, Set.of("YouTube"))));
            String a = deploy(ROOT, "A", null);
            String a1 = deploy(node(a).getConversationId(), "A1", null);
            for (String child : List.of(a, a1)) {
                AgentProfile p = m.profileFor(node(child).getConversationId(), "u-1").orElseThrow();
                assertThat(p.allowsSkill("YouTube")).isTrue();
                assertThat(p.allowsSkill("Jira")).isFalse();
                assertThat(p.allowsSkill("MCP: Added Later")).isFalse();
                assertThat(p.allowsSkill("Reply style", false)).isTrue();
                assertThat(p.allows("searchIndexedSources")).isFalse();
            }
        }

        @Test
        @DisplayName("a child of an agent pinned to a connection uses that connection too, at every depth; a locked root pins nothing")
        void inheritsRootConnectionPins() {
            java.util.UUID channel = java.util.UUID.randomUUID();
            java.util.UUID site = java.util.UUID.randomUUID();
            AgentTreeModule m = module(rootModule(new AgentProfile(null, null, null, null, null, "agent:7", null, false, null,
                    java.util.Map.of("youtube", channel, "JIRA", site))));
            String a = deploy(ROOT, "A", null);
            String a1 = deploy(node(a).getConversationId(), "A1", null);
            for (String child : List.of(a, a1)) {
                AgentProfile p = m.profileFor(node(child).getConversationId(), "u-1").orElseThrow();
                assertThat(p.connectionPin("youtube")).contains(channel);
                assertThat(p.connectionPin("jira")).as("a service named in any case").contains(site);
                assertThat(p.connectionPin("confluence")).isEmpty();
            }
            assertThat(AgentProfile.LOCKED.connectionPin("youtube")).isEmpty();
            assertThat(new AgentProfile(null, null, null, null, null, null, null, true, null,
                    java.util.Map.of("youtube", channel)).connectionPin("youtube")).as("locked: nothing is pinned").isEmpty();
        }

        @Test
        @DisplayName("at the deepest level deployAgent is not given; a locked root locks its children; another user gets nothing")
        void limits() {
            AgentTreeModule m = module();
            String a = deploy(ROOT, "A", null);
            String a1 = deploy(node(a).getConversationId(), "A1", null);
            assertThat(m.profileFor(node(a).getConversationId(), "u-1").orElseThrow().allows("deployAgent")).isTrue();
            assertThat(m.profileFor(node(a1).getConversationId(), "u-1").orElseThrow().allows("deployAgent")).isFalse();
            assertThat(m.profileFor(node(a).getConversationId(), "u-2").orElseThrow().locked()).isTrue();

            AgentTreeModule lockedRoot = module(rootModule(AgentProfile.LOCKED));
            assertThat(lockedRoot.profileFor(node(a).getConversationId(), "u-1").orElseThrow().locked()).isTrue();
        }

        @Test
        @DisplayName("what it runs on: its own model, else its root's (a saved agent's), else the chat's - never a Settings model for deployed agents")
        void runsOn() {
            AgentTreeModule plain = module();
            String a = deploy(ROOT, "A", null);
            assertThat(plain.runsOn(node(a), null)).isEqualTo(new AgentTreeModule.RunsOn(null, null));
            // A model an older build stored for deployed agents is read past: there is no such setting.
            child = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(
                    Map.of("provider", "p-cheap", "model", "cheap-model"), SubAgentSettings.class);
            assertThat(plain.runsOn(node(a), null)).isEqualTo(new AgentTreeModule.RunsOn(null, null));

            AgentProfile saved = new AgentProfile("p-saved", "saved-model", null, null, null, null, null);
            assertThat(plain.runsOn(node(a), saved)).isEqualTo(new AgentTreeModule.RunsOn("p-saved", "saved-model"));

            AgentNode own = node(a);
            own.setProviderId("p-own");
            own.setModel("own-model");
            assertThat(plain.runsOn(own, saved)).isEqualTo(new AgentTreeModule.RunsOn("p-own", "own-model"));
            // And profileFor runs it on the same.
            nodes.save(own);
            AgentTreeModule withRoot = module(rootModule(saved));
            assertThat(withRoot.profileFor(own.getConversationId(), "u-1").orElseThrow().model()).isEqualTo("own-model");
            assertThat(withRoot.rootProfile(ROOT, "u-1")).isEqualTo(saved);
        }

        @Test
        @DisplayName("its system prompt says which agent it is and how it reports - nothing that moves between turns")
        void identity() {
            String a = deploy(ROOT, "Compare release notes", "LONG");
            String line = AgentTreeModule.identity(node(a), 2);
            assertThat(line).contains("Compare release notes", "REPORT", "tellAgent", "LONG-LIVED", "level 1 of 2");
            assertThat(line).doesNotContain(Instant.now().toString().substring(0, 10));
        }
    }

    @Nested
    @DisplayName("alarms")
    class Alarms {

        @Test
        @DisplayName("an alarm is a minute to 30 days ahead, at most 20 pending, and goes off into the inbox once")
        void alarm() {
            AgentAlarms alarms = new AgentAlarms(alarmRows, inbox);
            assertThat(alarms.set(ROOT, null, 0, "too soon")).containsEntry("success", false);
            assertThat(alarms.set(ROOT, null, 60 * 24 * 31, "too late")).containsEntry("success", false);
            assertThat(alarms.set(ROOT, null, 5, " ")).containsEntry("success", false);
            assertThat(alarms.set(ROOT, "2026-13-01", null, "bad date")).containsEntry("success", false);
            Map<String, Object> set = alarms.set(ROOT, null, 5, "Check the build.");
            assertThat(set).containsEntry("success", true);

            alarms.tick();
            assertThat(inbox.hasPending(ROOT)).as("not due yet").isFalse();

            AgentAlarm a = alarmRows.findById(UUID.fromString((String) set.get("alarmId"))).orElseThrow();
            a.setDueAt(Instant.now().minusSeconds(1));
            alarms.tick();
            assertThat(inbox.take(ROOT)).startsWith("[Alarm]").contains("Check the build.");
            alarms.tick();
            assertThat(inbox.hasPending(ROOT)).as("one-shot").isFalse();

            for (int i = 0; i < AgentAlarms.MAX_PENDING; i++) alarms.set(ROOT, null, 10 + i, "n" + i);
            assertThat(alarms.set(ROOT, null, 60, "one too many")).containsEntry("success", false);
            String first = (String) ((Map<?, ?>) ((List<?>) alarms.list(ROOT).get("alarms")).get(0)).get("alarmId");
            assertThat(alarms.cancel(ROOT, first)).containsEntry("success", true);
            assertThat(alarms.cancel("conv-other", first)).containsEntry("success", false);
        }
    }

    @Nested
    @DisplayName("the inbox")
    class Inbox {

        @Test
        @DisplayName("what is taken is delivered once, under a heading saying what and whom it is from")
        void takeOnce() {
            UUID from = UUID.randomUUID();
            inbox.post(ROOT, AgentInbox.Kind.REPORT, from, "Reviewer", "LGTM");
            inbox.post(ROOT, AgentInbox.Kind.BACKGROUND, null, "runCommand (job 1)", "exit 0");
            String text = inbox.take(ROOT);
            assertThat(text).isEqualTo("[Report from Reviewer (agent " + from + ")]\nLGTM\n\n"
                    + "[Background result runCommand (job 1)]\nexit 0");
            assertThat(AgentInbox.isInboxMessage(text)).isTrue();
            assertThat(inbox.take(ROOT)).isNull();
            assertThat(inbox.hasPending(ROOT)).isFalse();
        }

        @Test
        @DisplayName("what waited before a restart is known again and wakes its conversation")
        void survivesRestart() {
            inbox.post(ROOT, AgentInbox.Kind.ALARM, null, null, "wake up");
            AgentInbox fresh = new AgentInbox(inboxRows, published::add);
            published.clear();
            fresh.loadPending();
            assertThat(fresh.hasPending(ROOT)).isTrue();
            assertThat(published).contains(new AgentInboxPostedEvent(ROOT));
        }

        @Test
        @DisplayName("waiting for something returns as soon as it is posted")
        void awaitWakes() throws Exception {
            Thread poster = new Thread(() -> {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                }
                inbox.post(ROOT, AgentInbox.Kind.REPORT, null, "x", "here");
            });
            long started = System.currentTimeMillis();
            poster.start();
            List<AgentInbox.Taken> got = inbox.await(ROOT, t -> t.kind() == AgentInbox.Kind.REPORT, Duration.ofSeconds(10), () -> false);
            assertThat(got).hasSize(1);
            assertThat(System.currentTimeMillis() - started).isLessThan(5_000);
            poster.join();
            verify(chat, never()).runUnattended(anyString(), anyString(), anyString(), anyString(), any(), anyList());
        }
    }
}
