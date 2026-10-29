package ai.proxyma.core.chat.service;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * The tools a skill group unlocks, read from the gate's own table (ToolSkillGating) for code outside
 * this package that names groups - a saved agent's tool sets (Constellations) withhold whole groups.
 * Read from here rather than restated, so a tool added to a group is covered wherever the group is
 * named, as DefaultChatSkillSeeder reads it for the same reason.
 */
@Component
public class ToolGroups {

    private final ToolSkillGating gating;

    ToolGroups(ToolSkillGating gating) {
        this.gating = gating;
    }

    /**
     * The product's own skill groups by name, in order - Jira, Web, Office Files... - and never a
     * product module's, whose skill is offered only where the module says.
     */
    public java.util.SortedSet<String> productGroups() {
        return gating.productGroupNames();
    }

    /** The tools of this group, or none for a group the gate does not know. */
    public Set<String> toolsOf(String group) {
        return gating.toolNamesForGroup(group);
    }
}
