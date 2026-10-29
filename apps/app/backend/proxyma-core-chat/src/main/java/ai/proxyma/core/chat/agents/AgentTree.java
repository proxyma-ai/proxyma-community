package ai.proxyma.core.chat.agents;

import ai.proxyma.core.chat.event.ChatEvent;
import ai.proxyma.core.chat.event.ConversationsDeletedEvent;
import ai.proxyma.core.chat.model.AgentEvent;
import ai.proxyma.core.chat.model.AgentNode;
import ai.proxyma.core.chat.repository.AgentAlarmRepository;
import ai.proxyma.core.chat.repository.AgentNodeRepository;
import ai.proxyma.core.chat.service.ChatEventSink;
import ai.proxyma.core.chat.service.ChatService;
import ai.proxyma.core.common.concurrent.BlockingWork;
import ai.proxyma.core.settings.dto.SubAgentSettings;
import ai.proxyma.core.settings.service.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * THE AGENT TREE (2026-10-28, the maintainer's design): there are no sub-agents, only agents. Every
 * agent - the one in the conversation the user opened included - may deploy child agents, and they
 * theirs, down to Settings > Agents' depth. Each child is a CONVERSATION of its own, run by the same
 * loop as every chat (ChatService.turn), on its own thread: nothing runs inside another agent's tool
 * call, so the tree's depth never becomes call-stack depth.
 *
 * <p>How they talk is a mailbox (AgentInbox): a parent's task or message, a child's report, a
 * peer's note. A child REPORTS by ending a turn: the final answer of a turn that worked on something
 * for its parent goes to the parent's inbox - unless the child still has work of its own running
 * (its own children, a background job, a write waiting for approval), in which case it waits, and the
 * turn that ends after that work reports. What the USER says to a child goes to its parent too, every
 * time, with the answer.
 *
 * <p>The parent has full control of its children - message, pause, resume, stop, dismiss - and of
 * theirs; the user, of every agent in a tree they own. A child can do no more than its parent: it
 * runs under the root's agent profile (AgentTreeModule), in the parent's permission mode.
 *
 * <p>Since 2026-10-29 (the Constellations samples' gaps): an agent may be given a BUDGET and a
 * DEADLINE, which cover everything it deploys - spent or passed, the tree stops that agent and its
 * subtree and its parent is told why (checked before every model call, and the deadlines every half
 * minute); its report may be CHECKED by a verifier before it is delivered (AgentVerifier); it may be
 * one item of a FAN-OUT, whose agents report to the fan-out (AgentFanOuts); and everything is
 * recorded for the timeline (AgentEvents).
 */
@Service
public class AgentTree {

    private static final Logger log = LoggerFactory.getLogger(AgentTree.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The surface child conversations are held under: out of History and of conversation search. */
    public static final String SURFACE = "agent";

    static final int RUNNING_PER_PARENT = 8;
    static final int LIVE_PER_TREE = 32;
    static final int REPORT_MAX = 30_000;
    public static final Duration AWAIT_MAX = Duration.ofMinutes(10);
    /** The tool that waits on {@link #await}; ChatService gives it a timeout as long as its wait. */
    public static final String AWAIT_TOOL = "awaitAgents";
    // Bumped by abandonWaits: a wait started under an older value has been given up by its caller.
    private final Map<String, java.util.concurrent.atomic.AtomicLong> waitGenerations = new ConcurrentHashMap<>();
    // The waits running in each conversation, each with how many agents it is still for - what a page
    // shows of a turn parked in awaitAgents (waitingOn). An entry lives exactly as long as its wait.
    private final Map<String, Map<Object, Integer>> waits = new ConcurrentHashMap<>();
    /** The most one agent may be given to spend, in US dollars, and the longest deadline, in minutes. */
    static final double BUDGET_MAX = 1_000;
    static final int MINUTES_MAX = 7 * 24 * 60;
    /** Messages from one agent to another in ten minutes - a back-and-forth is bounded too. */
    static final int PAIR_RATE = 20;
    static final long PAIR_WINDOW_MS = Duration.ofMinutes(10).toMillis();
    /** How long a subtree's spend is trusted before it is added up again. */
    static final long SPEND_TTL_MS = 5_000;

    static final Set<String> BUSY = Set.of(AgentNode.RUNNING, AgentNode.WAITING);
    /** An agent that is not over: working, waiting on work of its own, idle (LONG) or paused. */
    public static final Set<String> LIVE = Set.of(AgentNode.RUNNING, AgentNode.WAITING, AgentNode.IDLE, AgentNode.PAUSED);

    private final AgentNodeRepository nodes;
    private final AgentInbox inbox;
    private final AgentAlarmRepository alarms;
    private final ObjectProvider<ChatService> chat;
    private final SettingsService settings;
    private final ChatEventSink sink;
    private final ApplicationEventPublisher events;

    // conversation id -> its node id; a conversation known to be no agent's maps to NONE. Asked on
    // every step of every turn, so read from the database once per conversation.
    private static final UUID NONE = new UUID(0, 0);
    private final ConcurrentHashMap<String, UUID> nodeOf = new ConcurrentHashMap<>();
    // Where a child's turns run: a worker of their own, never the deploying agent's thread.
    private java.util.concurrent.Executor executor = BlockingWork.executor();

    // The tree's helpers, each optional - a test builds the tree alone.
    private AgentEvents record;
    private ObjectProvider<AgentFanOuts> fanOuts;
    private ObjectProvider<AgentVerifier> verifier;
    private ObjectProvider<AgentFiles> files;
    // What each conversation has spent, in US dollars. Defaults to asking the chat one at a time.
    private Function<Collection<String>, Double> costOf = this::costOneByOne;

    /** Tests only: run what would start on a worker on the caller's thread instead. */
    void runInline() {
        executor = Runnable::run;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setRecord(AgentEvents record) {
        this.record = record;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setHelpers(ObjectProvider<AgentFanOuts> fanOuts, ObjectProvider<AgentVerifier> verifier) {
        this.fanOuts = fanOuts;
        this.verifier = verifier;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setFiles(ObjectProvider<AgentFiles> files) {
        this.files = files;
    }

    // Which model an agent may name for one it deploys (AgentModels); absent in tests that build the tree by hand.
    private ObjectProvider<AgentModels> models;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setModels(ObjectProvider<AgentModels> models) {
        this.models = models;
    }

    /**
     * Why an agent may not name {@code model} for the agents it deploys, or null when it may (or names
     * none). Asked by deployAgents and deliberate before they start, so a bad name is refused at once.
     */
    public String modelRefusal(String model) {
        AgentModels m = models == null ? null : models.getIfAvailable();
        if (m == null || model == null || model.isBlank()) return null;
        AgentModels.Choice c = m.choose(null, model);
        return c.ok() ? null : c.error();
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setChatMemory(ai.proxyma.core.chat.service.SmartChatMemory memory) {
        if (memory != null) {
            costOf = ids -> memory.findConversations(ids).stream()
                    .mapToDouble(ai.proxyma.core.chat.model.Conversation::getEstimatedCostUsd).sum();
        }
    }

    /** Tests: what conversations cost. */
    void costsFrom(Function<Collection<String>, Double> costOf) {
        this.costOf = costOf;
    }

    // Conversations whose next turn was started by the tree (a task), not by a person.
    private final Set<String> dispatched = ConcurrentHashMap.newKeySet();
    // What the user typed into a child's running turn, to forward with its answer.
    private final ConcurrentHashMap<String, List<String>> userSaid = new ConcurrentHashMap<>();
    // One lock per tree, so two deployments at once cannot both pass a cap.
    private final ConcurrentHashMap<String, Object> treeLocks = new ConcurrentHashMap<>();
    // When each agent last messaged each other one, for PAIR_RATE.
    private final ConcurrentHashMap<String, Deque<Long>> sent = new ConcurrentHashMap<>();
    // Subtree spend, conversation -> {spent, read at}.
    private final ConcurrentHashMap<String, double[]> spendCache = new ConcurrentHashMap<>();
    long spendTtlMs = SPEND_TTL_MS;

    public AgentTree(AgentNodeRepository nodes, AgentInbox inbox, AgentAlarmRepository alarms,
                     ObjectProvider<ChatService> chat, SettingsService settings, ChatEventSink sink,
                     ApplicationEventPublisher events) {
        this.nodes = nodes;
        this.inbox = inbox;
        this.alarms = alarms;
        this.chat = chat;
        this.settings = settings;
        this.sink = sink;
        this.events = events;
    }

    // -- What a conversation is in the tree ---------------------------------------------------------

    /** The node of a child conversation, or empty for a conversation the user opened. */
    public Optional<AgentNode> node(String conversationId) {
        if (conversationId == null) return Optional.empty();
        UUID id = nodeOf.get(conversationId);
        if (NONE.equals(id)) return Optional.empty();
        Optional<AgentNode> n = id != null ? nodes.findById(id) : nodes.findByConversationId(conversationId);
        nodeOf.put(conversationId, n.map(AgentNode::getId).orElse(NONE));
        return n;
    }

    /** Whether a conversation is an agent another agent deployed. */
    public boolean isChild(String conversationId) {
        return node(conversationId).isPresent();
    }

    /** The conversation the user opened at the top of this one's tree - itself for a root. */
    public String rootOf(String conversationId) {
        return node(conversationId).map(AgentNode::getRootConversationId).orElse(conversationId);
    }

    /** How deep a conversation sits: 0 for the one the user opened. */
    public int depthOf(String conversationId) {
        return node(conversationId).map(AgentNode::getDepth).orElse(0);
    }

    public int maxDepth() {
        return subAgentSettings().maxDepth();
    }

    /** Whether agents this conversation deployed are still working - it is not finished while they are. */
    public boolean hasBusyChildren(String conversationId) {
        return conversationId != null && nodes.countByParentConversationIdAndStatusIn(conversationId, BUSY) > 0;
    }

    /** Whether an agent in this conversation may deploy another. */
    public boolean mayDeploy(String conversationId) {
        return depthOf(conversationId) < maxDepth();
    }

    /** Whether a turn may be started for this conversation now: not for a paused or ended agent. */
    public boolean mayWake(String conversationId) {
        return node(conversationId).map(n -> !AgentNode.PAUSED.equals(n.getStatus())
                && !AgentNode.STOPPED.equals(n.getStatus())).orElse(true);
    }

    // -- Deploying ----------------------------------------------------------------------------------

    /**
     * What an agent is deployed with. {@code budgetUsd} and {@code minutes} cover it and everything it
     * deploys; {@code verify} has a verifier check its reports before they are delivered.
     */
    public record Deploy(String title, String task, String lifetime, String providerId, String model,
                         Double budgetUsd, Integer minutes, boolean verify, List<String> files) {
        public Deploy(String title, String task, String lifetime, String providerId, String model) {
            this(title, task, lifetime, providerId, model, null, null, false, null);
        }

        public Deploy(String title, String task, String lifetime, String providerId, String model,
                      Double budgetUsd, Integer minutes, boolean verify) {
            this(title, task, lifetime, providerId, model, budgetUsd, minutes, verify, null);
        }
    }

    /** Where only the tree's own helpers put a child: one item of a fan-out, or a verifier of another. */
    record Placement(UUID fanOutId, Integer itemIndex, String role, UUID verifiesAgentId) {
        static final Placement NONE = new Placement(null, null, null, null);
    }

    /** Deploys a child of {@code parentConversationId}'s agent and starts it on its task. */
    public Map<String, Object> deploy(String parentConversationId, String userId, Deploy in) {
        return deploy(parentConversationId, userId, in, Placement.NONE);
    }

    Map<String, Object> deploy(String parentConversationId, String userId, Deploy in, Placement at) {
        if (parentConversationId == null) {
            return failure("Agents can only be deployed inside a conversation.");
        }
        if (in == null || in.task() == null || in.task().isBlank()) return failure("task must not be blank.");
        if (in.budgetUsd() != null && (!(in.budgetUsd() > 0) || in.budgetUsd() > BUDGET_MAX)) {
            return failure("budgetUsd is more than 0 and at most " + (int) BUDGET_MAX + " (US dollars), or left out.");
        }
        if (in.minutes() != null && (in.minutes() < 1 || in.minutes() > MINUTES_MAX)) {
            return failure("minutes is from 1 to " + MINUTES_MAX + ", or left out.");
        }
        int depth = depthOf(parentConversationId) + 1;
        int max = maxDepth();
        if (depth > max) {
            return failure("This agent is at the deepest level allowed (" + max + "), so it cannot deploy "
                    + "agents of its own. Do the work yourself, or report back to your parent.");
        }
        if (!mayWake(parentConversationId)) return failure("A paused or stopped agent cannot deploy agents.");
        // A named model must be one a provider lists and Settings allow; it runs on the provider listing it.
        String providerId = blankToNull(in.providerId());
        String model = blankToNull(in.model());
        AgentModels chooser = models == null ? null : models.getIfAvailable();
        if (model != null && chooser != null) {
            AgentModels.Choice c = chooser.choose(providerId, model);
            if (!c.ok()) return failure(c.error());
            providerId = c.providerId();
            model = c.model();
        }
        AgentFiles handing = in.files() == null || in.files().isEmpty() || files == null ? null : files.getIfAvailable();
        if (handing != null) {
            String bad = handing.checkDown(parentConversationId, userId, in.files());
            if (bad != null) return failure(bad);
        }
        String root = rootOf(parentConversationId);
        AgentNode saved;
        synchronized (treeLocks.computeIfAbsent(root, k -> new Object())) {
            if (nodes.countByParentConversationIdAndStatusIn(parentConversationId, BUSY) >= RUNNING_PER_PARENT) {
                return failure("At most " + RUNNING_PER_PARENT + " of your agents can work at once. Wait for a report "
                        + "(awaitAgents), or stop one with controlAgent. For many items, use deployAgents, which queues "
                        + "them.");
            }
            if (nodes.countByRootConversationIdAndStatusIn(root, LIVE) >= LIVE_PER_TREE) {
                return failure("This conversation already has " + LIVE_PER_TREE + " live agents. Dismiss or stop some "
                        + "with controlAgent before deploying more.");
            }
            AgentNode n = new AgentNode();
            n.setConversationId(UUID.randomUUID().toString());
            n.setParentConversationId(parentConversationId);
            n.setRootConversationId(root);
            n.setUserId(userId);
            n.setDepth(depth);
            n.setLifetime(AgentNode.LONG.equalsIgnoreCase(in.lifetime() == null ? "" : in.lifetime().strip())
                    ? AgentNode.LONG : AgentNode.SHORT);
            n.setTitle(title(in.title(), in.task()));
            n.setTask(AgentInbox.clip(in.task().strip(), AgentNode.TASK_MAX));
            n.setProviderId(providerId);
            n.setModel(model);
            n.setStatus(AgentNode.RUNNING);
            n.setReportOwed(true);
            n.setBudgetUsd(in.budgetUsd());
            if (in.minutes() != null) n.setDeadlineAt(Instant.now().plus(Duration.ofMinutes(in.minutes())));
            n.setVerify(in.verify());
            n.setFanOutId(at.fanOutId());
            n.setItemIndex(at.itemIndex());
            n.setRole(at.role());
            n.setVerifiesAgentId(at.verifiesAgentId());
            saved = nodes.save(n);
        }
        nodeOf.put(saved.getConversationId(), saved.getId());
        ChatService c = chat.getObject();
        // The parent's permission mode: a child can do no more without asking than its parent can.
        c.setPermissionMode(saved.getConversationId(), c.permissionMode(parentConversationId));
        recordEvent(saved, AgentEvent.DEPLOYED, saved.getStatus(), null, saved.getTitle());
        publishStatus(saved);
        String task = saved.getTask();
        if (handing != null) {
            // Before its first step, so its first script can read them.
            List<String> handed = handing.handDown(parentConversationId, saved.getConversationId(), userId, in.files());
            if (!handed.isEmpty()) {
                task = task + "\n\nFiles handed to you, in your attachments (a script reads attachments/<name>): "
                        + String.join(", ", handed) + ".";
            }
        }
        start(saved, task);
        log.info("[Agents] deployed agentId={} depth={} lifetime={} under conversation={}", saved.getId(),
                depth, saved.getLifetime(), parentConversationId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("agentId", saved.getId().toString());
        // For the page: where the agent's own conversation is, to open it from its panel.
        out.put("conversationId", saved.getConversationId());
        out.put("status", AgentNode.RUNNING);
        out.put("note", "It works on its own. Its report arrives as a message beginning \"[Report from "
                + saved.getTitle() + "\". Carry on or end your turn; call awaitAgents only when your next "
                + "step needs the report.");
        return out;
    }

    // Starts a turn on the child with this message - its task, or what reached it while idle.
    private void start(AgentNode n, String message) {
        dispatched.add(n.getConversationId());
        executor.execute(() -> {
            try {
                chat.getObject().runUnattended(n.getConversationId(), message, n.getUserId(), SURFACE,
                        chat.getObject().permissionMode(n.getConversationId()), List.of());
            } catch (RuntimeException e) {
                dispatched.remove(n.getConversationId());
                log.warn("[Agents] agentId={} could not start: {}", n.getId(), e.getMessage());
                settle(n.getConversationId(), null, e.getMessage());
            }
        });
    }

    // -- Messaging ----------------------------------------------------------------------------------

    /**
     * A message from one agent to another: {@code to} is "parent", or an agent's id - a child or any
     * descendant of the sender, or (with peer messaging on) a sibling. Delivered through the
     * recipient's inbox: read at its next step, or a turn is started for it. At most
     * {@value #PAIR_RATE} from one agent to another in ten minutes.
     */
    public Map<String, Object> tell(String fromConversationId, String to, String text) {
        if (fromConversationId == null) return failure("Agents can only message each other inside a conversation.");
        if (text == null || text.isBlank()) return failure("message must not be blank.");
        Optional<AgentNode> me = node(fromConversationId);
        String title = me.map(AgentNode::getTitle).orElse("your parent");
        UUID fromId = me.map(AgentNode::getId).orElse(null);
        if ("parent".equalsIgnoreCase(to == null ? "" : to.strip())) {
            if (me.isEmpty()) return failure("You have no parent agent: you are the agent the user is talking to.");
            String limited = rateCheck(fromConversationId, me.get().getParentConversationId(), "your parent");
            if (limited != null) return failure(limited);
            inbox.post(me.get().getParentConversationId(), AgentInbox.Kind.MESSAGE, fromId, title, text.strip());
            recordEvent(me.get(), AgentEvent.MESSAGE, null, null, "to its parent");
            return Map.of("success", true, "note", "Delivered. Its answer, if any, arrives as a message to you.");
        }
        Optional<AgentNode> target = byId(to);
        if (target.isEmpty()) return failure("No agent with id " + to + ". listAgents names yours.");
        AgentNode t = target.get();
        boolean descendant = isAncestor(fromConversationId, t);
        boolean sibling = me.isPresent() && subAgentSettings().peerMessaging() && !t.getId().equals(me.get().getId())
                && t.getParentConversationId().equals(me.get().getParentConversationId());
        if (!descendant && !sibling) {
            return failure("You can message your parent (to \"parent\") and the agents you deployed"
                    + (subAgentSettings().peerMessaging() ? ", and your siblings" : "") + " - not " + t.getTitle() + ".");
        }
        if (AgentNode.STOPPED.equals(t.getStatus()) || AgentNode.FAILED.equals(t.getStatus())) {
            return failure(t.getTitle() + " has stopped. Deploy a new agent for new work.");
        }
        String limited = rateCheck(fromConversationId, t.getConversationId(), t.getTitle());
        if (limited != null) return failure(limited);
        giveWork(t, AgentInbox.Kind.MESSAGE, fromId, title, text.strip());
        if (record != null) {
            record.write(t.getRootConversationId(), fromConversationId, fromId, AgentEvent.MESSAGE, null, t.getId(),
                    "to " + t.getTitle());
        }
        return Map.of("success", true, "status", t.getStatus(),
                "note", "Delivered. " + t.getTitle() + " reads it at its next step; its report arrives as a message to you.");
    }

    // Null when the sender may message the recipient now, else the sentence saying why not.
    private String rateCheck(String fromConversationId, String toConversationId, String toName) {
        long now = System.currentTimeMillis();
        Deque<Long> times = sent.computeIfAbsent(fromConversationId + ">" + toConversationId, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > PAIR_WINDOW_MS) times.pollFirst();
            if (times.size() >= PAIR_RATE) {
                return "You have sent " + toName + " " + PAIR_RATE + " messages in ten minutes - the most allowed. "
                        + "Wait for its report, or decide with what you have.";
            }
            times.addLast(now);
        }
        return null;
    }

    // Something for the agent to work on: it owes a report again and is woken, unless paused.
    private void giveWork(AgentNode t, AgentInbox.Kind kind, UUID fromId, String fromTitle, String text) {
        t.setReportOwed(true);
        if (!AgentNode.PAUSED.equals(t.getStatus())) t.setStatus(AgentNode.RUNNING);
        t.setEndedAt(null);
        nodes.save(t);
        publishStatus(t);
        inbox.post(t.getConversationId(), kind, fromId, fromTitle, text);
    }

    // -- Waiting for reports ------------------------------------------------------------------------

    /**
     * Waits for reports from these children (all of the caller's busy ones when none is named) - or
     * from a fan-out or a deliberation the caller started, by its id. The agents of a fan-out, and a
     * verifier, report elsewhere, so waiting for "all" waits for their fan-out instead.
     */
    public Map<String, Object> await(String parentConversationId, List<String> agentIds, Integer timeoutSeconds) {
        if (parentConversationId == null) return failure("awaitAgents works only inside a conversation.");
        List<AgentNode> children = nodes.findByParentConversationIdOrderByCreatedAtAsc(parentConversationId);
        Set<UUID> wanted = ConcurrentHashMap.newKeySet();
        // Ids of work that is not one node: a fan-out or a deliberation, answered by its helper.
        Set<UUID> groups = ConcurrentHashMap.newKeySet();
        AgentFanOuts f = fanOuts == null ? null : fanOuts.getIfAvailable();
        if (agentIds == null || agentIds.isEmpty()) {
            children.stream().filter(c -> c.isReportOwed() && !c.isEnded() && c.getFanOutId() == null && !c.isVerifier())
                    .forEach(c -> wanted.add(c.getId()));
            if (f != null) groups.addAll(f.runningOf(parentConversationId));
        } else {
            for (String raw : agentIds) {
                Optional<AgentNode> c = byId(raw).filter(n -> n.getParentConversationId().equals(parentConversationId));
                if (c.isPresent()) {
                    wanted.add(c.get().getId());
                    continue;
                }
                UUID group = f == null ? null : f.groupOf(parentConversationId, raw);
                if (group == null) return failure("No agent, fan-out or deliberation of yours with id " + raw + ".");
                groups.add(group);
            }
        }
        wanted.addAll(groups);
        if (wanted.isEmpty()) return Map.of("success", true, "reports", List.of(), "note", "None of your agents owes a report.");
        Duration timeout = Duration.ofSeconds(Math.max(5, Math.min(AWAIT_MAX.toSeconds(),
                timeoutSeconds == null ? AWAIT_MAX.toSeconds() : timeoutSeconds)));
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        java.util.concurrent.atomic.AtomicLong generation =
                waitGenerations.computeIfAbsent(parentConversationId, k -> new java.util.concurrent.atomic.AtomicLong());
        long startedAt = generation.get();
        java.util.function.BooleanSupplier abandoned = () -> generation.get() != startedAt;
        // Everything this wait took, so a wait its caller gave up can put it back.
        List<AgentInbox.Taken> took = new ArrayList<>();
        List<Map<String, Object>> reports = new ArrayList<>();
        Set<UUID> heard = ConcurrentHashMap.newKeySet();
        java.util.function.Predicate<UUID> over = id -> groups.contains(id)
                ? f == null || !f.isRunning(id)
                : nodes.findById(id).map(n -> !n.isReportOwed() || n.isEnded()).orElse(true);
        Object wait = new Object();
        try {
            return awaitLoop(parentConversationId, wanted, groups, f, deadline, abandoned, took, reports, heard, over, wait);
        } finally {
            waits.computeIfPresent(parentConversationId, (k, m) -> {
                m.remove(wait);
                return m.isEmpty() ? null : m;
            });
        }
    }

    /** How many agents the waits running in this conversation (awaitAgents) are still for; 0 when none. In memory. */
    public int waitingOn(String conversationId) {
        Map<Object, Integer> m = conversationId == null ? null : waits.get(conversationId);
        if (m == null) return 0;
        int n = 0;
        for (Integer v : m.values()) n += v;
        return n;
    }

    private void waitingFor(String conversationId, Object wait, int agents) {
        waits.compute(conversationId, (k, m) -> {
            Map<Object, Integer> held = m == null ? new ConcurrentHashMap<>() : m;
            held.put(wait, Math.max(0, agents));
            return held;
        });
    }

    private Map<String, Object> awaitLoop(String parentConversationId, Set<UUID> wanted, Set<UUID> groups, AgentFanOuts f,
                                          long deadline, java.util.function.BooleanSupplier abandoned,
                                          List<AgentInbox.Taken> took, List<Map<String, Object>> reports,
                                          Set<UUID> heard, java.util.function.Predicate<UUID> over, Object wait) {
        while (heard.size() < wanted.size() && !abandoned.getAsBoolean()) {
            waitingFor(parentConversationId, wait, wanted.size() - heard.size());
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) break;
            List<AgentInbox.Taken> got = inbox.await(parentConversationId,
                    t -> t.kind() == AgentInbox.Kind.REPORT && t.fromAgentId() != null && wanted.contains(t.fromAgentId())
                            && !heard.contains(t.fromAgentId()),
                    Duration.ofMillis(left),
                    () -> abandoned.getAsBoolean() || wanted.stream().allMatch(id -> heard.contains(id) || over.test(id)));
            took.addAll(got);
            if (abandoned.getAsBoolean()) break;
            if (got.isEmpty()) {
                // Ended without a report waiting (stopped, or its report taken by a step already).
                wanted.forEach(id -> {
                    if (!heard.contains(id) && !groups.contains(id) && over.test(id)) {
                        heard.add(id);
                        nodes.findById(id).ifPresent(n -> reports.add(reportView(n, n.getLastReport())));
                    } else if (!heard.contains(id) && groups.contains(id) && over.test(id)) {
                        heard.add(id);
                    }
                });
                if (System.currentTimeMillis() >= deadline) break;
                continue;
            }
            for (AgentInbox.Taken t : got) {
                heard.add(t.fromAgentId());
                Optional<AgentNode> n = nodes.findById(t.fromAgentId());
                if (n.isPresent()) {
                    reports.add(reportView(n.get(), t.text()));
                } else {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("agentId", t.fromAgentId().toString());
                    r.put("title", t.fromTitle());
                    r.put("report", t.text());
                    reports.add(r);
                }
            }
        }
        if (abandoned.getAsBoolean()) {
            // Nobody reads this result any more: what it took goes back, and reaches the agent as mail.
            for (AgentInbox.Taken t : took) inbox.post(parentConversationId, t.kind(), t.fromAgentId(), t.fromTitle(), t.text());
            if (!took.isEmpty()) {
                log.info("[Agents] an abandoned wait in conversation={} put {} report(s) back", parentConversationId, took.size());
            }
            return failure("The wait was given up; any report it held is back in your inbox.");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("reports", reports);
        if (heard.size() < wanted.size()) {
            out.put("note", (wanted.size() - heard.size()) + " agent(s) are still working; their reports arrive as "
                    + "messages to you.");
        }
        return out;
    }

    /**
     * The caller of every wait running in this conversation is gone - Stop, or a tool timeout - so
     * each ends at once and puts back what it took. A report taken by a wait nobody reads any more
     * was lost: the agent was marked as having reported, and its parent never heard it (feedback
     * 8a3e3c01, 2026-10-29).
     */
    public void abandonWaits(String conversationId) {
        if (conversationId == null) return;
        waitGenerations.computeIfAbsent(conversationId, k -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
        inbox.rouse(conversationId);
    }

    private static Map<String, Object> reportView(AgentNode n, String report) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("agentId", n.getId().toString());
        r.put("title", n.getTitle());
        r.put("status", n.getStatus());
        r.put("report", report == null ? "" : report);
        return r;
    }

    /** A descendant's latest report in full, and its scorecard when it was checked. */
    public Map<String, Object> readReport(String conversationId, String agentId) {
        Optional<AgentNode> target = byId(agentId);
        if (target.isEmpty() || !isAncestor(conversationId, target.get())) {
            return failure("No agent you deployed, or that yours deployed, has id " + agentId + ".");
        }
        AgentNode n = target.get();
        Map<String, Object> out = new LinkedHashMap<>(reportView(n, n.getLastReport()));
        out.put("success", true);
        if (n.getVerification() != null) out.put("verification", n.getVerification());
        if (n.getStopNote() != null) out.put("stopped", n.getStopNote());
        return out;
    }

    // -- Control ------------------------------------------------------------------------------------

    /** A parent's control over a child or any of its descendants: stop, pause, resume or dismiss. */
    public Map<String, Object> control(String fromConversationId, String agentId, String action) {
        Optional<AgentNode> target = byId(agentId);
        if (target.isEmpty()) {
            AgentFanOuts f = fanOuts == null ? null : fanOuts.getIfAvailable();
            if (f != null && "stop".equalsIgnoreCase(action == null ? "" : action.strip())
                    && f.groupOf(fromConversationId, agentId) != null) {
                return f.stop(fromConversationId, agentId);
            }
            return failure("No agent with id " + agentId + ".");
        }
        if (!isAncestor(fromConversationId, target.get())) {
            return failure("You can control only the agents you deployed and theirs.");
        }
        return apply(target.get(), action);
    }

    /** The same, for the person who owns the tree: they can do anything to any agent in it. */
    public Map<String, Object> controlAsUser(String userId, String agentId, String action) {
        Optional<AgentNode> target = byId(agentId);
        if (target.isEmpty() || !java.util.Objects.equals(target.get().getUserId(), userId)) {
            return failure("No such agent.");
        }
        return apply(target.get(), action);
    }

    /**
     * The person's control, from a conversation of the tree they opened (ownership checked by the
     * caller): any agent of that tree, at any depth.
     */
    public Map<String, Object> controlInTree(String conversationId, String agentId, String action) {
        Optional<AgentNode> target = byId(agentId);
        if (target.isEmpty() || !target.get().getRootConversationId().equals(rootOf(conversationId))) {
            return failure("No such agent in this conversation.");
        }
        return apply(target.get(), action);
    }

    private Map<String, Object> apply(AgentNode n, String action) {
        String a = action == null ? "" : action.strip().toLowerCase(Locale.ROOT);
        switch (a) {
            case "stop" -> {
                for (AgentNode d : subtree(n)) end(d, AgentNode.STOPPED);
                return Map.of("success", true, "status", AgentNode.STOPPED);
            }
            case "dismiss" -> {
                for (AgentNode d : subtree(n)) end(d, d.getId().equals(n.getId()) ? AgentNode.DONE : AgentNode.STOPPED);
                return Map.of("success", true, "status", AgentNode.DONE);
            }
            case "pause" -> {
                if (n.isEnded()) return failure(n.getTitle() + " has already ended.");
                if (!AgentNode.PAUSED.equals(n.getStatus())) {
                    n.setPausedFrom(n.getStatus());
                    n.setStatus(AgentNode.PAUSED);
                    nodes.save(n);
                    chat.getObject().stop(n.getConversationId());
                    publishStatus(n);
                }
                return Map.of("success", true, "status", AgentNode.PAUSED);
            }
            case "resume" -> {
                if (!AgentNode.PAUSED.equals(n.getStatus())) return failure(n.getTitle() + " is not paused.");
                String back = n.getPausedFrom() == null ? AgentNode.RUNNING : n.getPausedFrom();
                n.setStatus(back);
                n.setPausedFrom(null);
                nodes.save(n);
                publishStatus(n);
                if (inbox.hasPending(n.getConversationId())) {
                    events.publishEvent(new AgentInboxPostedEvent(n.getConversationId()));
                } else if (n.isReportOwed()) {
                    inbox.post(n.getConversationId(), AgentInbox.Kind.MESSAGE, null, "your parent",
                            "You were paused and are resumed. Carry on with your task where you left off.");
                }
                return Map.of("success", true, "status", back);
            }
            default -> {
                return failure("action is stop, pause, resume or dismiss.");
            }
        }
    }

    private void end(AgentNode n, String status) {
        if (n.isEnded() && !AgentNode.DONE.equals(status)) return;
        n.setStatus(status);
        n.setReportOwed(false);
        n.setEndedAt(Instant.now());
        nodes.save(n);
        chat.getObject().stop(n.getConversationId());
        publishStatus(n);
        // An item of a fan-out that ended without reporting still frees its place for the next.
        if (n.getFanOutId() != null) {
            AgentFanOuts f = fanOuts == null ? null : fanOuts.getIfAvailable();
            if (f != null) f.itemEnded(n);
        } else {
            slotFreed(n);
        }
    }

    /** The agents a conversation's agent deployed, newest last; with peer messaging, its siblings too. */
    public Map<String, Object> list(String conversationId) {
        List<Map<String, Object>> mine = new ArrayList<>();
        for (AgentNode c : nodes.findByParentConversationIdOrderByCreatedAtAsc(conversationId)) mine.add(view(c));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("agents", mine);
        Optional<AgentNode> me = node(conversationId);
        if (me.isPresent() && subAgentSettings().peerMessaging()) {
            out.put("siblings", nodes.findByParentConversationIdOrderByCreatedAtAsc(me.get().getParentConversationId())
                    .stream().filter(s -> !s.getId().equals(me.get().getId())).map(AgentTree::view).toList());
        }
        AgentFanOuts f = fanOuts == null ? null : fanOuts.getIfAvailable();
        if (f != null) {
            List<Map<String, Object>> groups = f.views(conversationId);
            if (!groups.isEmpty()) out.put("fanOuts", groups);
        }
        out.put("depth", depthOf(conversationId));
        out.put("maxDepth", maxDepth());
        me.ifPresent(n -> {
            if (n.getBudgetUsd() != null) out.put("yourBudgetUsd", n.getBudgetUsd());
            if (n.getDeadlineAt() != null) out.put("yourDeadline", n.getDeadlineAt().toString());
        });
        return out;
    }

    static Map<String, Object> view(AgentNode n) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("agentId", n.getId().toString());
        v.put("title", n.getTitle());
        v.put("status", n.getStatus());
        v.put("lifetime", n.getLifetime());
        v.put("depth", n.getDepth());
        v.put("owesReport", n.isReportOwed());
        if (n.getBudgetUsd() != null) v.put("budgetUsd", n.getBudgetUsd());
        if (n.getDeadlineAt() != null) v.put("deadline", n.getDeadlineAt().toString());
        if (n.getFanOutId() != null) v.put("fanOutId", n.getFanOutId().toString());
        if (n.isVerifier()) v.put("role", AgentNode.VERIFIER);
        return v;
    }

    // -- A turn ended -------------------------------------------------------------------------------

    /** What the user typed into a child's RUNNING turn - forwarded to its parent with the answer. */
    public void userSpoke(String conversationId, String text) {
        if (text == null || text.isBlank() || !isChild(conversationId)) return;
        userSaid.computeIfAbsent(conversationId, k -> java.util.Collections.synchronizedList(new ArrayList<>())).add(text);
    }

    /**
     * A turn of a conversation has ended (ChatService.turn, last thing). For a child: what the user
     * said goes to its parent with the answer; and a turn that worked on something for its parent
     * reports, unless the child still has work of its own running - then it waits, and the turn that
     * ends after that work reports.
     *
     * @param userMessage the message that opened the turn when a person sent it, else null
     * @param answer      the turn's final answer, or null when it failed
     * @param error       why it failed, or null
     */
    public void turnEnded(String conversationId, String userMessage, String answer, String error) {
        Optional<AgentNode> found = node(conversationId);
        boolean byTree = dispatched.remove(conversationId);
        if (found.isEmpty()) return;
        AgentNode n = nodes.findById(found.get().getId()).orElse(null);
        if (n == null) return;
        List<String> said = new ArrayList<>();
        if (userMessage != null && !byTree) said.add(userMessage);
        List<String> typed = userSaid.remove(conversationId);
        if (typed != null) said.addAll(typed);
        if (!said.isEmpty()) {
            String reply = answer != null ? answer : "(its turn ended without an answer" + (error == null ? "" : ": " + error) + ")";
            inbox.post(n.getParentConversationId(), AgentInbox.Kind.USER_TALK, n.getId(), n.getTitle(),
                    "The user said:\n" + String.join("\n\n", said) + "\n\n" + n.getTitle() + " answered:\n"
                            + AgentInbox.clip(reply, REPORT_MAX));
            recordEvent(n, AgentEvent.USER_TALK, null, null, "the user talked to it");
            // A person's own question is no report on the task: only a turn the tree started, or
            // one that took the agent's mail, reports.
            if (userMessage != null && !byTree) return;
        }
        settle(conversationId, answer, error);
    }

    // Reports to the parent when the child owes one and nothing of its own is still running.
    private void settle(String conversationId, String answer, String error) {
        Optional<AgentNode> found = node(conversationId);
        if (found.isEmpty()) return;
        AgentNode n = nodes.findById(found.get().getId()).orElse(null);
        if (n == null || n.isEnded() || AgentNode.PAUSED.equals(n.getStatus()) || !n.isReportOwed()) return;
        ChatService c = chat.getObject();
        if (answer != null && c.hasBackgroundWork(conversationId)) {
            n.setStatus(AgentNode.WAITING);
            nodes.save(n);
            publishStatus(n);
            return;
        }
        String report = answer != null ? AgentInbox.clip(answer, REPORT_MAX)
                : "I could not finish: my turn failed" + (error == null ? "." : " - " + error);
        AgentVerifier v = verifier == null ? null : verifier.getIfAvailable();
        if (n.isVerifier()) {
            complete(n, report, answer == null);
            if (v != null) v.verdict(n, report, answer == null);
            return;
        }
        // Checked before it is delivered: the agent waits, holding its report, until the verifier's
        // scorecard comes back (AgentVerifier.verdict, then deliverChecked).
        if (answer != null && n.isVerify() && v != null) {
            n.setLastReport(report);
            n.setStatus(AgentNode.WAITING);
            nodes.save(n);
            publishStatus(n);
            if (v.check(n, report)) return;
            report = report + "\n\n[Not verified: no verifier could be deployed.]";
        }
        complete(n, report, answer == null);
        deliverReport(n, report);
    }

    // The agent's work on this report is over: it stops owing one, and ends unless it lives on.
    private void complete(AgentNode n, String report, boolean failed) {
        n.setReportOwed(false);
        n.setLastReport(report);
        if (failed && !n.isLong()) {
            n.setStatus(AgentNode.FAILED);
            n.setEndedAt(Instant.now());
        } else if (n.isLong()) {
            n.setStatus(AgentNode.IDLE);
        } else {
            n.setStatus(AgentNode.DONE);
            n.setEndedAt(Instant.now());
        }
        nodes.save(n);
        publishStatus(n);
        slotFreed(n);
    }

    // A place among its parent's working agents is free: a fan-out waiting for one takes it.
    private void slotFreed(AgentNode n) {
        AgentFanOuts f = fanOuts == null ? null : fanOuts.getIfAvailable();
        if (f == null) return;
        try {
            f.slotFreed(n.getParentConversationId());
        } catch (RuntimeException e) {
            log.warn("[Agents] could not hand a freed place to a fan-out: {}", e.getMessage());
        }
    }

    /** The verifier's way back: a checked report, its scorecard appended, goes where any report goes. */
    void deliverChecked(UUID agentId, String report) {
        AgentNode n = nodes.findById(agentId).orElse(null);
        if (n == null || n.isEnded() || !n.isReportOwed()) return;
        complete(n, report, false);
        deliverReport(n, report);
    }

    // To the fan-out the agent works an item of, or to its parent.
    private void deliverReport(AgentNode n, String report) {
        AgentFanOuts f = n.getFanOutId() == null || fanOuts == null ? null : fanOuts.getIfAvailable();
        if (f != null) {
            recordEvent(n, AgentEvent.REPORT, null, null, "to its fan-out");
            f.itemEnded(n);
            return;
        }
        inbox.post(n.getParentConversationId(), AgentInbox.Kind.REPORT, n.getId(), n.getTitle(), report);
        recordEvent(n, AgentEvent.REPORT, null, node(n.getParentConversationId()).map(AgentNode::getId).orElse(null),
                firstLine(report));
    }

    // -- Budgets and deadlines ----------------------------------------------------------------------

    /**
     * Why this conversation may take no further step, or empty: its own budget or deadline, or an
     * ancestor's, spent or passed. The agent whose limit it is - and everything under it - is stopped
     * here, once, and its parent told why. Asked before every model call (AgentTreeModule.stopReason).
     */
    public Optional<String> checkLimits(String conversationId) {
        Optional<AgentNode> me = node(conversationId);
        if (me.isEmpty()) return Optional.empty();
        AgentNode cur = nodes.findById(me.get().getId()).orElse(null);
        Instant now = Instant.now();
        for (int hop = 0; cur != null && hop <= SubAgentSettings.MAX_DEPTH_CEILING + 1; hop++) {
            if (cur.isEnded() && cur.getStopNote() != null) return Optional.of("Stopped: " + cur.getStopNote());
            if (cur.getDeadlineAt() != null && !now.isBefore(cur.getDeadlineAt())) {
                return Optional.of(enforce(cur, "its deadline (" + cur.getDeadlineAt() + ") has passed"));
            }
            if (cur.getBudgetUsd() != null) {
                double spent = spendOf(cur.getConversationId());
                if (spent >= cur.getBudgetUsd()) {
                    return Optional.of(enforce(cur, String.format(Locale.ROOT,
                            "its budget of $%.2f is spent ($%.2f with the agents it deployed)", cur.getBudgetUsd(), spent)));
                }
            }
            String parent = cur.getParentConversationId();
            cur = node(parent).flatMap(p -> nodes.findById(p.getId())).orElse(null);
        }
        return Optional.empty();
    }

    // Stops the agent whose limit it is, and its subtree, once; tells its parent; returns the sentence.
    private String enforce(AgentNode limited, String why) {
        String note = limited.getTitle() + " was stopped: " + why + ".";
        synchronized (treeLocks.computeIfAbsent(limited.getRootConversationId(), k -> new Object())) {
            AgentNode fresh = nodes.findById(limited.getId()).orElse(limited);
            if (fresh.isEnded()) return "Stopped: " + (fresh.getStopNote() == null ? note : fresh.getStopNote());
            String last = fresh.getLastReport();
            for (AgentNode d : subtree(fresh)) {
                if (d.isEnded()) continue;
                d.setStopNote(d.getId().equals(fresh.getId()) ? note : "Stopped with " + fresh.getTitle() + ": " + why + ".");
                end(d, AgentNode.STOPPED);
            }
            recordEvent(fresh, AgentEvent.LIMIT, AgentNode.STOPPED, null, why);
            log.info("[Agents] agentId={} stopped by its limit: {}", fresh.getId(), why);
            String report = "Stopped by the tree: " + why + ". "
                    + (last == null || last.isBlank() ? "It had not reported yet." : "Its last report:\n" + last);
            fresh = nodes.findById(fresh.getId()).orElse(fresh);
            fresh.setLastReport(AgentInbox.clip(report, REPORT_MAX));
            nodes.save(fresh);
            if (fresh.getFanOutId() == null) {
                inbox.post(fresh.getParentConversationId(), AgentInbox.Kind.REPORT, fresh.getId(), fresh.getTitle(), report);
            }
        }
        return "Stopped: " + note;
    }

    /**
     * Stops every agent of a tree, once - its root's own limit is spent (a saved agent's run, which
     * its module states). Nobody is told but the agents: the root's own turn ends with the reason.
     */
    public void stopTree(String rootConversationId, String why) {
        synchronized (treeLocks.computeIfAbsent(rootConversationId, k -> new Object())) {
            for (AgentNode n : nodes.findByRootConversationIdOrderByCreatedAtAsc(rootConversationId)) {
                if (n.isEnded()) continue;
                n.setStopNote(AgentInbox.clip(why, 480));
                end(n, AgentNode.STOPPED);
            }
        }
    }

    /** Every half minute: an agent whose deadline has passed is stopped even while nothing runs. */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void sweepDeadlines() {
        try {
            Instant now = Instant.now();
            for (AgentNode n : nodes.findByStatusIn(LIVE)) {
                if (n.getDeadlineAt() != null && !now.isBefore(n.getDeadlineAt())) {
                    enforce(n, "its deadline (" + n.getDeadlineAt() + ") has passed");
                }
            }
        } catch (RuntimeException e) {
            log.warn("[Agents] could not check the agents' deadlines: {}", e.getMessage());
        }
    }

    /**
     * What a conversation and every agent under it have spent, in US dollars - trusted for
     * {@value #SPEND_TTL_MS} ms, since it is asked before every step of every agent under a budget.
     */
    public double spendOf(String conversationId) {
        if (conversationId == null) return 0;
        long now = System.currentTimeMillis();
        double[] cached = spendCache.get(conversationId);
        if (cached != null && now - (long) cached[1] < spendTtlMs) return cached[0];
        List<String> ids = new ArrayList<>();
        ids.add(conversationId);
        Map<String, List<AgentNode>> byParent = new HashMap<>();
        for (AgentNode n : nodes.findByRootConversationIdOrderByCreatedAtAsc(rootOf(conversationId))) {
            byParent.computeIfAbsent(n.getParentConversationId(), k -> new ArrayList<>()).add(n);
        }
        ArrayDeque<String> todo = new ArrayDeque<>(List.of(conversationId));
        while (!todo.isEmpty() && ids.size() <= LIVE_PER_TREE * 64) {
            for (AgentNode c : byParent.getOrDefault(todo.poll(), List.of())) {
                ids.add(c.getConversationId());
                todo.add(c.getConversationId());
            }
        }
        double spent;
        try {
            Double s = costOf.apply(ids);
            spent = s == null ? 0 : s;
        } catch (RuntimeException e) {
            log.debug("[Agents] could not add up the spend under {}: {}", conversationId, e.getMessage());
            spent = cached == null ? 0 : cached[0];
        }
        spendCache.put(conversationId, new double[]{spent, now});
        return spent;
    }

    private double costOneByOne(Collection<String> ids) {
        double sum = 0;
        for (String id : ids) {
            try {
                sum += chat.getObject().getConversation(id).getEstimatedCostUsd();
            } catch (RuntimeException e) {
                // Not written yet - an agent that has not started a turn has spent nothing.
            }
        }
        return sum;
    }

    // -- Deletion -----------------------------------------------------------------------------------

    /** A deleted conversation takes its agents' conversations with it, and its inbox and alarms. */
    @EventListener
    public void onConversationsDeleted(ConversationsDeletedEvent event) {
        List<String> ids = event.conversationIds();
        if (ids.isEmpty()) return;
        try {
            List<String> children = new ArrayList<>();
            for (String id : ids) {
                for (AgentNode c : nodes.findByParentConversationIdOrderByCreatedAtAsc(id)) {
                    if (!ids.contains(c.getConversationId())) children.add(c.getConversationId());
                }
            }
            nodes.deleteByConversationIdIn(ids);
            ids.forEach(nodeOf::remove);
            ids.forEach(spendCache::remove);
            inbox.forget(ids);
            alarms.deleteByConversationIdIn(ids);
            for (String child : children) {
                try {
                    chat.getObject().deleteConversation(child);
                } catch (RuntimeException e) {
                    log.warn("[Agents] could not delete agent conversation {}: {}", child, e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            log.warn("[Agents] could not clear the agents of deleted conversations: {}", e.getMessage());
        }
    }

    /** After a restart no turn runs: an agent left RUNNING with nothing waiting is woken to carry on. */
    @EventListener(ApplicationReadyEvent.class)
    public void resumeAfterRestart() {
        try {
            for (AgentNode n : nodes.findByStatusIn(Set.of(AgentNode.RUNNING))) {
                if (!inbox.hasPending(n.getConversationId()) && n.isReportOwed()) {
                    inbox.post(n.getConversationId(), AgentInbox.Kind.MESSAGE, null, "the system",
                            "The application restarted while you were working. Carry on with your task.");
                }
            }
        } catch (RuntimeException e) {
            log.warn("[Agents] could not resume agents after the restart: {}", e.getMessage());
        }
    }

    // -- Reading ------------------------------------------------------------------------------------

    /** Every agent of the tree this conversation is in, for the agents map and the canvas. */
    public List<AgentNode> treeOf(String conversationId) {
        return nodes.findByRootConversationIdOrderByCreatedAtAsc(rootOf(conversationId));
    }

    /** Every agent under these root conversations - the Constellations canvas's live agents. */
    public List<AgentNode> treesOf(java.util.Collection<String> rootConversationIds) {
        return rootConversationIds == null || rootConversationIds.isEmpty() ? List.of()
                : nodes.findByRootConversationIdIn(rootConversationIds);
    }

    /** One deployed agent and what its own conversation spent - for a module that logs a run. */
    public record Usage(String id, String title, String status, long inputTokens, long outputTokens, double costUsd) {}

    /** The agents a conversation deployed, each with its conversation's spend. */
    public List<Usage> usageOfChildren(String parentConversationId) {
        List<Usage> out = new ArrayList<>();
        for (AgentNode n : nodes.findByParentConversationIdOrderByCreatedAtAsc(parentConversationId)) {
            long in = 0;
            long outTokens = 0;
            double cost = 0;
            try {
                var c = chat.getObject().getConversation(n.getConversationId());
                in = c.getInputTokens();
                outTokens = c.getOutputTokens();
                cost = c.getEstimatedCostUsd();
            } catch (RuntimeException e) {
                // Not written yet - an agent that has not started a turn has spent nothing.
            }
            out.add(new Usage(n.getId().toString(), n.getTitle(), n.getStatus(), in, outTokens, cost));
        }
        return out;
    }

    // -- Helpers ------------------------------------------------------------------------------------

    Optional<AgentNode> byId(String raw) {
        if (raw == null) return Optional.empty();
        try {
            return nodes.findById(UUID.fromString(raw.strip()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    // Whether the agent in conversationId deployed t, or deployed one of t's ancestors.
    boolean isAncestor(String conversationId, AgentNode t) {
        if (conversationId == null) return false;
        String parent = t.getParentConversationId();
        for (int hops = 0; parent != null && hops <= SubAgentSettings.MAX_DEPTH_CEILING + 1; hops++) {
            if (parent.equals(conversationId)) return true;
            parent = node(parent).map(AgentNode::getParentConversationId).orElse(null);
        }
        return false;
    }

    private List<AgentNode> subtree(AgentNode n) {
        List<AgentNode> out = new ArrayList<>();
        java.util.ArrayDeque<AgentNode> todo = new java.util.ArrayDeque<>(List.of(n));
        while (!todo.isEmpty() && out.size() <= LIVE_PER_TREE * 4) {
            AgentNode next = todo.poll();
            out.add(next);
            todo.addAll(nodes.findByParentConversationIdOrderByCreatedAtAsc(next.getConversationId()));
        }
        return out;
    }

    /** Tells the parent's page where a child stands (its panel and the agents map follow it), and records it. */
    void publishStatus(AgentNode n) {
        recordEvent(n, AgentEvent.STATUS, n.getStatus(), null, null);
        try {
            Map<String, Object> data = new LinkedHashMap<>(view(n));
            data.put("conversationId", n.getConversationId());
            events.publishEvent(new ChatEvent(n.getParentConversationId(), sink.nextSequence(n.getParentConversationId()),
                    ChatEvent.Type.AGENT_STATUS.value(), n.getId().toString(), null, JSON.writeValueAsString(data)));
        } catch (Exception e) {
            log.debug("[Agents] could not publish the status of agentId={}: {}", n.getId(), e.getMessage());
        }
    }

    void recordEvent(AgentNode n, String kind, String status, UUID to, String detail) {
        if (record != null) record.record(n, kind, status, to, detail);
    }

    private SubAgentSettings subAgentSettings() {
        try {
            SubAgentSettings s = settings.getSettings(SubAgentSettings.PREFIX, SubAgentSettings.class);
            return s == null ? new SubAgentSettings() : s;
        } catch (RuntimeException e) {
            return new SubAgentSettings();
        }
    }

    private static String title(String given, String task) {
        String t = given == null || given.isBlank() ? task.strip() : given.strip();
        t = t.replaceAll("\\s+", " ");
        return t.length() <= 80 ? t : t.substring(0, 77) + "...";
    }

    static String firstLine(String text) {
        if (text == null) return null;
        String t = text.strip();
        int nl = t.indexOf('\n');
        return nl < 0 ? t : t.substring(0, nl);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    static Map<String, Object> failure(String error) {
        return Map.of("success", false, "error", error);
    }
}
