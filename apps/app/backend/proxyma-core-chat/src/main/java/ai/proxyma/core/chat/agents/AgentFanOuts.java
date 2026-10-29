package ai.proxyma.core.chat.agents;

import ai.proxyma.core.chat.event.ConversationsDeletedEvent;
import ai.proxyma.core.chat.model.AgentEvent;
import ai.proxyma.core.chat.model.AgentFanOut;
import ai.proxyma.core.chat.model.AgentNode;
import ai.proxyma.core.chat.repository.AgentFanOutRepository;
import ai.proxyma.core.chat.repository.AgentNodeRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WORK OVER A LIST (2026-10-29, the Constellations samples' gap 3): one agent per item - an ECU, an
 * account, a contract, a repository - deployed as places free up instead of refused past the caps,
 * each reporting to the fan-out, and ONE report back when the last item is in, every item's result in
 * it. The agent that started it reads one message rather than forty, and its context stays clean;
 * readAgentReport gives any item's report in full.
 *
 * <p>Items run at most {@code maxParallel} at a time (and never past the parent's eight working
 * agents or the tree's thirty-two live ones - a place a capped deployment did not get is taken when
 * one frees up). Stopping the fan-out stops its running items and reports what was done. A
 * deliberation's rounds are fan-outs too (AgentDeliberations), whose report goes to the deliberation.
 */
@Service
public class AgentFanOuts {

    private static final Logger log = LoggerFactory.getLogger(AgentFanOuts.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    static final int DEFAULT_PARALLEL = 4;
    /** The most of one item's report the fan-out's report carries; the whole is a readAgentReport away. */
    static final int ITEM_REPORT_MAX = 2_000;

    /** One item: the title its agent is shown under, and its whole task. */
    public record Item(String title, String task) {}

    private final AgentFanOutRepository fanOuts;
    private final AgentNodeRepository nodes;
    private final AgentTree tree;
    private final AgentInbox inbox;
    private AgentEvents record;
    private ObjectProvider<AgentDeliberations> deliberations;
    private final ConcurrentHashMap<UUID, Object> locks = new ConcurrentHashMap<>();

    public AgentFanOuts(AgentFanOutRepository fanOuts, AgentNodeRepository nodes, AgentTree tree, AgentInbox inbox) {
        this.fanOuts = fanOuts;
        this.nodes = nodes;
        this.tree = tree;
        this.inbox = inbox;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setRecord(AgentEvents record) {
        this.record = record;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDeliberations(ObjectProvider<AgentDeliberations> deliberations) {
        this.deliberations = deliberations;
    }

    // -- Starting -----------------------------------------------------------------------------------

    /** Starts one agent per item under {@code parentConversationId}'s agent. */
    public Map<String, Object> start(String parentConversationId, String userId, String title, List<Item> items,
                                     Integer maxParallel, Double budgetUsdEach, Integer minutesEach, String model,
                                     boolean verify) {
        return start(parentConversationId, userId, title, items, maxParallel, budgetUsdEach, minutesEach, model, verify, null);
    }

    Map<String, Object> start(String parentConversationId, String userId, String title, List<Item> items,
                              Integer maxParallel, Double budgetUsdEach, Integer minutesEach, String model,
                              boolean verify, UUID deliberationId) {
        if (parentConversationId == null) return AgentTree.failure("Agents can only be deployed inside a conversation.");
        if (items == null || items.isEmpty()) return AgentTree.failure("items must name at least one item.");
        if (items.size() > AgentFanOut.ITEMS_MAX) {
            return AgentTree.failure("At most " + AgentFanOut.ITEMS_MAX + " items in one fan-out. Split the list.");
        }
        for (Item it : items) {
            if (it == null || it.task() == null || it.task().isBlank()) return AgentTree.failure("Every item needs a task.");
        }
        if (!tree.mayDeploy(parentConversationId)) {
            return AgentTree.failure("This agent is at the deepest level allowed (" + tree.maxDepth() + "), so it cannot "
                    + "deploy agents of its own. Do the work yourself, or report back to your parent.");
        }
        if (!tree.mayWake(parentConversationId)) return AgentTree.failure("A paused or stopped agent cannot deploy agents.");
        if (budgetUsdEach != null && (!(budgetUsdEach > 0) || budgetUsdEach > AgentTree.BUDGET_MAX)) {
            return AgentTree.failure("budgetUsdEach is more than 0 and at most " + (int) AgentTree.BUDGET_MAX + ", or left out.");
        }
        if (minutesEach != null && (minutesEach < 1 || minutesEach > AgentTree.MINUTES_MAX)) {
            return AgentTree.failure("minutesEach is from 1 to " + AgentTree.MINUTES_MAX + ", or left out.");
        }
        AgentFanOut f = new AgentFanOut();
        f.setParentConversationId(parentConversationId);
        f.setRootConversationId(tree.rootOf(parentConversationId));
        f.setUserId(userId);
        f.setTitle(clipTitle(title == null || title.isBlank() ? items.size() + " items" : title.strip()));
        try {
            f.setItems(JSON.writeValueAsString(items));
        } catch (Exception e) {
            return AgentTree.failure("The items could not be kept.");
        }
        f.setTotal(items.size());
        f.setMaxParallel(Math.max(1, Math.min(AgentTree.RUNNING_PER_PARENT, maxParallel == null ? DEFAULT_PARALLEL : maxParallel)));
        f.setBudgetUsdEach(budgetUsdEach);
        f.setMinutesEach(minutesEach);
        f.setModel(model == null || model.isBlank() ? null : model.strip());
        f.setVerify(verify);
        f.setDeliberationId(deliberationId);
        AgentFanOut saved = fanOuts.save(f);
        if (record != null) {
            record.write(saved.getRootConversationId(), parentConversationId,
                    tree.node(parentConversationId).map(AgentNode::getId).orElse(null), AgentEvent.FAN_OUT, AgentFanOut.RUNNING,
                    null, saved.getTitle() + " (" + saved.getTotal() + " items)");
        }
        log.info("[Agents] fan-out {} of {} items under conversation={}", saved.getId(), saved.getTotal(), parentConversationId);
        pump(saved.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("fanOutId", saved.getId().toString());
        out.put("total", saved.getTotal());
        out.put("note", "Its " + saved.getTotal() + " agents work at most " + saved.getMaxParallel() + " at a time. One "
                + "report with every item's result arrives as a message beginning \"[Report from " + saved.getTitle()
                + "\". Carry on or end your turn; awaitAgents with the fanOutId waits for it, and controlAgent stops it.");
        return out;
    }

    // -- Running ------------------------------------------------------------------------------------

    /** Deploys items while there is room, and finishes the fan-out once every item has ended. */
    void pump(UUID id) {
        synchronized (locks.computeIfAbsent(id, k -> new Object())) {
            AgentFanOut f = fanOuts.findById(id).orElse(null);
            if (f == null || !AgentFanOut.RUNNING.equals(f.getStatus())) return;
            List<AgentNode> mine = nodes.findByFanOutIdOrderByItemIndexAsc(id);
            long working = mine.stream().filter(n -> !n.isEnded() && n.isReportOwed()).count();
            List<Item> items = items(f);
            while (f.getNextIndex() < f.getTotal() && working < f.getMaxParallel()) {
                int index = f.getNextIndex();
                Item it = items.get(index);
                Map<String, Object> r = tree.deploy(f.getParentConversationId(), f.getUserId(),
                        new AgentTree.Deploy(it.title(), it.task(), AgentNode.SHORT, f.getProviderId(), f.getModel(),
                                f.getBudgetUsdEach(), f.getMinutesEach(), f.isVerify()),
                        new AgentTree.Placement(f.getId(), index, null, null));
                if (!Boolean.TRUE.equals(r.get("success"))) {
                    String error = String.valueOf(r.get("error"));
                    // No room now: a place that frees up is handed back here (slotFreed).
                    if (error.startsWith("At most") || error.contains("live agents")) break;
                    fanOuts.save(f);
                    stopWith(f, "Item " + (index + 1) + " (" + it.title() + ") could not start: " + error);
                    return;
                }
                f.setNextIndex(index + 1);
                working++;
            }
            fanOuts.save(f);
            if (f.getNextIndex() >= f.getTotal() && working == 0) finish(f, false, null);
        }
    }

    /** One item's agent has reported, or ended without reporting: its place goes to the next item. */
    void itemEnded(AgentNode n) {
        if (n.getFanOutId() == null) return;
        try {
            pump(n.getFanOutId());
        } catch (RuntimeException e) {
            log.warn("[Agents] fan-out {} could not go on after an item ended: {}", n.getFanOutId(), e.getMessage());
        }
    }

    /** One of this conversation's agents has ended: its fan-outs waiting for a place may take it. */
    void slotFreed(String parentConversationId) {
        for (AgentFanOut f : fanOuts.findByParentConversationIdOrderByCreatedAtAsc(parentConversationId)) {
            if (AgentFanOut.RUNNING.equals(f.getStatus()) && f.getNextIndex() < f.getTotal()) pump(f.getId());
        }
    }

    // The last item is in (or the fan-out was stopped): one report goes where the fan-out reports.
    private void finish(AgentFanOut f, boolean stopped, String why) {
        f.setStatus(stopped ? AgentFanOut.STOPPED : AgentFanOut.DONE);
        f.setEndedAt(Instant.now());
        fanOuts.save(f);
        List<AgentNode> mine = nodes.findByFanOutIdOrderByItemIndexAsc(f.getId());
        if (record != null) {
            record.write(f.getRootConversationId(), f.getParentConversationId(),
                    tree.node(f.getParentConversationId()).map(AgentNode::getId).orElse(null), AgentEvent.FAN_OUT,
                    f.getStatus(), null, f.getTitle() + (stopped ? " stopped" : " done"));
        }
        AgentDeliberations d = f.getDeliberationId() == null || deliberations == null ? null : deliberations.getIfAvailable();
        if (d != null) {
            d.roundDone(f, mine, stopped, why);
            return;
        }
        inbox.post(f.getParentConversationId(), AgentInbox.Kind.REPORT, f.getId(), f.getTitle(), report(f, mine, stopped, why));
    }

    /** The fan-out's one report: how many items went how, then each item's report, clipped. */
    static String report(AgentFanOut f, List<AgentNode> items, boolean stopped, String why) {
        long done = items.stream().filter(n -> AgentNode.DONE.equals(n.getStatus())).count();
        long failed = items.stream().filter(n -> AgentNode.FAILED.equals(n.getStatus())).count();
        long halted = items.stream().filter(n -> AgentNode.STOPPED.equals(n.getStatus())).count();
        StringBuilder out = new StringBuilder();
        out.append("Fan-out \"").append(f.getTitle()).append("\" ").append(stopped ? "was stopped" : "is done").append(": ")
                .append(done).append(" of ").append(f.getTotal()).append(" items reported");
        if (failed > 0) out.append(", ").append(failed).append(" failed");
        if (halted > 0) out.append(", ").append(halted).append(" stopped");
        if (f.getNextIndex() < f.getTotal()) out.append(", ").append(f.getTotal() - f.getNextIndex()).append(" never started");
        out.append('.');
        if (why != null) out.append(' ').append(why);
        out.append(" Each item's report is below, cut to ").append(ITEM_REPORT_MAX)
                .append(" characters; readAgentReport with its agentId gives one in full.\n");
        int per = Math.max(300, Math.min(ITEM_REPORT_MAX, (AgentTree.REPORT_MAX - 2_000) / Math.max(1, items.size())));
        for (AgentNode n : items) {
            out.append("\n## ").append(n.getItemIndex() == null ? "?" : n.getItemIndex() + 1).append(". ").append(n.getTitle())
                    .append(" - ").append(n.getStatus()).append(" (agentId ").append(n.getId()).append(")\n");
            String r = n.getLastReport();
            out.append(r == null || r.isBlank() ? "(no report)" : AgentInbox.clip(r.strip(), per)).append('\n');
        }
        return AgentInbox.clip(out.toString(), AgentTree.REPORT_MAX);
    }

    // -- Control ------------------------------------------------------------------------------------

    /** Stops a fan-out the caller started: its running items stop, and what was done is reported. */
    Map<String, Object> stop(String conversationId, String rawId) {
        UUID id = uuid(rawId);
        AgentFanOut f = id == null ? null : fanOuts.findById(id).orElse(null);
        if (f == null || !f.getParentConversationId().equals(conversationId)) {
            AgentDeliberations d = deliberations == null ? null : deliberations.getIfAvailable();
            if (d != null && id != null) return d.stop(conversationId, id);
            return AgentTree.failure("No fan-out of yours with id " + rawId + ".");
        }
        if (!AgentFanOut.RUNNING.equals(f.getStatus())) return AgentTree.failure("That fan-out has already ended.");
        stopWith(f, "Stopped by the agent that started it.");
        return Map.of("success", true, "status", AgentFanOut.STOPPED);
    }

    /** Stops a deliberation's running round, if it has one; false when it has none. */
    boolean stopRound(UUID deliberationId, String why) {
        for (AgentFanOut f : fanOuts.findByStatus(AgentFanOut.RUNNING)) {
            if (deliberationId.equals(f.getDeliberationId())) {
                stopWith(f, why);
                return true;
            }
        }
        return false;
    }

    private void stopWith(AgentFanOut f, String why) {
        f.setStatus(AgentFanOut.STOPPED);
        fanOuts.save(f);
        for (AgentNode n : nodes.findByFanOutIdOrderByItemIndexAsc(f.getId())) {
            if (!n.isEnded()) tree.control(f.getParentConversationId(), n.getId().toString(), "stop");
        }
        finish(f, true, why);
    }

    // -- Reading ------------------------------------------------------------------------------------

    /** The fan-outs and deliberations of this conversation still running, by id - what "await all" waits for. */
    Set<UUID> runningOf(String parentConversationId) {
        Set<UUID> out = new LinkedHashSet<>();
        for (AgentFanOut f : fanOuts.findByParentConversationIdOrderByCreatedAtAsc(parentConversationId)) {
            if (AgentFanOut.RUNNING.equals(f.getStatus()) && f.getDeliberationId() == null) out.add(f.getId());
        }
        AgentDeliberations d = deliberations == null ? null : deliberations.getIfAvailable();
        if (d != null) out.addAll(d.runningOf(parentConversationId));
        return out;
    }

    /** The id of a fan-out or deliberation this conversation started, or null. */
    UUID groupOf(String parentConversationId, String rawId) {
        UUID id = uuid(rawId);
        if (id == null) return null;
        Optional<AgentFanOut> f = fanOuts.findById(id);
        if (f.isPresent()) return f.get().getParentConversationId().equals(parentConversationId) ? id : null;
        AgentDeliberations d = deliberations == null ? null : deliberations.getIfAvailable();
        return d != null && d.isOf(parentConversationId, id) ? id : null;
    }

    /** Whether a fan-out or deliberation is still working. */
    boolean isRunning(UUID id) {
        Optional<AgentFanOut> f = fanOuts.findById(id);
        if (f.isPresent()) return AgentFanOut.RUNNING.equals(f.get().getStatus());
        AgentDeliberations d = deliberations == null ? null : deliberations.getIfAvailable();
        return d != null && d.isRunning(id);
    }

    /** This conversation's fan-outs, for listing its agents. */
    List<Map<String, Object>> views(String parentConversationId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentFanOut f : fanOuts.findByParentConversationIdOrderByCreatedAtAsc(parentConversationId)) {
            if (f.getDeliberationId() != null) continue;
            long ended = nodes.findByFanOutIdOrderByItemIndexAsc(f.getId()).stream().filter(AgentNode::isEnded).count();
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("fanOutId", f.getId().toString());
            v.put("title", f.getTitle());
            v.put("status", f.getStatus());
            v.put("total", f.getTotal());
            v.put("started", f.getNextIndex());
            v.put("ended", ended);
            out.add(v);
        }
        return out;
    }

    @EventListener
    public void onConversationsDeleted(ConversationsDeletedEvent event) {
        if (event.conversationIds().isEmpty()) return;
        try {
            fanOuts.deleteByRootConversationIdIn(event.conversationIds());
            fanOuts.deleteByParentConversationIdIn(event.conversationIds());
        } catch (RuntimeException e) {
            log.warn("[Agents] could not remove the fan-outs of deleted conversations: {}", e.getMessage());
        }
    }

    // -- Helpers ------------------------------------------------------------------------------------

    static List<Item> items(AgentFanOut f) {
        try {
            return JSON.readValue(f.getItems(), new TypeReference<List<Item>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    static String clipTitle(String t) {
        String one = t.replaceAll("\\s+", " ").strip();
        return one.length() <= 80 ? one : one.substring(0, 77) + "...";
    }

    static UUID uuid(String raw) {
        if (raw == null) return null;
        try {
            return UUID.fromString(raw.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
