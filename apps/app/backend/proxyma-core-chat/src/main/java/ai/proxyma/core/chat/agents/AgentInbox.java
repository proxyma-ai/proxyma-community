package ai.proxyma.core.chat.agents;

import ai.proxyma.core.chat.model.AgentInboxEntry;
import ai.proxyma.core.chat.repository.AgentInboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * WHAT REACHES AN AGENT THAT NO PERSON TYPED - one inbox per conversation, for every kind of it:
 * a child's report, a message from its parent, a child or a peer, an alarm going off, a background
 * job's result, and what the user said to one of its children.
 *
 * <p>Posting is all a sender does. The conversation's running turn takes what is waiting at its next
 * step (ChatService.takeReports); with no turn running, one is started for it - a
 * {@link AgentInboxPostedEvent} that ChatService answers with a wake. So every agent works the way a
 * person does with a mailbox: nobody waits on anybody's call stack, and a message to an agent that
 * is busy is read when it next looks up.
 *
 * <p>Persisted, so a restart loses nothing; the set of conversations with something waiting is kept
 * in memory as well, because whether anything is owed is asked before every step of every turn.
 */
@Service
public class AgentInbox {

    private static final Logger log = LoggerFactory.getLogger(AgentInbox.class);

    /** What an entry is, and how the agent is told about it. */
    public enum Kind {
        REPORT("Report from"),
        MESSAGE("Message from"),
        ALARM("Alarm"),
        BACKGROUND("Background result"),
        USER_TALK("The user talked to"),
        /** A person's decision on a write kept in the approvals inbox (ApprovalInbox). */
        APPROVAL("Approval"),
        /**
         * How full the agent's context window is, once, before the conversation is condensed - so
         * it can save what must outlast the summary (ChatService.contextNote). Written into the
         * conversation at the next step, never posted: it wakes nobody.
         */
        CONTEXT("Context"),
        /**
         * What a product module keeps for this agent between conversations - a saved agent's
         * workspace memory (ChatModule.memoryContext). Written into the conversation at the step
         * that has no copy in view, never posted: it wakes nobody.
         */
        MEMORY("Workspace memory");

        private final String heading;

        Kind(String heading) {
            this.heading = heading;
        }

        public String heading() {
            return heading;
        }
    }

    /** One entry as it was taken, for a caller that needs more than the text (awaitAgents). */
    public record Taken(UUID id, Kind kind, UUID fromAgentId, String fromTitle, String text, Instant createdAt) {}

    private final AgentInboxRepository entries;
    private final ApplicationEventPublisher events;
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    // A waiter per conversation for awaitAgents: notified on every post there.
    private final ConcurrentHashMap<String, Object> monitors = new ConcurrentHashMap<>();

    public AgentInbox(AgentInboxRepository entries, ApplicationEventPublisher events) {
        this.entries = entries;
        this.events = events;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadPending() {
        try {
            pending.addAll(entries.conversationsWithPending());
            if (!pending.isEmpty()) {
                log.info("[Agents] {} conversation(s) have something waiting from before the restart", pending.size());
                for (String conversationId : List.copyOf(pending)) events.publishEvent(new AgentInboxPostedEvent(conversationId));
            }
        } catch (RuntimeException e) {
            log.warn("[Agents] could not read what was waiting before the restart: {}", e.getMessage());
        }
    }

    /** Leaves something for a conversation's agent, and starts a turn for it if none is running. */
    public UUID post(String conversationId, Kind kind, UUID fromAgentId, String fromTitle, String text) {
        AgentInboxEntry e = new AgentInboxEntry();
        e.setConversationId(conversationId);
        e.setKind(kind.name());
        e.setFromAgentId(fromAgentId);
        e.setFromTitle(fromTitle == null ? null : clip(fromTitle, 200));
        e.setText(clip(text == null ? "" : text, AgentInboxEntry.TEXT_MAX));
        AgentInboxEntry saved = entries.save(e);
        pending.add(conversationId);
        Object monitor = monitors.get(conversationId);
        if (monitor != null) {
            synchronized (monitor) {
                monitor.notifyAll();
            }
        }
        events.publishEvent(new AgentInboxPostedEvent(conversationId));
        return saved.getId();
    }

    /** Wakes every wait on this conversation so it looks again (AgentTree.abandonWaits). */
    public void rouse(String conversationId) {
        Object monitor = monitors.get(conversationId);
        if (monitor == null) return;
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }

    /** Whether anything is waiting for this conversation's agent. Cheap: a set lookup. */
    public boolean hasPending(String conversationId) {
        return conversationId != null && pending.contains(conversationId);
    }

    /**
     * Takes everything waiting, once, as the text the agent reads as its next message - or null.
     * Marked delivered as it is taken: a second taker finds nothing.
     */
    public String take(String conversationId) {
        List<Taken> taken = takeWhere(conversationId, t -> true);
        return taken.isEmpty() ? null : render(taken);
    }

    /** Takes only the entries {@code which} accepts, leaving the rest for the agent's next step. */
    public List<Taken> takeWhere(String conversationId, Predicate<Taken> which) {
        if (!hasPending(conversationId)) return List.of();
        List<AgentInboxEntry> rows = entries.findByConversationIdAndDeliveredAtIsNullOrderByCreatedAtAsc(conversationId);
        List<Taken> out = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        for (AgentInboxEntry r : rows) {
            Taken t = new Taken(r.getId(), Kind.valueOf(r.getKind()), r.getFromAgentId(), r.getFromTitle(), r.getText(),
                    r.getCreatedAt());
            if (which.test(t)) {
                out.add(t);
                ids.add(r.getId());
            }
        }
        if (!ids.isEmpty()) {
            int marked = entries.markDelivered(ids, Instant.now());
            // Somebody else took some of them in between: keep only what this call marked.
            if (marked < ids.size()) {
                Set<UUID> still = ConcurrentHashMap.newKeySet();
                entries.findByConversationIdAndDeliveredAtIsNullOrderByCreatedAtAsc(conversationId)
                        .forEach(r -> still.add(r.getId()));
                out.removeIf(t -> still.contains(t.id()));
            }
        }
        if (rows.size() == ids.size()) pending.remove(conversationId);
        // A post between the read and the removal is still pending: look once more.
        if (!pending.contains(conversationId)
                && !entries.findByConversationIdAndDeliveredAtIsNullOrderByCreatedAtAsc(conversationId).isEmpty()) {
            pending.add(conversationId);
        }
        return out;
    }

    /**
     * Waits until {@code which} accepts something waiting for this conversation, or the time runs
     * out, and takes it - what awaitAgents is made of. Never holds a lock while it waits.
     */
    public List<Taken> await(String conversationId, Predicate<Taken> which, Duration timeout,
                             java.util.function.BooleanSupplier done) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        Object monitor = monitors.computeIfAbsent(conversationId, k -> new Object());
        while (true) {
            List<Taken> got = takeWhere(conversationId, which);
            if (!got.isEmpty() || done.getAsBoolean()) return got;
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) return List.of();
            synchronized (monitor) {
                try {
                    monitor.wait(Math.min(left, 2_000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return List.of();
                }
            }
        }
    }

    /** Everything a deleted conversation had waiting goes with it. */
    public void forget(java.util.Collection<String> conversationIds) {
        if (conversationIds == null || conversationIds.isEmpty()) return;
        entries.deleteByConversationIdIn(conversationIds);
        conversationIds.forEach(pending::remove);
    }

    /** The text the agent reads: each entry under a heading saying what it is and whom it is from. */
    public static String render(List<Taken> taken) {
        StringBuilder out = new StringBuilder();
        for (Taken t : taken) {
            if (!out.isEmpty()) out.append("\n\n");
            out.append('[').append(t.kind().heading());
            if (t.fromTitle() != null && !t.fromTitle().isBlank()) out.append(' ').append(t.fromTitle());
            if (t.fromAgentId() != null) out.append(" (agent ").append(t.fromAgentId()).append(')');
            out.append("]\n").append(t.text());
        }
        return out.toString();
    }

    /** Whether a message the agent is reading opens with something from its inbox. */
    public static boolean isInboxMessage(String text) {
        if (text == null) return false;
        for (Kind k : Kind.values()) if (text.startsWith("[" + k.heading())) return true;
        return false;
    }

    private static final java.util.regex.Pattern HEADING = java.util.regex.Pattern.compile(
            "(?m)^\\[(Report from|Message from|Alarm|Background result|The user talked to|Approval|Context|Workspace memory)(?: (.*?))?"
                    + "(?: \\(agent ([0-9a-fA-F-]{36})\\))?\\]$");

    /** What a stored inbox message carries, in order, as {agentId, title, kind} - for the transcript. */
    public static List<String[]> entriesIn(String content) {
        List<String[]> out = new ArrayList<>();
        if (content == null) return out;
        java.util.regex.Matcher m = HEADING.matcher(content);
        while (m.find()) {
            String kind = switch (m.group(1)) {
                case "Report from" -> "report";
                case "Message from" -> "message";
                case "Alarm" -> "alarm";
                case "Background result" -> "background";
                case "Approval" -> "approval";
                case "Context" -> "context";
                case "Workspace memory" -> "memory";
                default -> "user_talk";
            };
            out.add(new String[] {m.group(3), m.group(2), kind});
        }
        return out;
    }

    static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 20) + "\n[... cut short]";
    }

    /** The newest entry of one kind from one agent that is still waiting, if any - for tests and the panel. */
    Optional<AgentInboxEntry> peek(String conversationId, Kind kind, UUID fromAgentId) {
        return entries.findByConversationIdAndDeliveredAtIsNullOrderByCreatedAtAsc(conversationId).stream()
                .filter(e -> kind.name().equals(e.getKind()) && java.util.Objects.equals(fromAgentId, e.getFromAgentId()))
                .reduce((a, b) -> b);
    }
}
