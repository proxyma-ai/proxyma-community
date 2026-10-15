package ai.proxyma.core.provider.adapter;

import ai.proxyma.core.settings.dto.ChatSettings.PromptCache;
import ai.proxyma.core.settings.dto.ChatSettings.Tone;
import ai.proxyma.core.settings.provider.ProviderProtocol;
import ai.proxyma.core.settings.provider.ResolvedProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The conversation's cache breakpoint is on the LAST message of the request, in a blocking call and
 * a stream alike (0.29.2 feedback). Without AnthropicCacheTail, Spring AI put the three it had
 * left on the first answer, the second question and the first tool step of this conversation, and
 * none on the five tool results after them - captured from the request on loopback.
 */
class AnthropicCacheTailTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<String> sent = new AtomicReference<>();

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    @DisplayName("a blocking call: one history breakpoint, on the last tool result, with the asked lifetime")
    void blockingCall() throws Exception {
        AnthropicAdapter adapter = adapter("application/json",
                "{\"id\":\"m\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"x\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],"
                        + "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}");
        ChatModel model = model(adapter);
        model.call(new Prompt(longTurn(), adapter.buildChatOptions("m", Tone.CONCISE, 1024, List.of(), PromptCache.ONE_HOUR)));
        assertTail(JSON.readTree(sent.get()), "1h");
    }

    @Test
    @DisplayName("a stream: the same, through the WebClient")
    void stream() throws Exception {
        String sse = String.join("\n",
                "event: message_start",
                "data: {\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"x\",\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}",
                "",
                "event: message_delta",
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":1}}",
                "",
                "event: message_stop",
                "data: {\"type\":\"message_stop\"}",
                "", "");
        AnthropicAdapter adapter = adapter("text/event-stream", sse);
        ChatModel model = model(adapter);
        model.stream(new Prompt(longTurn(), adapter.buildChatOptions("m", Tone.CONCISE, 1024, List.of(), PromptCache.FIVE_MINUTES)))
                .blockLast(Duration.ofSeconds(10));
        assertTail(JSON.readTree(sent.get()), "5m");
    }

    @Test
    @DisplayName("a request with no breakpoint in its messages is sent as it was")
    void untouchedWithoutBreakpoints() {
        byte[] body = "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}]}"
                .getBytes(StandardCharsets.UTF_8);
        assertThat(AnthropicCacheTail.retail(body)).isSameAs(body);
        byte[] notJson = "not json".getBytes(StandardCharsets.UTF_8);
        assertThat(AnthropicCacheTail.retail(notJson)).isSameAs(notJson);
    }

    @Test
    @DisplayName("a thinking block is never the one marked")
    void thinkingIsSkipped() throws Exception {
        byte[] body = ("{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"q\",\"cache_control\":{\"type\":\"ephemeral\"}}]},"
                + "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"a\"},{\"type\":\"thinking\",\"thinking\":\"t\",\"signature\":\"s\"}]}]}")
                .getBytes(StandardCharsets.UTF_8);
        JsonNode out = JSON.readTree(AnthropicCacheTail.retail(body));
        JsonNode last = out.path("messages").path(1).path("content");
        assertThat(last.path(0).has("cache_control")).isTrue();
        assertThat(last.path(1).has("cache_control")).isFalse();
        assertThat(out.path("messages").path(0).path("content").path(0).has("cache_control")).isFalse();
    }

    // The system keeps its breakpoint; in the messages there is exactly one, on the last block of
    // the last message.
    private static void assertTail(JsonNode request, String ttl) {
        assertThat(request.toString()).contains("\"system\"");
        assertThat(request.path("system").toString()).contains("cache_control");
        JsonNode messages = request.path("messages");
        int marked = 0;
        for (JsonNode message : messages) {
            for (JsonNode block : message.path("content")) if (block.has("cache_control")) marked++;
        }
        assertThat(marked).as("history breakpoints").isEqualTo(1);
        JsonNode lastBlocks = messages.get(messages.size() - 1).path("content");
        JsonNode last = lastBlocks.get(lastBlocks.size() - 1);
        assertThat(last.path("type").asText()).isEqualTo("tool_result");
        assertThat(last.path("cache_control").path("ttl").asText()).isEqualTo(ttl);
    }

    private static List<Message> longTurn() {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("system ".repeat(2000)));
        messages.add(new UserMessage("first question " + "x ".repeat(1500)));
        messages.add(new AssistantMessage("first answer " + "y ".repeat(1500)));
        messages.add(new UserMessage("second question"));
        for (int i = 0; i < 5; i++) {
            messages.add(AssistantMessage.builder().content("step " + i)
                    .toolCalls(List.of(new AssistantMessage.ToolCall("t" + i, "function", "search", "{\"q\":\"" + i + "\"}")))
                    .build());
            messages.add(ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse("t" + i, "search", "result " + i + " " + "z ".repeat(1500))))
                    .build());
        }
        return messages;
    }

    private AnthropicAdapter adapter(String contentType, String reply) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            sent.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new AnthropicAdapter(new SimpleClientHttpRequestFactory(), WebClient.builder());
    }

    private ChatModel model(AnthropicAdapter adapter) {
        ResolvedProvider provider = new ResolvedProvider(UUID.randomUUID(), "S", ProviderProtocol.ANTHROPIC,
                "http://127.0.0.1:" + server.getAddress().getPort(), "k", Map.of(), false);
        return adapter.buildChatModel(provider, "m", Tone.CONCISE, 1024);
    }
}
