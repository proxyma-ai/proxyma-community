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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * EACH TOOL CALL OF A LITELLM ANTHROPIC STREAM, HANDED OVER WHOLE (0.39.0 and 0.40.1 feedback).
 *
 * <p>Anthropic sends a content block's events in a row: its content_block_start, its deltas, its
 * content_block_stop, and one tool call is one block. A LiteLLM proxy on its Anthropic route
 * translates OpenAI-shaped chunks and does neither reliably:
 * <ul>
 *   <li>it closes a block LATE, after the next block has started and sent its first piece - and
 *   Spring AI gathers a tool call from its start to the first content_block_stop of ANY block;</li>
 *   <li>it SPLITS one tool call over two blocks: it opens a new block whenever a chunk's kind differs
 *   from the block's, so a chunk with no content between two pieces of a call's arguments counts as
 *   text, and the rest of the arguments arrive in a new tool_use block with an empty name and an id
 *   of its own (LiteLLM's AnthropicStreamWrapper._should_start_new_content_block).</li>
 * </ul>
 * Either way the call was parsed with part of its arguments - "JsonEOFException: Unexpected
 * end-of-input: expected close marker for Object" - and the nameless rest became a call to a tool
 * named "", which the gateway then refused on every later request of the conversation
 * ("tool_use.name: String should have at least 1 character").
 *
 * <p>So, on that route alone (LITELLM is a protocol of its own and carries its deviations; the
 * native Anthropic adapter speaks Anthropic as documented): text streams as it arrives; every tool
 * call is gathered - a nameless tool block's arguments joining the call before it - and handed over
 * whole, one block each, when the message ends. A call is run only once its message has ended
 * anyway. One whose arguments came in its start rather than as deltas is given them as a delta,
 * which is the only place Spring AI reads them from.
 */
final class AnthropicToolUseBlocks {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The calls gathered so far, in the order they started. */
    private final List<Call> calls = new ArrayList<>();
    /** Which call each open tool block's index feeds. */
    private final Map<Integer, Call> open = new HashMap<>();

    private static final class Call {
        final int index;            // the index of the block that started it, which it is handed over under
        final String start;         // its content_block_start event, as sent
        final StringBuilder arguments = new StringBuilder();
        String input;               // a non-empty input sent in the start, else null
        boolean deltas;             // whether any input_json_delta came

        Call(int index, String start) {
            this.index = index;
            this.start = start;
        }
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
                    boolean nameless = block.path("name").asText("").isBlank();
                    Call call;
                    if (nameless && !calls.isEmpty()) {
                        call = calls.get(calls.size() - 1);           // the rest of the call before it
                    } else {
                        call = new Call(index, event);
                        calls.add(call);
                    }
                    JsonNode input = block.path("input");
                    if (input.isObject() && !input.isEmpty() && call.input == null) call.input = input.toString();
                    open.put(index, call);
                    return List.of();
                }
            }
            case "content_block_delta" -> {
                Call call = index == null ? null : open.get(index);
                if (call != null) {
                    JsonNode delta = data.path("delta");
                    if ("input_json_delta".equals(delta.path("type").asText())) {
                        call.deltas = true;
                        call.arguments.append(delta.path("partial_json").asText(""));
                    }
                    return List.of();
                }
            }
            case "content_block_stop" -> {
                if (index != null && open.remove(index) != null) return List.of();
            }
            case "message_delta", "message_stop", "error" -> {
                List<String> out = releaseAll();
                out.add(event);
                return out;
            }
            default -> { }
        }
        return List.of(event);
    }

    /** What is still held when the stream ends. */
    List<String> finish() {
        return releaseAll();
    }

    private List<String> releaseAll() {
        List<String> out = new ArrayList<>();
        for (Call call : calls) {
            out.add(call.start);
            String arguments = call.deltas ? call.arguments.toString() : call.input;
            if (arguments != null && !arguments.isEmpty()) out.add(deltaEvent(call.index, arguments));
            out.add(stopEvent(call.index));
        }
        calls.clear();
        open.clear();
        return out;
    }

    private static String deltaEvent(int index, String partialJson) {
        String delta = JSON.createObjectNode().put("type", "content_block_delta").put("index", index)
                .set("delta", JSON.createObjectNode().put("type", "input_json_delta").put("partial_json", partialJson))
                .toString();
        return "event: content_block_delta\ndata: " + delta + "\n\n";
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
