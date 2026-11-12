package ai.proxyma.core.chat.agents;

import ai.proxyma.core.chat.model.AgentNode;
import ai.proxyma.core.chat.module.ChatModule;
import ai.proxyma.core.settings.usage.LlmCallLabels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The chat's answer to "which agent were these calls for" (LlmCallLabels), for the per-call log.
 *
 * <p>A conversation a product module holds for an agent is named by that module ({@link
 * ChatModule#callLabels} - Constellations answers "Project / Agent" and how the run started). An agent
 * another agent DEPLOYED is named by its title, after its root's label when the root has one, and
 * takes the root's trigger - so the agents a scheduled run deployed read as that run. Anything else -
 * an ordinary chat - has no label.
 *
 * <p>Bounded: one batch to each module, one query for the deployed agents among the conversations and
 * one more batch to the modules for their roots. At most {@link #MAX} conversations a call; the
 * controller asks per page or download, which is fewer. Never throws - a label is decoration, and a
 * failure leaves the calls unlabelled rather than the log unread.
 */
@Component
public class ConversationCallLabels implements LlmCallLabels {

    private static final Logger log = LoggerFactory.getLogger(ConversationCallLabels.class);

    /** The most conversations labelled in one call; the rest stay unlabelled. */
    static final int MAX = 2_000;
    /** How a deployed agent's title follows its root's label. */
    static final String CHILD_SEPARATOR = " > ";
    /** The longest title shown for a deployed agent. */
    static final int TITLE_SHOWN = 120;

    private final AgentTree tree;
    private final ObjectProvider<ChatModule> modules;

    public ConversationCallLabels(AgentTree tree, ObjectProvider<ChatModule> modules) {
        this.tree = tree;
        this.modules = modules;
    }

    @Override
    public Map<String, Label> labels(Collection<String> conversationIds) {
        if (conversationIds == null || conversationIds.isEmpty()) return Map.of();
        Set<String> ids = new LinkedHashSet<>();
        for (String id : conversationIds) {
            if (id != null && !id.isBlank() && ids.size() < MAX) ids.add(id);
        }
        if (ids.isEmpty()) return Map.of();
        Map<String, Label> out = new HashMap<>(fromModules(ids));
        Set<String> rest = new LinkedHashSet<>(ids);
        rest.removeAll(out.keySet());
        if (rest.isEmpty()) return out;

        Map<String, AgentNode> children;
        try {
            children = tree.nodesOf(rest);
        } catch (RuntimeException e) {
            log.debug("[CallLabels] could not read the deployed agents: {}", e.getMessage());
            return out;
        }
        if (children.isEmpty()) return out;
        Set<String> roots = new LinkedHashSet<>();
        for (AgentNode n : children.values()) {
            if (n.getRootConversationId() != null) roots.add(n.getRootConversationId());
        }
        Map<String, Label> rootLabels = roots.isEmpty() ? Map.of() : fromModules(roots);
        children.forEach((id, n) -> {
            String title = title(n.getTitle());
            Label root = n.getRootConversationId() == null ? null : rootLabels.get(n.getRootConversationId());
            if (root == null) {
                out.put(id, new Label(title, null));
            } else {
                out.put(id, new Label(root.agent() + CHILD_SEPARATOR + title, root.trigger()));
            }
        });
        return out;
    }

    // The first module to name a conversation names it.
    private Map<String, Label> fromModules(Set<String> ids) {
        Map<String, Label> out = new HashMap<>();
        List<ChatModule> all;
        try {
            all = modules.orderedStream().toList();
        } catch (RuntimeException e) {
            return out;
        }
        for (ChatModule m : all) {
            try {
                Map<String, Label> named = m.callLabels(ids);
                if (named == null) continue;
                named.forEach((id, label) -> {
                    if (id != null && ids.contains(id) && label != null && label.agent() != null) out.putIfAbsent(id, label);
                });
            } catch (RuntimeException e) {
                log.debug("[CallLabels] a module could not name its conversations: {}", e.getMessage());
            }
        }
        return out;
    }

    private static String title(String title) {
        String t = Objects.requireNonNullElse(title, "").strip();
        if (t.isEmpty()) return "Agent";
        return t.length() <= TITLE_SHOWN ? t : t.substring(0, TITLE_SHOWN);
    }
}
