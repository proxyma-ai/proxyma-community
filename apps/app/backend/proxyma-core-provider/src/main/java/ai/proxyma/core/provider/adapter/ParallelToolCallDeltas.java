package ai.proxyma.core.provider.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.ResolvableType;
import org.springframework.core.codec.StringDecoder;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Gives every tool call of an OpenAI-compatible stream a delta of its own, the one shape Spring AI
 * 1.1.7 can merge.
 *
 * <p>The protocol lets one delta carry several entries in {@code tool_calls}, each with its
 * {@code index}, and a server may send that: several parallel calls emitted whole and together, or
 * a flush that falls where one call's arguments end and the next call begins. Spring AI's stream
 * merger reads one entry per delta and throws {@code IllegalStateException: Currently only one
 * tool call is supported per message!} on the second - which ended the turn, or the sub-agent, in
 * the middle of its work (reported 2026-10-02, a GLM model behind an OpenAI-compatible gateway).
 * It throws only when such a delta is not the first tool-call delta of the reply, so the same
 * model failed on some turns and not on others.
 *
 * <p>So a {@code data:} line whose delta holds more than one tool call becomes one line per call,
 * in the order the server gave them. That order is what the merger needs: an entry with an id
 * opens a call, an entry without one continues the call opened last. The delta's text and thinking
 * stay on the first line, {@code finish_reason} and {@code usage} move to the last - the merger
 * closes its window on the finish reason, and a call after it would be left out. Every other line
 * passes through as the bytes it arrived as.
 *
 * <p>Not repaired here: a server that continues two calls in turns (arguments for index 0, then
 * for index 1, then index 0 again). The merger has no notion of an index and appends to the call
 * opened last; nothing seen so far streams that way.
 */
final class ParallelToolCallDeltas {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DATA = "data:";

    private ParallelToolCallDeltas() {}

    /** One SSE line as the lines to send on: itself, or one line per tool call its delta carried. */
    static List<String> split(String line) {
        if (line == null) return List.of();
        if (!line.startsWith(DATA) || !line.contains("\"tool_calls\"")) return List.of(line);
        JsonNode root;
        try {
            root = JSON.readTree(line.substring(DATA.length()));
        } catch (Exception notJson) {
            return List.of(line);
        }
        if (root == null || !root.isObject()) return List.of(line);
        JsonNode choice = root.path("choices").path(0);
        JsonNode delta = choice.path("delta");
        JsonNode calls = delta.path("tool_calls");
        if (!choice.isObject() || !delta.isObject() || !calls.isArray() || calls.size() < 2) return List.of(line);

        List<String> lines = new ArrayList<>(calls.size());
        int last = calls.size() - 1;
        for (int i = 0; i <= last; i++) {
            ObjectNode chunk = root.deepCopy();
            ObjectNode chunkChoice = (ObjectNode) chunk.path("choices").path(0);
            ObjectNode chunkDelta = (ObjectNode) chunkChoice.path("delta");
            // What the delta said beside its tool calls is said once, on the first line.
            if (i > 0) chunkDelta.retain("role");
            ArrayNode one = chunkDelta.putArray("tool_calls");
            one.add(calls.get(i));
            if (i < last) {
                if (chunkChoice.has("finish_reason")) chunkChoice.putNull("finish_reason");
                chunk.remove("usage");
            }
            lines.add(DATA + " " + chunk);
        }
        return lines;
    }

    /** Applies {@link #split} to every line of an event-stream response; anything else passes through. */
    static ExchangeFilterFunction filter() {
        return ExchangeFilterFunction.ofResponseProcessor(response -> {
            boolean eventStream = response.headers().contentType()
                    .map(MediaType.TEXT_EVENT_STREAM::isCompatibleWith)
                    .orElse(false);
            if (!eventStream) return Mono.just(response);
            return Mono.just(response.mutate().body(ParallelToolCallDeltas::splitLines).build());
        });
    }

    private static Flux<DataBuffer> splitLines(Flux<DataBuffer> body) {
        DefaultDataBufferFactory buffers = DefaultDataBufferFactory.sharedInstance;
        return StringDecoder.allMimeTypes(List.of("\n"), true)
                .decode(body, ResolvableType.forClass(String.class), null, null)
                // Each event ends at a blank line: the one after the original line closes the last
                // of the lines it became, and the others are given their own.
                .map(line -> buffers.wrap((String.join("\n\n", split(line)) + "\n").getBytes(StandardCharsets.UTF_8)));
    }
}
