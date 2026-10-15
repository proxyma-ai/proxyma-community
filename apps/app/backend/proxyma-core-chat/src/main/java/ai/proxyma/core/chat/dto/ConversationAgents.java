package ai.proxyma.core.chat.dto;

import ai.proxyma.core.chat.domain.MessageRole;
import ai.proxyma.core.chat.service.SubAgentService.AgentUsage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A conversation's agents and what each spent - the agents graph opened beside the composer's
 * model (maintainer's request, 2026-10-03), and the one place these figures are shown.
 *
 * <p>THE MAIN AGENT'S SHARE IS DERIVED, not recorded: the conversation's totals hold every call
 * made for it - the main agent's steps, its title, condensing it, and each sub-agent - and the
 * sub-agents' rows say what they spent, so the rest is the main agent's. A sub-agent that ran
 * before its row carried a figure counts as nothing here and its spend stays in the main share,
 * which is still right as a total. Clamped at zero, so a rounding difference never shows a
 * negative.
 *
 * <p>{@code turns} are when the MAIN agent worked, for the graph's total time (0.29.1 feedback):
 * that is the time during which at least one agent - the main one or any sub-agent - was working,
 * not the sum of every agent's time. The page takes the union of these and the sub-agents' own
 * runs, so time two agents worked side by side counts once.
 */
public record ConversationAgents(Spend main, List<AgentUsage> subAgents, Spend total, List<Turn> turns) {

    public record Spend(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens,
                        double costUsd) {}

    /** One turn of the main agent, epoch milliseconds; {@code endedAt} null while it still runs. */
    public record Turn(long startedAt, Long endedAt) {}

    /** One stored message's role and time - all {@link #turnsOf} reads of a message. */
    public record Stamp(MessageRole role, Instant createdAt, boolean summary) {}

    public static ConversationAgents of(Spend total, List<AgentUsage> subAgents) {
        return of(total, subAgents, List.of());
    }

    public static ConversationAgents of(Spend total, List<AgentUsage> subAgents, List<Turn> turns) {
        long in = total.inputTokens(), out = total.outputTokens();
        long read = total.cacheReadTokens(), write = total.cacheWriteTokens();
        double cost = total.costUsd();
        for (AgentUsage a : subAgents) {
            in -= a.inputTokens();
            out -= a.outputTokens();
            read -= a.cacheReadTokens();
            write -= a.cacheWriteTokens();
            cost -= a.costUsd();
        }
        Spend main = new Spend(Math.max(0, in), Math.max(0, out), Math.max(0, read), Math.max(0, write),
                Math.max(0, cost));
        return new ConversationAgents(main, subAgents, total, turns);
    }

    /**
     * The main agent's turns, read off the conversation's stored messages in order: a turn starts
     * at a user message - one the user sent, or a background sub-agent's report, which starts a
     * turn the same way - and ends at the last message written before the next one. Nothing records
     * a turn as such; every step of one is written as it finishes, so its last row is its end.
     *
     * <p>A condensation's summary rows are not anybody's message and start nothing. A message
     * typed mid-turn (inject) ends one turn and starts the next a moment later, so the gap between
     * the step before it and it is not counted - seconds, against a figure in minutes.
     *
     * @param running whether a turn is running now: the last one is open, and the page counts it
     *                up to the moment it is looking at
     */
    public static List<Turn> turnsOf(List<Stamp> messages, boolean running) {
        List<Turn> turns = new ArrayList<>();
        Instant start = null;
        Instant last = null;
        for (Stamp m : messages) {
            if (m.createdAt() == null || m.summary() || m.role() == MessageRole.SYSTEM) continue;
            if (m.role() == MessageRole.USER) {
                if (start != null) turns.add(new Turn(start.toEpochMilli(), last.toEpochMilli()));
                start = m.createdAt();
                last = start;
            } else if (start != null && m.createdAt().isAfter(last)) {
                last = m.createdAt();
            }
        }
        if (start != null) turns.add(new Turn(start.toEpochMilli(), running ? null : last.toEpochMilli()));
        return turns;
    }
}
