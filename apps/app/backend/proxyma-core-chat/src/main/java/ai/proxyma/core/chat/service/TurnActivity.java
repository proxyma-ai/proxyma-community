package ai.proxyma.core.chat.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What each RUNNING turn is doing at this moment - the tool it is in, and whether it is waiting out a
 * provider's rate limit - for a page that shows it without following the conversation's event stream
 * (Constellations' agent cards, 2026-10-29). In memory only, one small entry per running turn: put
 * when the turn registers and removed when it ends, by that turn alone, so nothing here outlives the
 * turn it describes and a later turn's entry is never removed by an earlier one's end.
 *
 * <p>A tool is held by its NAME and call id only - never its arguments or its result.
 */
final class TurnActivity {

    /** One running turn, as {@link #of} reads it. {@code tool} is the latest call still in flight, else null. */
    record Snapshot(long startedAt, String tool, boolean rateLimited) {}

    private record Call(String name, long order) {}

    private static final class Live {
        final long startedAt = System.currentTimeMillis();
        final Map<String, Call> calls = new ConcurrentHashMap<>();
        volatile boolean rateLimited;
    }

    private final ConcurrentHashMap<String, Live> turns = new ConcurrentHashMap<>();
    // Which of several calls in flight started last: the one a reader is shown.
    private final AtomicLong order = new AtomicLong();

    /** A turn of this conversation has registered. Returns the token its {@link #end} takes. */
    Object begin(String conversationId) {
        Live live = new Live();
        turns.put(conversationId, live);
        return live;
    }

    /** The turn that {@link #begin} answered {@code token} to has ended. */
    void end(String conversationId, Object token) {
        if (conversationId != null && token != null) turns.remove(conversationId, token);
    }

    /** A tool call started in this conversation's running turn; nothing when no turn runs. */
    void toolStarted(String conversationId, String callId, String toolName) {
        Live live = conversationId == null ? null : turns.get(conversationId);
        if (live == null || callId == null || toolName == null) return;
        live.calls.put(callId, new Call(toolName, order.incrementAndGet()));
    }

    /** A tool call ended - answered, refused, abandoned. A call nobody holds is no change. */
    void toolEnded(String conversationId, String callId) {
        Live live = conversationId == null ? null : turns.get(conversationId);
        if (live != null && callId != null) live.calls.remove(callId);
    }

    /** The turn's model call is waiting out a rate limit ({@code true}), or has started its next attempt. */
    void rateLimited(String conversationId, boolean waiting) {
        Live live = conversationId == null ? null : turns.get(conversationId);
        if (live != null) live.rateLimited = waiting;
    }

    /** The conversation's running turn, or null when none runs. */
    Snapshot of(String conversationId) {
        Live live = conversationId == null ? null : turns.get(conversationId);
        if (live == null) return null;
        Call latest = null;
        for (Call c : live.calls.values()) {
            if (latest == null || c.order() > latest.order()) latest = c;
        }
        return new Snapshot(live.startedAt, latest == null ? null : latest.name(), live.rateLimited);
    }
}
