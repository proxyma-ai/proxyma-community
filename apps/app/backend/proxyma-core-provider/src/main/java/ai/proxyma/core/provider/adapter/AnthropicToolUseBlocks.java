package ai.proxyma.core.provider.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EACH TOOL CALL OF A LITELLM ANTHROPIC STREAM, HANDED OVER WHOLE (0.39.0 feedback, two gateways).
 *
 * <p>Anthropic sends a content block's events in a row: its content_block_start, its deltas, its
 * content_block_stop. A LiteLLM proxy on its Anthropic route does not always: it closes a block
 * LATE, after the next block has started and sent its first piece. Spring AI gathers a tool call
 * from its start to the first content_block_stop of ANY block, so a tool call following text was
 * parsed with half its arguments - "JsonEOFException: Unexpected end-of-input: expected close marker
 * for Object" - and the turn ended.
 *
 * <p>So, on that route alone (LITELLM is a protocol of its own and carries its deviations; the
 * native Anthropic adapter speaks Anthropic as documented): a tool_use block's events are held from
 * its start and released together at its own stop - text keeps streaming as it arrives. A tool block
 * still open when the message ends is closed there, and one whose arguments came in its start rather
 * than as deltas is given them as a delta, which is the only place Spring AI reads them from.
 */
final class AnthropicToolUseBlocks {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The tool blocks held, by index, each its events in order. */
    private final Map<Integer, Held> open = new LinkedHashMap<>();

    private static final class Held {
        final List<String> events = new ArrayList<>();
        String input;          // a non-empty input sent in the start, else null
        boolean deltas;        // whether any input_json_delta came
    }

    static ExchangeFilterFunction streamFilter() {
        return ExchangeFilterFunction.ofResponseProcessor(response -> {
            boolean eventStream = response.headers().contentType()
                    .map(MediaType.TEXT_EVENT_STREAM::isCompatibleWith)
                    .orElse(false);
            if (!eventStream || !response.statusCode().is2xxSuccessful()) return Mono.just(response);
            return Mono.just(response.mutate().body(AnthropicToolUseBlocks::regroup).build());
        });
    }

    private static Flux<DataBuffer> regroup(Flux<DataBuffer> body) {
        return Flux.defer(() -> {
            AnthropicToolUseBlocks blocks = new AnthropicToolUseBlocks();
            StringBuilder pending = new StringBuilder();
            DefaultDataBufferFactory buffers = DefaultDataBufferFactory.sharedInstance;
            return StringDecoder.allMimeTypes(List.of("\n"), true)
                    .decode(body, ResolvableType.forClass(String.class), null, null)
                    .concatMapIterable(raw -> {
                        String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
                        if (!line.isEmpty()) {
                            pending.append(line).append('\n');
                            return List.<String>of();
                        }
                        if (pending.isEmpty()) return List.<String>of();
                        String event = pending.append('\n').toString();
                        pending.setLength(0);
                        return blocks.accept(event);
                    })
                    .concatWith(Flux.defer(() -> {
                        List<String> rest = new ArrayList<>();
                        if (!pending.isEmpty()) rest.addAll(blocks.accept(pending.append('\n').toString()));
                        rest.addAll(blocks.finish());
                        return Flux.fromIterable(rest);
                    }))
                    .map(text -> (DataBuffer) buffers.wrap(text.getBytes(StandardCharsets.UTF_8)));
        });
    }

    /** One whole event (its lines and the blank line after them); what to pass on now, in order. */
    List<String> accept(String event) {
        JsonNode data = dataOf(event);
        String type = data == null ? "" : data.path("type").asText("");
        Integer index = data != null && data.has("index") ? data.path("index").asInt() : null;
        switch (type) {
            case "content_block_start" -> {
                JsonNode block = data.path("content_block");
                if ("tool_use".equals(block.path("type").asText()) && index != null) {
                    if (open.containsKey(index)) return List.of();   // a repeated start: the block goes on
                    Held held = new Held();
                    JsonNode input = block.path("input");
                    if (input.isObject() && !input.isEmpty()) held.input = input.toString();
                    held.events.add(event);
                    open.put(index, held);
                    return List.of();
                }
            }
            case "content_block_delta" -> {
                Held held = index == null ? null : open.get(index);
                if (held != null) {
                    if ("input_json_delta".equals(data.path("delta").path("type").asText())) held.deltas = true;
                    held.events.add(event);
                    return List.of();
                }
            }
            case "content_block_stop" -> {
                Held held = index == null ? null : open.remove(index);
                if (held != null) return release(index, held, event);
            }
            case "message_delta", "message_stop", "error" -> {
                List<String> out = closeAll();
                out.add(event);
                return out;
            }
            default -> { }
        }
        return List.of(event);
    }

    /** What is still held when the stream ends, each closed. */
    List<String> finish() {
        return closeAll();
    }

    private List<String> closeAll() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<Integer, Held> e : open.entrySet()) out.addAll(release(e.getKey(), e.getValue(), stopEvent(e.getKey())));
        open.clear();
        return out;
    }

    private static List<String> release(int index, Held held, String stop) {
        List<String> out = new ArrayList<>(held.events);
        if (!held.deltas && held.input != null) {
            ObjectMapper m = JSON;
            String delta = m.createObjectNode().put("type", "content_block_delta").put("index", index)
                    .set("delta", m.createObjectNode().put("type", "input_json_delta").put("partial_json", held.input))
                    .toString();
            out.add(1, "event: content_block_delta\ndata: " + delta + "\n\n");
        }
        out.add(stop);
        return out;
    }

    private static String stopEvent(int index) {
        return "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":" + index + "}\n\n";
    }

    private static JsonNode dataOf(String event) {
        for (String line : event.split("\n")) {
            if (!line.startsWith("data:")) continue;
            try {
                return JSON.readTree(line.substring("data:".length()).trim());
            } catch (Exception notJson) {
                return null;
            }
        }
        return null;
    }
}
