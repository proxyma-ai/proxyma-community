package ai.proxyma.core.chat.agents;

import ai.proxyma.core.chat.module.AgentProfile;
import ai.proxyma.core.chat.module.AgentProfiles;
import ai.proxyma.core.chat.module.ChatModule;
import ai.proxyma.core.chat.model.AgentNode;
import ai.proxyma.core.settings.dto.SubAgentSettings;
import ai.proxyma.core.settings.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The agent tree as the chat sees it: a CHILD conversation runs under its tree's root profile - the
 * narrowing a saved agent of Constellations has, or none for a conversation the user opened - so a
 * child can never do more than the agent that deployed it. On top of that it may run on a model of
 * its own (given at deployAgent, else Settings > Agents' child model), and at the deepest level it
 * is not given deployAgent. Its system prompt says which agent it is and for whom it works.
 *
 * <p>First of the modules, so AgentProfiles asks it before the others; it asks the others itself,
 * about the ROOT conversation, and answers nothing for a conversation that is not a child.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AgentTreeModule implements ChatModule {

    private static final Logger log = LoggerFactory.getLogger(AgentTreeModule.class);

    /** The tool that deploys an agent, withheld at the deepest level. */
    public static final String DEPLOY = "deployAgent";

    private final AgentTree tree;
    private final ObjectProvider<ChatModule> modules;
    private final SettingsService settings;

    public AgentTreeModule(AgentTree tree, ObjectProvider<ChatModule> modules, SettingsService settings) {
        this.tree = tree;
        this.modules = modules;
        this.settings = settings;
    }

    @Override public List<Object> toolObjects() { return List.of(); }
    @Override public Map<String, Set<String>> skillToolGroups() { return Map.of(); }
    @Override public Set<String> ownToolNames() { return Set.of(); }
    @Override public boolean offersToolsIn(String conversationId, String userId) { return false; }
    @Override public boolean licensed() { return true; }

    @Override
    public Optional<AgentProfile> profileFor(String conversationId, String userId) {
        Optional<AgentNode> found = tree.node(conversationId);
        if (found.isEmpty()) return Optional.empty();
        AgentNode n = found.get();
        if (n.getUserId() != null && !n.getUserId().equals(userId)) return Optional.of(AgentProfile.LOCKED);
        List<ChatModule> others = modules.orderedStream().filter(m -> m != this).toList();
        Optional<AgentProfile> rootProfile = AgentProfiles.of(others, n.getRootConversationId(), userId);
        AgentProfile root = rootProfile.orElse(null);
        if (root != null && root.locked()) return Optional.of(AgentProfile.LOCKED);

        Set<String> withheld = new HashSet<>();
        if (root != null && root.withheldTools() != null) withheld.addAll(root.withheldTools());
        if (n.getDepth() >= tree.maxDepth()) withheld.add(DEPLOY);

        String provider = n.getProviderId();
        String model = n.getModel();
        if (model == null && root != null && root.ownModel()) {
            provider = root.providerId();
            model = root.model();
        }
        if (model == null) {
            SubAgentSettings child = childSettings();
            if (child.getModel() != null && !child.getModel().isBlank()) {
                provider = child.getProvider() == null || child.getProvider().isBlank() ? null : child.getProvider();
                model = child.getModel();
            }
        }
        // Its step limit: the root's (a saved agent's), else Settings > Agents' for deployed agents
        // when set there, else the chat's own.
        Integer steps = root != null && root.maxSteps() != null ? root.maxSteps() : childSettings().getMaxIterations();
        return Optional.of(new AgentProfile(provider, model, steps,
                withheld.isEmpty() ? null : withheld, root == null ? null : root.sourceIds(),
                root == null ? null : root.memoryKey(), root == null ? null : root.withheldSkills()));
    }

    /**
     * A deployed agent stops at its own budget or deadline, or an ancestor's (AgentTree.checkLimits) -
     * and at its ROOT's, as another module states it (a saved agent's run): then its whole tree stops.
     */
    @Override
    public Optional<String> stopReason(String conversationId) {
        Optional<AgentNode> found = tree.node(conversationId);
        if (found.isEmpty()) return Optional.empty();
        Optional<String> own = tree.checkLimits(conversationId);
        if (own.isPresent()) return own;
        String root = found.get().getRootConversationId();
        for (ChatModule m : modules.orderedStream().filter(m -> m != this).toList()) {
            Optional<String> reason;
            try {
                reason = m.stopReason(root);
            } catch (RuntimeException e) {
                continue;
            }
            if (reason != null && reason.isPresent()) {
                tree.stopTree(root, reason.get());
                return reason;
            }
        }
        return Optional.empty();
    }

    /** A deployed agent is watched exactly as its tree's root is: an agent of a scheduled run is not. */
    @Override
    public boolean parksApprovals(String conversationId) {
        Optional<AgentNode> found = tree.node(conversationId);
        if (found.isEmpty()) return false;
        String root = found.get().getRootConversationId();
        return modules.orderedStream().filter(m -> m != this).anyMatch(m -> {
            try {
                return m.parksApprovals(root);
            } catch (RuntimeException e) {
                return false;
            }
        });
    }

    @Override
    public Optional<String> systemContext(String conversationId, String userId) {
        try {
            return tree.node(conversationId).map(n -> identity(n, tree.maxDepth()));
        } catch (RuntimeException e) {
            log.warn("[Agents] could not describe agent conversation {}: {}", conversationId, e.getMessage());
            return Optional.empty();
        }
    }

    // Identity only, and nothing that moves between turns (ChatModule.systemContext).
    static String identity(AgentNode n, int maxDepth) {
        StringBuilder s = new StringBuilder();
        s.append("YOU ARE AN AGENT ANOTHER AGENT DEPLOYED, to work on: ").append(n.getTitle()).append(".\n");
        s.append("- You work on your own, in a conversation of your own. Your task came from your parent agent; ")
                .append("the final answer of each of your turns is your REPORT to it, so end every turn with what ")
                .append("you found or did, complete enough to act on without asking you.\n");
        s.append("- Talk to your parent mid-task with tellAgent (to \"parent\"): a question it must answer, or ")
                .append("progress it should know now. Its answers arrive as messages to you.\n");
        if (n.getDepth() < maxDepth) {
            s.append("- You may deploy agents of your own for parts of the work (deployAgent); you are at level ")
                    .append(n.getDepth()).append(" of ").append(maxDepth).append(".\n");
        } else {
            s.append("- You are at the deepest level allowed (").append(maxDepth).append("): you cannot deploy agents.\n");
        }
        s.append(n.isLong()
                ? "- You are a LONG-LIVED agent: after a report you stay, and your parent may give you more work.\n"
                : "- You are a SHORT-LIVED agent: your report on this task is your last, unless you are given more.\n");
        s.append("- The user may also talk to you directly. Answer them; your parent is told what was said.");
        return s.toString();
    }

    private SubAgentSettings childSettings() {
        try {
            SubAgentSettings s = settings.getSettings(SubAgentSettings.PREFIX, SubAgentSettings.class);
            return s == null ? new SubAgentSettings() : s;
        } catch (RuntimeException e) {
            return new SubAgentSettings();
        }
    }
}
