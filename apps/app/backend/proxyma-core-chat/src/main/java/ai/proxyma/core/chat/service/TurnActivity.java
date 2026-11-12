package ai.proxyma.core.chat.service;

import ai.proxyma.core.system.util.SensitivePaths;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
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
 * <p>A tool is held by its NAME, its call id and a SUBJECT - never its result, and never its arguments
 * as they were sent. The subject (2026-10-31, the maintainer: a card should say "searching for
 * something", not just "searching") is a small JSON object of the few argument keys a page names a
 * call by (the query, the issue key, a file's NAME, a page's address...), each value cut short and
 * run through SensitivePaths.scrub, the whole at most {@link #SUBJECT_MAX} characters. It reaches
 * only the person whose conversation it is - Constellations' card endpoint gives everyone else the
 * tool's name alone.
 */
final class TurnActivity {

    /**
     * One running turn, as {@link #of} reads it. {@code tool} is the latest call still in flight, else the
     * last one that ended within {@link #LINGER_MS}, else null; {@code subject} that call's subject (see the
     * class note), else null.
     */
    record Snapshot(long startedAt, String tool, String subject, boolean rateLimited) {}

    private record Call(String name, String subject, long order) {}

    /** The most a subject is, as JSON: a few short values, never a document. */
    static final int SUBJECT_MAX = 1024;
    // Each value's own bound before the scrub - enough to say what was searched for.
    private static final int VALUE_MAX = 160;
    // A call's arguments larger than this are not read for a subject at all (a document being saved).
    private static final int ARGUMENTS_MAX = 64 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    // The argument keys a page names a call by (the chat's toolActivity: DETAIL_KEYS and the few its
    // special cases read), in that page's order. Anything else - a body, a document, a result - stays.
    private static final List<String> SUBJECT_KEYS = List.of(
            "issueKey", "pageId", "prId", "commitId", "query", "q", "jql", "cql", "pattern", "url", "fact",
            "summary", "title", "name", "skillName", "task", "targetStatus", "repoSlug", "projectKey", "spaceKey",
            "branch", "tag", "fileName", "server", "product", "message", "note", "action", "tool", "from", "to",
            "id", "forget", "waitForReply", "method", "agent");

    /**
     * How long a call that has ended is still named, when none is in flight (2026-11-11, the maintainer: a card said
     * "thinking" through a learning run whose three YouTube calls were answered in milliseconds). A page asks every
     * four seconds while a team works (poll.ts WORKING_POLL_MS): a call shorter than that was never seen.
     */
    static final long LINGER_MS = 6_000;

    private record Ended(Call call, long at) {}

    private static final class Live {
        final long startedAt;
        final Map<String, Call> calls = new ConcurrentHashMap<>();
        volatile boolean rateLimited;
        volatile Ended lastEnded;

        Live(long startedAt) {
            this.startedAt = startedAt;
        }
    }

    private final ConcurrentHashMap<String, Live> turns = new ConcurrentHashMap<>();
    private final java.util.function.LongSupplier clock;

    TurnActivity() {
        this(System::currentTimeMillis);
    }

    TurnActivity(java.util.function.LongSupplier clock) {
        this.clock = clock;
    }
    // Which of several calls in flight started last: the one a reader is shown.
    private final AtomicLong order = new AtomicLong();

    /** A turn of this conversation has registered. Returns the token its {@link #end} takes. */
    Object begin(String conversationId) {
        Live live = new Live(clock.getAsLong());
        turns.put(conversationId, live);
        return live;
    }

    /** The turn that {@link #begin} answered {@code token} to has ended. */
    void end(String conversationId, Object token) {
        if (conversationId != null && token != null) turns.remove(conversationId, token);
    }

    /** A tool call started in this conversation's running turn; nothing when no turn runs. */
    void toolStarted(String conversationId, String callId, String toolName) {
        toolStarted(conversationId, callId, toolName, null);
    }

    /** As above, with the call's arguments as sent, read for its subject and then dropped. */
    void toolStarted(String conversationId, String callId, String toolName, String arguments) {
        Live live = conversationId == null ? null : turns.get(conversationId);
        if (live == null || callId == null || toolName == null) return;
        live.calls.put(callId, new Call(toolName, subjectOf(toolName, arguments), order.incrementAndGet()));
    }

    /**
     * The subject of a call: the keys a page names it by, each a short scrubbed string, a number or a
     * yes/no. A key ending in "path" is a file's path and becomes its NAME ({@code fileName}) - except
     * a REST passthrough's {@code path}, which is the API address it calls. Null when nothing is left.
     */
    static String subjectOf(String toolName, String arguments) {
        if (arguments == null || arguments.isBlank() || arguments.length() > ARGUMENTS_MAX) return null;
        JsonNode args;
        try {
            args = JSON.readTree(arguments);
        } catch (Exception notJson) {
            return null;
        }
        if (args == null || !args.isObject()) return null;
        ObjectNode out = JSON.createObjectNode();
        boolean rest = toolName != null && toolName.startsWith("call") && toolName.endsWith("Api");
        // A saved agent's workspace file (Constellations' readWorkspaceFile and its siblings): its path is
        // an address inside the agent's project, never a server path, and the page reads it to say what
        // the file HOLDS - "Reading its memory" - and never shows it (toolActivity's workspaceFileKind).
        boolean workspace = toolName != null && toolName.endsWith("WorkspaceFile");
        for (String key : SUBJECT_KEYS) {
            // An id says nothing to a reader; remember's says the entry is corrected, not new.
            if (key.equals("id") && !"remember".equals(toolName)) continue;
            put(out, key, args.get(key));
        }
        if (rest || workspace) put(out, "path", args.get("path"));
        if (!out.has("fileName") && !workspace) {
            var names = args.fieldNames();
            while (names.hasNext()) {
                String key = names.next();
                if (!key.toLowerCase(java.util.Locale.ROOT).endsWith("path") || (rest && key.equals("path"))) continue;
                JsonNode v = args.get(key);
                if (v != null && v.isTextual() && !v.asText().isBlank()) {
                    String path = v.asText().strip();
                    while (path.endsWith("/") || path.endsWith("\\")) path = path.substring(0, path.length() - 1);
                    int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
                    put(out, "fileName", JSON.getNodeFactory().textNode(path.substring(cut + 1)));
                    break;
                }
            }
        }
        if (out.isEmpty()) return null;
        String json = out.toString();
        return json.length() > SUBJECT_MAX ? null : json;
    }

    private static void put(ObjectNode out, String key, JsonNode v) {
        if (v == null || v.isNull()) return;
        if (v.isTextual()) {
            String text = v.asText().strip();
            if (text.isEmpty()) return;
            if (text.length() > VALUE_MAX) text = text.substring(0, VALUE_MAX);
            out.put(key, SensitivePaths.scrub(text));
        } else if (v.isNumber()) {
            out.set(key, v);
        } else if (v.isBoolean()) {
            out.put(key, v.asBoolean());
        }
    }

    /** A tool call ended - answered, refused, abandoned. A call nobody holds is no change. */
    void toolEnded(String conversationId, String callId) {
        Live live = conversationId == null ? null : turns.get(conversationId);
        if (live == null || callId == null) return;
        Call ended = live.calls.remove(callId);
        if (ended != null) live.lastEnded = new Ended(ended, clock.getAsLong());
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
        Ended ended = live.lastEnded;
        if (latest == null && ended != null && clock.getAsLong() - ended.at() < LINGER_MS) latest = ended.call();
        return new Snapshot(live.startedAt, latest == null ? null : latest.name(),
                latest == null ? null : latest.subject(), live.rateLimited);
    }
}
