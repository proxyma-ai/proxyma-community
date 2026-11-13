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
 * Its contract is deliberately narrow - it cannot rename the product, re-gate another module's tools
 * or reach a conversation it does not own, and it replaces the product's general instructions only
 * for a conversation it owns ({@link #basePrompt}) - and nothing outside this repository implements it.
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
     * The general instructions this conversation's system prompt starts with, in place of the
     * product's own - the built-in prompt (or the one a super admin saved) with its delegation guidance
     * and the reminder at its end (SystemPromptService.DEFAULT, DELEGATION, DELEGATION_REMINDER) - or
     * empty to keep those. For a conversation this module OWNS, whose agent works differently from the
     * product's general assistant: a saved agent of a team, often on a cheap model, unattended, under
     * an access that may withhold what the general prompt promises. SystemPromptService.AGENT_BASE is
     * written to be returned here. {{PRODUCT_NAME}} is filled in as in the product's prompt.
     *
     * <p>Only the head is replaced. The rest of the system prompt is built as for any conversation and
     * in the same order: {@link #systemContext}, the license note, the skills and workflows lists, the
     * date and who the person is, and the guardrail's canary. So whatever the text, the product's own
     * blocks still follow it - and the absolute rules are the text's to carry, as AGENT_BASE does.
     *
     * <p>The first module that answers is used. Like {@link #systemContext} it is part of a prompt that
     * is compared whole on every turn, so it must not move between turns. {@code userId} as for
     * {@link #offersToolsIn}. Cheap, never throws: a module that fails is passed over, and the
     * product's own prompt is used.
     */
    default Optional<String> basePrompt(String conversationId, String userId) {
        return Optional.empty();
    }

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

    /**
     * What changed in what {@link #memoryContext} gave this conversation since it was given or last told - a
     * teammate's edit, a new shared file - as a short note, or empty when nothing did. Asked at the first step
     * of a later turn while a copy of the memory is in view; the chat writes the answer into the conversation
     * as a note after the turn's opening, like the context note (never the system prompt, never a re-send of
     * the memory). The module keeps what it last told. {@code userId} as for {@link #offersToolsIn}. Cheap
     * for a conversation that is nobody's agent; never throws.
     */
    default Optional<String> memoryChanges(String conversationId, String userId) {
        return Optional.empty();
    }

    /** Whether this module's skills are within this installation's license (LicenseToolScope). */
    boolean licensed();

    /**
     * How this conversation runs differently from an ordinary chat, when it is one this module
     * holds for a saved agent - its model, step limit, withheld tools, sources and memory - or
     * empty. Every field only narrows or redirects (see {@link AgentProfile}); none can widen what
     * the turn's user may do, and none replaces the product's own system prompt ({@link #basePrompt}
     * is the one hook that does, and only its general instructions). {@code userId} as
     * for {@link #offersToolsIn}: a module answers only the user the conversation is bound for.
     * Read through {@link AgentProfiles}, which keeps a failure to the one call.
     */
    default Optional<AgentProfile> profileFor(String conversationId, String userId) {
        return Optional.empty();
    }

    /**
     * Whether this module KEEPS this conversation: it is part of something the module owns - a saved
     * agent's conversation in a Constellations project - and goes when that thing does (the project,
     * the team), never because it sat idle. The chat's conversation retention skips such a
     * conversation and every agent deployed under it (ConversationRetentionSweep; the maintainer,
     * 2026-11-12: a channel team quiet for a week lost its conversations, which the Privacy Policy says
     * stay until the team is removed). Asked once a day per idle conversation; a cached lookup is fine.
     */
    default boolean keepsConversation(String conversationId) {
        return false;
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
     * Why a person may not decide this conversation's waiting writes now - its module's add-on is not in force, so the
     * conversation's project is read-only (2026-11-10, the maintainer: "it's currently not really read only as I can
     * still approve") - or empty to let them. Asked of the tree's root conversation. Cheap, never throws.
     */
    default Optional<String> decisionRefused(String conversationId) {
        return Optional.empty();
    }

    /**
     * Whether a write this conversation keeps in the approvals inbox ({@link #parksApprovals}) is approved by the
     * server the moment it is asked, with nobody reviewing it (ApprovalInbox.approveAutomatically) - a Constellations
     * project whose agents' writes are switched to go ahead. The write is still kept and listed as decided. Say yes only
     * where the person the write runs for chose it. Cheap, never throws.
     */
    default boolean autoApproves(String conversationId) {
        return false;
    }

    /**
     * Whether how a write this conversation kept in the approvals inbox went - {@code failed}: approved and its
     * call failed for good - is written into the conversation as a NOTE its agent reads at its next turn, rather
     * than starting a turn for it (ApprovalInbox; 2026-11-04: a batch of seventeen approved replies woke the
     * writer for five model calls of bookkeeping). Say yes only for a conversation of this module's whose agent
     * has nothing to do about it. A deployed agent that still owes its report is woken whatever this says - its
     * report waits on the decision. Cheap, never throws.
     */
    default boolean approvalOutcomeQuiet(String conversationId, String toolName, boolean failed) {
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

    /**
     * What a person wrote to open a turn in this conversation - for a module that keeps what was said
     * (Constellations keeps each person's conversation with a saved agent in its workspace). Called just
     * after {@link #personSpoke} for a turn a person opened, never for one a module or an agent started.
     * Cheap, never throws.
     */
    default void personSaid(String conversationId, String message) {
    }

    /**
     * The @-mentions of a person's message that name something of this module's rather than a source -
     * Constellations' projects, whose leader the person addresses from the main chat. Asked once per
     * message that carries mentions, before its turn is built, with the names as the composer sent
     * them and the user the turn runs for (null on Desktop and Home Server, or for a visitor). What it
     * claims is taken out of the sources the agent is told to prioritize, and its note is put in the
     * message instead; a module that claims a name is also the one that offers whatever acts on it
     * (toolsOfferedIn). Must answer only for what this user may work with, and never throw.
     */
    default MentionClaim claimMentions(String conversationId, String userId, List<String> mentions) {
        return MentionClaim.NONE;
    }

    /**
     * How long a call of one of this module's tools may run before the chat gives it up, in seconds -
     * for a tool that WAITS for another agent's answer, which the chat's own minute would cut short. 0
     * leaves it to the chat. Cheap, never throws.
     */
    default int toolTimeoutSeconds(String toolName) {
        return 0;
    }

    /**
     * The chat gave up a call of this tool - Stop, or its time limit - and nobody will read what it
     * returns. A tool of this module's that waits must end that wait and keep anything it took for the
     * conversation, as awaitAgents does (AgentTree.abandonWaits). Called for every tool given up, a
     * module's or not; cheap, never throws.
     */
    default void toolAbandoned(String conversationId, String toolName) {
    }

    /**
     * Whether something this module started for this conversation will answer it later - a question
     * to a Constellations project's lead that the lead has not answered yet. Background work: the turn's
     * end says more is coming, so the page keeps following the conversation and the turn the answer
     * starts reaches it, and the conversation's status says it is not done. Asked often - a map lookup.
     */
    default boolean awaitsAnswer(String conversationId) {
        return false;
    }

    /**
     * What the per-call log names these conversations' model calls after, for the ones this module
     * holds for an agent - "Project / Agent" and how the run started - or nothing for the rest. Asked
     * when the log is READ (ConversationCallLabels), once per page or download for its distinct
     * conversations, so a few queries for the whole batch and never one per conversation. Names only,
     * never what was said; whoever may read the call reads its label. Never throws.
     */
    default Map<String, ai.proxyma.core.settings.usage.LlmCallLabels.Label> callLabels(
            java.util.Collection<String> conversationIds) {
        return Map.of();
    }

    /** The names of the skills this module contributes. */
    default Set<String> skillNames() {
        return skillToolGroups().keySet();
    }

    /**
     * HOW THE PAGE SHOWS A USER-ROLE MESSAGE THIS MODULE WROTE - a run's opening ("[Scheduled run, ...]"
     * and its task), a note for a lead, a person's words relayed under a header for the agent - or empty
     * for one it did not write. The agent reads the message as stored; the page must not show the
     * headers, which are written for the model and are English whatever the reader's language (the
     * maintainer, 2026-11-05). So an opening comes back either as the person's own words alone
     * ({@link Shown#personText}) or as a mail line of a kind the page names in the reader's language
     * ({@link Shown#mail}), carrying only the words worth reading under it - a task - or none.
     * {@code content} has the composer's markers already taken off (ChatService.sanitizeUserContent).
     * Asked by the transcript for every user-role row: a check of the text, never a query; never throws.
     */
    default Optional<Shown> shownOpening(String content) {
        return Optional.empty();
    }

    /**
     * An inbox item this module wrote, as the page shows it: a heading written for the model can carry
     * words about its sender ("Scout: it could not answer", "Ana (Writer), your peer") that the page must
     * not show. Returns the item with its sender's name alone and, where those words said something, a
     * kind of its own (a reply that failed); the item unchanged when it is not this module's. Applied to
     * the transcript and to the live 'agent-mail' event alike. A check of the text; never throws.
     */
    default ai.proxyma.core.chat.agents.AgentInbox.Item shownMail(ai.proxyma.core.chat.agents.AgentInbox.Item item) {
        return item;
    }

    /**
     * A user-role message as the page draws it ({@link #shownOpening}): the person's own words with the
     * header for the agent taken off, or a mail line of its own kind. Exactly one of the two is set.
     */
    record Shown(String personText, ai.proxyma.core.chat.agents.AgentInbox.Item mail) {
        public static Shown asPerson(String text) {
            return new Shown(text == null ? "" : text, null);
        }

        /** A mail line of {@code kind}, with {@code text} under it, or none when null. */
        public static Shown asMail(String kind, String text) {
            return new Shown(null, new ai.proxyma.core.chat.agents.AgentInbox.Item(null, null, kind, text));
        }
    }
}
