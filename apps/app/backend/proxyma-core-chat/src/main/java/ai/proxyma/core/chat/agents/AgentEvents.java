package ai.proxyma.core.chat.agents;

import ai.proxyma.core.chat.event.ConversationsDeletedEvent;
import ai.proxyma.core.chat.model.AgentEvent;
import ai.proxyma.core.chat.model.AgentNode;
import ai.proxyma.core.chat.repository.AgentEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * THE TREE'S RECORD (2026-10-29, the Constellations samples' gap 6): what happened in an agent tree
 * and when - each agent deployed, each status it moved through, each report and message between two
 * agents, each write kept for a person and its decision, a budget or deadline stopping a subtree, a
 * verifier's scorecard, a file handed on. A timeline is drawn from it, and a replay steps through it.
 *
 * <p>Recording never fails what it records: a row that cannot be written is logged and dropped. Rows
 * go with their root conversation.
 */
@Service
public class AgentEvents {

    private static final Logger log = LoggerFactory.getLogger(AgentEvents.class);

    /** The most a timeline reads at once. */
    public static final int READ_MAX = 5_000;

    private final AgentEventRepository events;

    public AgentEvents(AgentEventRepository events) {
        this.events = events;
    }

    /** Something about a deployed agent (its node), going to {@code to} when it went to another agent. */
    public void record(AgentNode about, String kind, String status, UUID to, String detail) {
        if (about == null) return;
        write(about.getRootConversationId(), about.getConversationId(), about.getId(), kind, status, to, detail);
    }

    /** Something about the agent the user opened - the root - in its own conversation. */
    public void recordRoot(String rootConversationId, String kind, UUID to, String detail) {
        write(rootConversationId, rootConversationId, null, kind, null, to, detail);
    }

    public void write(String root, String conversationId, UUID agentId, String kind, String status, UUID to, String detail) {
        if (root == null || conversationId == null || kind == null) return;
        try {
            AgentEvent e = new AgentEvent();
            e.setRootConversationId(root);
            e.setConversationId(conversationId);
            e.setAgentId(agentId);
            e.setToAgentId(to);
            e.setKind(kind);
            e.setStatus(status);
            e.setDetail(clip(detail));
            e.setAt(Instant.now());
            events.save(e);
        } catch (RuntimeException ex) {
            log.debug("[Agents] could not record a {} event for {}: {}", kind, conversationId, ex.getMessage());
        }
    }

    /** What happened in these trees from {@code from} on, oldest first, at most {@link #READ_MAX}. */
    public List<AgentEvent> of(Collection<String> roots, Instant from) {
        if (roots == null || roots.isEmpty()) return List.of();
        return events.findByRootConversationIdInAndAtGreaterThanEqualOrderByAtAsc(roots,
                from == null ? Instant.EPOCH : from, PageRequest.of(0, READ_MAX));
    }

    @EventListener
    public void onConversationsDeleted(ConversationsDeletedEvent event) {
        if (event.conversationIds().isEmpty()) return;
        try {
            events.deleteByRootConversationIdIn(event.conversationIds());
            events.deleteByConversationIdIn(event.conversationIds());
        } catch (RuntimeException e) {
            log.warn("[Agents] could not remove the events of deleted conversations: {}", e.getMessage());
        }
    }

    static String clip(String detail) {
        if (detail == null) return null;
        String one = detail.replaceAll("\\s+", " ").strip();
        return one.length() <= AgentEvent.DETAIL_MAX ? one : one.substring(0, AgentEvent.DETAIL_MAX - 3) + "...";
    }
}
