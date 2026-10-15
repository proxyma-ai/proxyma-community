package ai.proxyma.core.chat.module;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What a product MODULE built into this deployable adds to the chat agent: its own tools, the skill
 * that unlocks them, when they may be offered, and a line of identity for the conversations it owns.
 * The first implementation is the key-account panel (proxyma-core-keyaccount, spec 11 section 8),
 * which is built in the server profile only - so chat, which every edition carries, cannot name it
 * and asks this port instead. An edition without such a module has no bean of this type, and
 * every caller treats that as "nothing to add".
 *
 * <p><b>THIS IS NOT A TENANT OVERRIDE, and must not become one.</b> CLAUDE.md forbids reintroducing
 * the tenant mechanism (ExtraMcpTool, ToolSkillGroupContributor, a deployment re-skinning the
 * product). That mechanism let something OUTSIDE the product change what the product is. This port
 * is internal plumbing between two modules of this repository, both shipped and reviewed together:
 * a feature of the product (one with its own page) contributing the agent half of that feature.
 * Its contract is deliberately narrow - it cannot rename the product, replace the system prompt,
 * re-gate another module's tools or reach a conversation it does not own - and nothing outside this
 * repository implements it.
 *
 * <p>Everything here is asked on the hot chat path (before every model call), so implementations
 * keep each answer to an in-memory check or one primary-key lookup, and never throw: a module that
 * fails to answer must cost its own feature, not the turn.
 */
public interface ChatModule {

    /** The {@code @Tool} beans this module adds. Registered with every other tool (McpToolConfig). */
    List<Object> toolObjects();

    /**
     * Skill name -> the tool names reading or picking that skill unlocks (ToolSkillGating). Every
     * tool of {@link #ownToolNames()} must be in a group, or it would be a CORE tool, offered in
     * every conversation on the deployment. Core tools the skill also relies on are NOT listed
     * here - listing one would make it gated everywhere - but in the skill file's own tools.
     */
    Map<String, Set<String>> skillToolGroups();

    /** This module's own tools: the ones {@link #offersToolsIn} decides about. */
    Set<String> ownToolNames();

    /**
     * Whether this module's own tools may be offered in this conversation right now - on top of
     * the skill being unlocked there, never instead of it. {@code conversationId} may be null.
     * {@code userId} is the user the turn runs for, or null: whatever the module keeps about a
     * conversation is the user's who made it, and anybody else asking is told there is nothing.
     */
    boolean offersToolsIn(String conversationId, String userId);

    /**
     * Lines for this conversation's system prompt, identity only, or empty. The system prompt is
     * compared whole on every turn (ChatService.refreshSystemPrompt) and rewritten when it differs,
     * so this must not carry anything that moves between turns - a date, a count, a status - or the
     * provider's cached prefix is lost on every turn. {@code userId} as for {@link #offersToolsIn}.
     */
    Optional<String> systemContext(String conversationId, String userId);

    /** Whether this module's skills are within this installation's license (LicenseToolScope). */
    boolean licensed();

    /** The names of the skills this module contributes. */
    default Set<String> skillNames() {
        return skillToolGroups().keySet();
    }
}
