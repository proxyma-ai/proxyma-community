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
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A STREAMED TURN SHOWS WHAT THE MODEL THINKS (2026-11-11, the maintainer: "can we have its thinking stream and print out
 * thinking tokens count instead of just the timer?"): a model that thinks by default - Claude's from generation 5 - is
 * asked for its thinking's summary, which it otherwise streams empty; an older one, which would START thinking if asked,
 * and a request that sets thinking another way are sent as they were.
 */
class AnthropicThinkingDisplayTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<String> sent = new AtomicReference<>();

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private static JsonNode shown(String body) throws Exception {
        return JSON.readTree(AnthropicThinkingDisplay.shown(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("a model that thinks by default is asked for its thinking's summary; one that would start thinking is not")
    void whichModels() throws Exception {
        for (String m : List.of("claude-haiku-5-5", "claude-opus-5-5", "claude-sonnet-5", "claude-fable-5-1", "claude-opus-5")) {
            assertThat(AnthropicThinkingDisplay.thinksByDefault(m)).as(m).isTrue();
        }
        for (String m : List.of("claude-opus-4-8", "claude-sonnet-4-6", "claude-haiku-4-5", "claude-3-7-sonnet", "gpt-5", "")) {
            assertThat(AnthropicThinkingDisplay.thinksByDefault(m)).as(m).isFalse();
        }
        assertThat(shown("{\"model\":\"claude-haiku-5-5\",\"messages\":[]}").path("thinking").toString())
                .isEqualTo("{\"type\":\"adaptive\",\"display\":\"summarized\"}");
        assertThat(shown("{\"model\":\"claude-opus-5-5\",\"thinking\":{\"type\":\"adaptive\"}}").path("thinking").path("display").asText())
                .isEqualTo("summarized");
    }

    @Test
    @DisplayName("an older model, thinking set another way, a display already named and anything not a request are left as they were")
    void leftAlone() {
        for (String body : List.of("{\"model\":\"claude-opus-4-8\",\"messages\":[]}",
                "{\"model\":\"claude-sonnet-5-5\",\"thinking\":{\"type\":\"between_tools\"}}",
                "{\"model\":\"claude-haiku-5-5\",\"thinking\":{\"type\":\"adaptive\",\"display\":\"omitted\"}}",
                "{\"model\":\"claude-haiku-4-5\",\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":2048}}",
                "not json", "[1,2]")) {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            assertThat(AnthropicThinkingDisplay.shown(b)).as(body).isSameAs(b);
        }
    }

    @Test
    @DisplayName("a stream to the native protocol carries it, through the adapter's own WebClient")
    void throughTheAdapter() throws Exception {
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
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            sent.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        AnthropicAdapter adapter = new AnthropicAdapter(new SimpleClientHttpRequestFactory(), WebClient.builder());
        ResolvedProvider provider = new ResolvedProvider(UUID.randomUUID(), "S", ProviderProtocol.ANTHROPIC,
                "http://127.0.0.1:" + server.getAddress().getPort(), "k", Map.of(), false);
        adapter.buildChatModel(provider, "claude-haiku-5-5", Tone.CONCISE, 1024)
                .stream(new Prompt(List.of(new UserMessage("hi")),
                        adapter.buildChatOptions("claude-haiku-5-5", Tone.CONCISE, 1024, List.of(), PromptCache.FIVE_MINUTES)))
                .blockLast(Duration.ofSeconds(10));
        assertThat(JSON.readTree(sent.get()).path("thinking").path("display").asText()).isEqualTo("summarized");
    }
}
