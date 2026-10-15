package ai.proxyma.core.chat.dto;

import ai.proxyma.core.system.util.SensitivePaths;

import java.util.List;

/**
 * A sub-agent as the thread shows it: what it is, how it ended, what it did and what it reported.
 *
 * <p>The same object whether it is read from a run still going or from its stored row, so a page
 * that reloads draws the panel it was drawing before. Field names match the frontend's
 * {@code SubAgentInfo} (chat.types.ts).
 *
 * @param mode   {@code sync} or {@code async}
 * @param status {@code running}, {@code completed}, {@code partial}, {@code failed} or {@code stopped}
 * @param items  tool calls and the text between them, in the order they happened
 * @param awaitingModelSince when its model call went out, while it waits on one; else null
 * @param timeoutAt when it is stopped if it has not finished, while it runs; else null
 */
public record SubAgentView(String id, String mode, String status, String title, Long startedAt, Long endedAt,
                           String report, List<Item> items, Long awaitingModelSince, Long timeoutAt) {

    /** One thing it did: a tool call ({@code kind} "tool") or something it said ("text"). */
    public record Item(String kind, String callId, String toolName, String arguments, String result,
                       boolean done, Long startedAt, Long endedAt, String text) {

        public static Item tool(String callId, String toolName, String arguments, String result, boolean done,
                                Long startedAt, Long endedAt) {
            return new Item("tool", callId, toolName, arguments, result, done, startedAt, endedAt, null);
        }

        public static Item text(String text) {
            return new Item("text", null, null, null, null, true, null, null, text);
        }

        Item scrubbed() {
            return new Item(kind, callId, toolName, SensitivePaths.scrub(arguments), SensitivePaths.scrub(result),
                    done, startedAt, endedAt, SensitivePaths.scrub(text));
        }
    }

    /**
     * The copy that may cross to a browser: no server path in an argument, a result, the text or
     * the report. Screened here, at the boundary, for the reason every other field of a transcript
     * is - the stored record keeps the real paths, which the agent itself worked from.
     */
    public SubAgentView scrubbed() {
        return new SubAgentView(id, mode, status, SensitivePaths.scrub(title), startedAt, endedAt,
                SensitivePaths.scrub(report), items == null ? List.of() : items.stream().map(Item::scrubbed).toList(),
                awaitingModelSince, timeoutAt);
    }
}
