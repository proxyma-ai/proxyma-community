package ai.proxyma.core.chat.agents;

import ai.proxyma.core.chat.domain.MessageRole;
import ai.proxyma.core.chat.model.AgentNode;
import ai.proxyma.core.chat.model.Conversation;
import ai.proxyma.core.system.util.SensitivePaths;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One agent of the tree as the agents map reads it (GET /chat/conversations/{id}/agent-tree): where
 * it stands and, when it ended other than by finishing, why - as a CODE the page words in the
 * reader's language (AgentNode.REASON_*), never the sentence the agents read, which carries an ISO
 * time and is English only. Beside it the figures the map sets against their limits: the context
 * its conversation holds against the window, what it spent against its budget, and the times its run
 * time and deadline are read from.
 *
 * <p>Titles, reports and scorecards the model wrote cross to the browser screened, as every other
 * agent text is.
 *
 * <p>THE MAIN AGENT HAS A ROW TOO ({@link #main}, the maintainer's feedback of 2026-11-03: "Main agent
 * must have Idle status. Its cost and run time must be shown."). It has no AgentNode - it is the
 * conversation the user opened - so what it is doing comes from the chat's own in-memory state, what
 * it spent is its own conversation's (every deployed agent spends on a conversation of its own), and
 * its run time is the wall clock of its turns, read off its stored messages ({@link #workedOf}).
 */
public final class AgentTreeView {

    private AgentTreeView() {}

    /**
     * @param c             its conversation, or null when it cannot be read - its figures are then zero
     * @param working       whether a turn runs in its conversation now
     * @param contextWindow the window every conversation is condensed against (SmartChatMemory)
     * @param runsOn        the model it runs on when one was chosen for it; both null for the chat's own
     * @param providerName  that model's provider instance's name, or null when it cannot be named
     */
    public static Map<String, Object> of(AgentNode n, Conversation c, boolean working, int contextWindow,
                                         AgentTreeModule.RunsOn runsOn, String providerName) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("agentId", n.getId().toString());
        v.put("conversationId", n.getConversationId());
        v.put("parentConversationId", n.getParentConversationId());
        v.put("title", SensitivePaths.scrub(n.getTitle()));
        v.put("status", n.getStatus());
        v.put("lifetime", n.getLifetime());
        v.put("depth", n.getDepth());
        v.put("owesReport", n.isReportOwed());
        v.put("lastReport", n.getLastReport() == null ? null : SensitivePaths.scrub(n.getLastReport()));
        String model = runsOn == null ? null : runsOn.model();
        v.put("model", model);
        v.put("providerName", model == null ? null : providerName);
        v.put("createdAt", iso(n.getCreatedAt()));
        v.put("updatedAt", iso(n.getUpdatedAt()));
        v.put("endedAt", iso(n.getEndedAt()));
        v.put("working", working);
        v.put("inputTokens", c == null ? 0 : c.getInputTokens());
        v.put("outputTokens", c == null ? 0 : c.getOutputTokens());
        v.put("costUsd", c == null ? 0 : c.getEstimatedCostUsd());
        v.put("contextTokens", c == null ? 0 : c.getContextTokens());
        v.put("contextWindow", contextWindow > 0 ? contextWindow : null);
        v.put("budgetUsd", n.getBudgetUsd());
        v.put("deadlineAt", iso(n.getDeadlineAt()));
        v.put("role", n.getRole());
        v.put("verification", n.getVerification() == null ? null : SensitivePaths.scrub(n.getVerification()));
        // A code only for an agent that has ended: a LONG one given work again is not still stopped.
        v.put("stopReason", n.isEnded() ? n.getStopReason() : null);
        return v;
    }

    /** One stored message's role and time - all {@link #workedOf} reads of a message. */
    public record Stamp(MessageRole role, Instant createdAt, boolean summary) {}

    /**
     * How long the main agent has worked: the wall clock of its finished turns, and when the one
     * running now started - null when none runs - so the page counts that one up to its own now.
     */
    public record Worked(long finishedMs, Instant runningSince) {}

    /**
     * The main agent's time worked, read off its conversation's stored messages in order (as the
     * agents map read it before the agent tree, 0.29.1 feedback): a turn starts at a user message -
     * one the person sent, or the mail a deployed agent's report wakes it with, which is stored the
     * same way - and ends at the last message written before the next one. Nothing records a turn as
     * such; every step of one is written as it finishes, so its last row is its end.
     *
     * <p>A condensation's summary rows are nobody's message and start nothing; the system message is
     * no turn. A message typed mid-turn ends one turn and starts the next a moment later, so the gap
     * between the step before it and it is not counted - seconds, against a figure in minutes.
     *
     * @param running whether a turn runs now: the last one is open, and the page counts it on
     */
    public static Worked workedOf(List<Stamp> messages, boolean running) {
        long finished = 0;
        Instant start = null;
        Instant last = null;
        for (Stamp m : messages == null ? List.<Stamp>of() : messages) {
            if (m == null || m.createdAt() == null || m.summary() || m.role() == MessageRole.SYSTEM) continue;
            if (m.role() == MessageRole.USER) {
                if (start != null) finished += Math.max(0, last.toEpochMilli() - start.toEpochMilli());
                start = m.createdAt();
                last = start;
            } else if (start != null && m.createdAt().isAfter(last)) {
                last = m.createdAt();
            }
        }
        if (start == null) return new Worked(0, null);
        if (running) return new Worked(finished, start);
        return new Worked(finished + Math.max(0, last.toEpochMilli() - start.toEpochMilli()), null);
    }

    /**
     * The main agent as the agents map reads it: whether a turn runs, whether a write of its waits for
     * a person, whether its turn is waiting out a provider's rate limit, whether work it started - its
     * agents, a background job - is still out; and its figures: the context its conversation holds
     * against the window, what its OWN conversation spent (the deployed agents' spend is on theirs),
     * and its time worked.
     *
     * @param c             the conversation the user opened, or null when it cannot be read
     * @param contextWindow the window every conversation is condensed against, 0 when unknown
     */
    public static Map<String, Object> main(Conversation c, boolean working, boolean awaitingApproval,
                                           boolean rateLimited, boolean waiting, int contextWindow, Worked worked) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("working", working);
        v.put("awaitingApproval", awaitingApproval);
        v.put("rateLimited", working && rateLimited);
        v.put("waiting", !working && waiting);
        v.put("inputTokens", c == null ? 0 : c.getInputTokens());
        v.put("outputTokens", c == null ? 0 : c.getOutputTokens());
        v.put("costUsd", c == null ? 0 : c.getEstimatedCostUsd());
        v.put("contextTokens", c == null ? 0 : c.getContextTokens());
        v.put("contextWindow", contextWindow > 0 ? contextWindow : null);
        v.put("workedMs", worked == null ? 0 : worked.finishedMs());
        v.put("turnStartedAt", worked == null ? null : iso(worked.runningSince()));
        return v;
    }

    private static String iso(Instant at) {
        return at == null ? null : at.toString();
    }
}
