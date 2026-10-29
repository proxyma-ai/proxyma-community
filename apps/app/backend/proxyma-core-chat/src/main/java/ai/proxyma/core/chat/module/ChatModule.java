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
     * Which of this module's own tools may be offered in this conversation right now - for a module
     * whose tools are not all-or-nothing (the key-account tools that OPEN an account are offered while
     * none is open, the ones that work on it only once one is; a Constellations project's board is
     * offered to the agents a saved agent deployed, its team tools only to the saved agent). The
     * default is every own tool when {@link #offersToolsIn} says yes, else none. Same contract: cheap,
     * never throws.
     */
    default Set<String> toolsOfferedIn(String conversationId, String userId) {
        return offersToolsIn(conversationId, userId) ? ownToolNames() : Set.of();
    }

    /**
     * Lines for this conversation's system prompt, identity only, or empty. The system prompt is
     * compared whole on every turn (ChatService.refreshSystemPrompt) and rewritten when it differs,
     * so this must not carry anything that moves between turns - a date, a count, a status - or the
     * provider's cached prefix is lost on every turn. {@code userId} as for {@link #offersToolsIn}.
     */
    Optional<String> systemContext(String conversationId, String userId);

    /**
     * What this conversation's agent keeps between conversations - a saved agent's long-term memory,
     * read from its workspace - as the text it reads before it works, or empty. It is NOT part of the
     * system prompt, which must not move between turns ({@link #systemContext}): the chat writes it
     * into the conversation as a message of its own (AgentInbox.Kind.MEMORY) at the first model call
     * that has no copy in view - a conversation's first step, and the first after a condensation took
     * the earlier copy into its summary. So it is asked before a model call only while no copy is in
     * view, and the answer for a conversation that is nobody's agent must stay one primary-key lookup.
     * {@code userId} as for {@link #offersToolsIn}. Never throws.
     */
    default Optional<String> memoryContext(String conversationId, String userId) {
        return Optional.empty();
    }

    /** Whether this module's skills are within this installation's license (LicenseToolScope). */
    boolean licensed();

    /**
     * How this conversation runs differently from an ordinary chat, when it is one this module
     * holds for a saved agent - its model, step limit, withheld tools, sources and memory - or
     * empty. Every field only narrows or redirects (see {@link AgentProfile}); none can widen what
     * the turn's user may do, and none replaces the product's own system prompt. {@code userId} as
     * for {@link #offersToolsIn}: a module answers only the user the conversation is bound for.
     * Read through {@link AgentProfiles}, which keeps a failure to the one call.
     */
    default Optional<AgentProfile> profileFor(String conversationId, String userId) {
        return Optional.empty();
    }

    /**
     * Whether this module holds something for this conversation's agent that has not reached it yet -
     * a teammate's message, a reply. Asked whenever a turn could take it (between steps, at a turn's
     * end) and when {@code ChatService.pendingInputArrived} is called; it must be CHEAP - a map lookup,
     * never a query - since an ordinary conversation is asked too.
     */
    default boolean hasPendingInput(String conversationId) {
        return false;
    }

    /**
     * Takes what {@link #hasPendingInput} said was owed, once: the text the agent reads as the next
     * user-role message, or empty when something else took it first. What is taken is the module's
     * to mark delivered; the chat puts it in the conversation and runs a step on it, starting a turn
     * when none runs - the way an agent's report reaches the agent that deployed it.
     */
    default Optional<String> takePendingInput(String conversationId) {
        return Optional.empty();
    }

    /**
     * A turn of this conversation has ended after {@code durationMs} of wall clock - ordinary,
     * continued or unattended alike. For a module that keeps how long its agents work. Called on the
     * turn's own thread after everything is written; it must not throw and must not be slow.
     */
    default void turnEnded(String conversationId, long durationMs) {
    }

    /**
     * The same, with the turn's final answer (null when it failed) - for a module that sends the
     * answer on, as Constellations sends a saved agent's reply to the teammate that asked.
     */
    default void turnEnded(String conversationId, long durationMs, String answer) {
        turnEnded(conversationId, durationMs);
    }

    /**
     * Whether nobody watches this conversation, so a write it asks to make is KEPT in the approvals
     * inbox (ApprovalInbox) rather than held as a prompt in the turn - a run its module started on a
     * schedule or for another agent. A question for the person is answered at once, saying so, and a
     * turn at its step limit ends rather than asking. Cheap, never throws.
     */
    default boolean parksApprovals(String conversationId) {
        return false;
    }

    /**
     * Why this conversation may take no further step - a budget spent, a deadline passed - or empty.
     * Asked before every model call, so it must be cheap (cached where it adds anything up). A module
     * that answers is also the one that stops what else must stop and tells whom it must; the chat only
     * ends the turn, with this sentence as its answer.
     */
    default Optional<String> stopReason(String conversationId) {
        return Optional.empty();
    }

    /**
     * A person has just written in this conversation - opened a turn, or typed into a running one. For
     * a module that bounds what agents do on their own between two things a person says. Cheap, never
     * throws.
     */
    default void personSpoke(String conversationId) {
    }

    /** The names of the skills this module contributes. */
    default Set<String> skillNames() {
        return skillToolGroups().keySet();
    }
}
