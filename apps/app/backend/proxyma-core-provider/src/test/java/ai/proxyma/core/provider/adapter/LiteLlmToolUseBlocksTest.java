package ai.proxyma.core.provider.adapter;

import ai.proxyma.core.settings.dto.ChatSettings.Tone;
import ai.proxyma.core.settings.provider.GatewayApi;
import ai.proxyma.core.settings.provider.ProviderProtocol;
import ai.proxyma.core.settings.provider.ResolvedProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A LITELLM GATEWAY'S TOOL CALL ARRIVES WHOLE (0.39.0 feedback, two gateways): on its Anthropic route
 * a LiteLLM proxy closes a content block late - after the NEXT block has started and sent its first
 * piece - and Spring AI's aggregation closes the tool call it is gathering at the first
 * content_block_stop of ANY block. The tool call's arguments were parsed half-written, and the turn
 * ended with "JsonEOFException: Unexpected end-of-input: expected close marker for Object".
 *
 * <p>Against a loopback server replaying that order, through the real AnthropicApi: the native
 * adapter fails exactly as reported (which pins the cause), and the LiteLLM adapter, which gathers
 * each tool block's events and hands them over together (AnthropicToolUseBlocks), gets the text and
 * the whole tool call.
 */
class LiteLlmToolUseBlocksTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private static String event(String type, String json) {
        return "event: " + type + "\ndata: " + json + "\n\n";
    }

    private static final String START = event("message_start",
            "{\"type\":\"message_start\",\"message\":{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\","
                    + "\"content\":[],\"model\":\"claude-test\",\"usage\":{\"input_tokens\":5,\"output_tokens\":0}}}");
    private static final String END = event("message_delta",
            "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":20}}")
            + event("message_stop", "{\"type\":\"message_stop\"}");

    // Text, then a tool call whose first piece arrives BEFORE the text block's stop.
    private static final String LATE_STOP = START
            + event("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")
            + event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Let me look.\"}}")
            + event("content_block_start", "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"searchIndexedSources\",\"input\":{}}}")
            + event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"query\\\": \\\"release\"}}")
            + event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
            + event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\" plan\\\"}\"}}")
            + event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}")
            + END;

    // Two tool calls, the first closed only after the second has started, and the second never
    // closed at all.
    private static final String INTERLEAVED_UNCLOSED = START
            + event("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_a\",\"name\":\"recall\",\"input\":{}}}")
            + event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"query\\\": \"}}")
            + event("content_block_start", "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_b\",\"name\":\"searchConversations\",\"input\":{}}}")
            + event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"budget\\\"}\"}}")
            + event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"q\\\": \\\"x\\\"}\"}}")
            + event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
            + END;

    @Test
    @DisplayName("the native adapter fails on a late content_block_stop exactly as the feedback reported")
    void nativeFailsAsReported() throws Exception {
        String url = serve(LATE_STOP);
        ChatModel model = new AnthropicAdapter(new SimpleClientHttpRequestFactory(), WebClient.builder())
                .buildChatModel(new ResolvedProvider(UUID.randomUUID(), "Anthropic", ProviderProtocol.ANTHROPIC,
                        url, "test-key", Map.of(), false), "claude-test", Tone.CONCISE, 1024);
        Throwable failure = catchThrowable(() -> model.stream(new Prompt(new UserMessage("hi"))).blockLast(Duration.ofSeconds(10)));
        assertThat(failure).isNotNull();
        assertThat(String.valueOf(failure.getMessage()) + causes(failure)).contains("Unexpected end-of-input");
    }

    @Test
    @DisplayName("through LiteLLM the text streams and the tool call arrives whole")
    void liteLlmGetsTheWholeToolCall() throws Exception {
        List<ChatResponse> chunks = liteLlm(serve(LATE_STOP)).stream(prompt())
                .collectList().block(Duration.ofSeconds(10));
        assertThat(text(chunks)).isEqualTo("Let me look.");
        List<AssistantMessage.ToolCall> calls = toolCalls(chunks);
        assertThat(calls).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("searchIndexedSources");
            assertThat(c.arguments()).contains("\"query\"").contains("release plan");
        });
    }

    @Test
    @DisplayName("interleaved tool calls are each whole, and one never closed is closed at the end")
    void interleavedAndUnclosed() throws Exception {
        List<ChatResponse> chunks = liteLlm(serve(INTERLEAVED_UNCLOSED)).stream(prompt())
                .collectList().block(Duration.ofSeconds(10));
        List<AssistantMessage.ToolCall> calls = toolCalls(chunks);
        assertThat(calls).extracting(AssistantMessage.ToolCall::name).containsExactly("recall", "searchConversations");
        assertThat(calls.get(0).arguments()).contains("budget");
        assertThat(calls.get(1).arguments()).contains("\"x\"");
    }

    @Test
    @DisplayName("events are regrouped only around tool blocks; everything else passes in order")
    void regroupsOnlyToolBlocks() {
        AnthropicToolUseBlocks blocks = new AnthropicToolUseBlocks();
        List<String> out = new ArrayList<>();
        for (String e : LATE_STOP.split("\n\n")) out.addAll(blocks.accept(e + "\n\n"));
        out.addAll(blocks.finish());
        String joined = String.join("", out);
        int textStop = joined.indexOf("\"index\":0}");
        int toolStart = joined.indexOf("toolu_1");
        assertThat(textStop).as("the text block closes before the tool block is handed over").isLessThan(toolStart);
        assertThat(joined.indexOf("release")).isGreaterThan(toolStart);
        assertThat(joined.indexOf(" plan")).isLessThan(joined.lastIndexOf("content_block_stop"));
        assertThat(joined).endsWith(event("message_stop", "{\"type\":\"message_stop\"}"));
    }

    // The tool calls are the answer here: nothing is to run them.
    private static Prompt prompt() {
        return new Prompt(new UserMessage("hi"), org.springframework.ai.anthropic.AnthropicChatOptions.builder()
                .internalToolExecutionEnabled(false).build());
    }

    private ChatModel liteLlm(String url) {
        ResolvedProvider provider = new ResolvedProvider(UUID.randomUUID(), "Gateway", ProviderProtocol.LITELLM,
                url, "test-key", Map.of(), false, GatewayApi.ANTHROPIC_MESSAGES, false);
        SimpleClientHttpRequestFactory http = new SimpleClientHttpRequestFactory();
        return new LiteLlmAdapter(new AnthropicAdapter(http, WebClient.builder()), http, WebClient.builder())
                .buildChatModel(provider, "claude-test", Tone.CONCISE, 1024);
    }

    private String serve(String events) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = events.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String text(List<ChatResponse> chunks) {
        StringBuilder out = new StringBuilder();
        for (ChatResponse r : chunks) {
            if (r.getResults() == null) continue;
            r.getResults().forEach(g -> {
                String t = g.getOutput().getText();
                if (t != null) out.append(t);
            });
        }
        return out.toString();
    }

    // By id, as the chat folds a stream: Spring AI's last chunk restates the final tool call.
    private static List<AssistantMessage.ToolCall> toolCalls(List<ChatResponse> chunks) {
        java.util.Map<String, AssistantMessage.ToolCall> out = new java.util.LinkedHashMap<>();
        for (ChatResponse r : chunks) {
            if (r.getResults() == null) continue;
            r.getResults().forEach(g -> g.getOutput().getToolCalls().forEach(c -> out.putIfAbsent(c.id(), c)));
        }
        return new ArrayList<>(out.values());
    }

    private static String causes(Throwable t) {
        StringBuilder s = new StringBuilder();
        for (Throwable c = t.getCause(); c != null && c != c.getCause(); c = c.getCause()) s.append(" | ").append(c.getMessage());
        return s.toString();
    }
}
