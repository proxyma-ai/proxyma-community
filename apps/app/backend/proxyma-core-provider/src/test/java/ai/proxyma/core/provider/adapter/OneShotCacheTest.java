package ai.proxyma.core.provider.adapter;

import ai.proxyma.core.settings.dto.ChatSettings.PromptCache;
import ai.proxyma.core.settings.dto.ChatSettings.Tone;
import ai.proxyma.core.settings.provider.GatewayApi;
import ai.proxyma.core.settings.provider.ProviderProtocol;
import ai.proxyma.core.settings.provider.ResolvedProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NO ANTHROPIC CALL GOES OUT UNCACHED (2026-11-14, the maintainer: "ensure all providers are handled correctly, always
 * avoid non-cache calls"). A call made with no options of its own - a conversation's title, the knowledge graph's
 * extraction, the guardrail, query expansion, reranking, OCR, a workflow step, the YouTube team's calls - and a session
 * opened with plain options (condensation) asked Anthropic to cache nothing, so a long fixed instruction was paid in full
 * at every call: the YouTube team's drafting calls, about 10,000 tokens each on Claude Haiku, never read a cache.
 *
 * <p>Such a call is ONE-SHOT: its system prompt is what repeats, its message what is new. So its default is a
 * breakpoint on the SYSTEM prompt alone - the message is never written to a cache it will not be read from - for five
 * minutes. A turn's own options (AnthropicAdapter.buildChatOptions) keep theirs: the conversation's history, with the
 * breakpoint on its last message. Read off the requests a loopback server receives, through the adapters' own clients.
 */
class OneShotCacheTest {

    private static final String MESSAGE = "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
            + "\"model\":\"claude-haiku-5-5\",\"content\":[{\"type\":\"text\",\"text\":\"Hi!\"}],"
            + "\"stop_reason\":\"end_turn\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":5,\"output_tokens\":2}}";

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    @DisplayName("Anthropic, a call with no options of its own: the system prompt is cached, the message is not")
    void anthropicGenericCall() throws Exception {
        AnthropicAdapter adapter = new AnthropicAdapter(new SimpleClientHttpRequestFactory(), WebClient.builder());
        ResolvedProvider provider = provider(ProviderProtocol.ANTHROPIC, serve());
        adapter.buildChatModel(provider, "claude-haiku-5-5", Tone.CONCISE, 1024)
                .call(new Prompt(List.of(new SystemMessage("The fixed instructions."), new UserMessage("One comment."))));
        assertSystemOnly(sent());
    }

    @Test
    @DisplayName("Anthropic, a session with plain options (condensation): cached the same way")
    void anthropicPlainSession() throws Exception {
        AnthropicAdapter adapter = new AnthropicAdapter(new SimpleClientHttpRequestFactory(), WebClient.builder());
        ResolvedProvider provider = provider(ProviderProtocol.ANTHROPIC, serve());
        ToolCallingChatOptions plain = ToolCallingChatOptions.builder().model("claude-haiku-5-5")
                .internalToolExecutionEnabled(false).build();
        adapter.buildChatModel(provider, "claude-haiku-5-5", Tone.CONCISE, 1024)
                .call(new Prompt(List.of(new SystemMessage("Summarize."), new UserMessage("The history.")), plain));
        assertSystemOnly(sent());
    }

    @Test
    @DisplayName("Anthropic, a tuned session naming no lifetime (the YouTube team's calls): one-shot, as a plain call")
    void anthropicTunedOneShot() throws Exception {
        AnthropicAdapter adapter = new AnthropicAdapter(new SimpleClientHttpRequestFactory(), WebClient.builder());
        ResolvedProvider provider = provider(ProviderProtocol.ANTHROPIC, serve());
        ToolCallingChatOptions tuned = adapter.buildChatOptions(provider, "claude-haiku-5-5", Tone.CONCISE, 1024, List.of(), null);
        adapter.buildChatModel(provider, "claude-haiku-5-5", Tone.CONCISE, 1024)
                .call(new Prompt(List.of(new SystemMessage("The fixed instructions."), new UserMessage("One comment.")), tuned));
        assertSystemOnly(sent());
    }

    @Test
    @DisplayName("Anthropic, a turn's own options keep the conversation's breakpoint on its last message")
    void anthropicTurnKeepsHistory() throws Exception {
        AnthropicAdapter adapter = new AnthropicAdapter(new SimpleClientHttpRequestFactory(), WebClient.builder());
        ResolvedProvider provider = provider(ProviderProtocol.ANTHROPIC, serve());
        ToolCallingChatOptions turn = adapter.buildChatOptions(provider, "claude-haiku-5-5", Tone.CONCISE, 1024, List.of(),
                PromptCache.FIVE_MINUTES);
        adapter.buildChatModel(provider, "claude-haiku-5-5", Tone.CONCISE, 1024)
                .call(new Prompt(List.of(new SystemMessage("The agent."), new UserMessage("A question.")), turn));
        JsonNode body = sent();
        assertThat(lastBlock(body).path("cache_control").path("type").asText()).as("the history's breakpoint").isEqualTo("ephemeral");
    }

    @Test
    @DisplayName("LiteLLM on Anthropic's API, a call with no options of its own: cached as Anthropic is")
    void liteLlmAnthropicGenericCall() throws Exception {
        LiteLlmAdapter adapter = new LiteLlmAdapter(new AnthropicAdapter(new SimpleClientHttpRequestFactory(), WebClient.builder()),
                new SimpleClientHttpRequestFactory(), WebClient.builder());
        String url = serve();
        ResolvedProvider provider = new ResolvedProvider(UUID.randomUUID(), "LiteLLM", ProviderProtocol.LITELLM, url, "sk-test",
                Map.of(), false, GatewayApi.ANTHROPIC_MESSAGES, false);
        adapter.buildChatModel(provider, "claude-haiku-5-5", Tone.CONCISE, 1024)
                .call(new Prompt(List.of(new SystemMessage("The fixed instructions."), new UserMessage("One comment."))));
        assertSystemOnly(sent());
    }

    // The system prompt carries the five-minute breakpoint; no message does.
    private void assertSystemOnly(JsonNode body) {
        JsonNode system = body.path("system");
        JsonNode marked = system.isArray() ? system.get(system.size() - 1).path("cache_control") : json.missingNode();
        assertThat(marked.path("type").asText()).as("the system prompt is cached: %s", body).isEqualTo("ephemeral");
        assertThat(marked.has("ttl") && !"5m".equals(marked.path("ttl").asText())).as("five minutes").isFalse();
        assertThat(body.path("messages").toString()).as("no message is written to the cache").doesNotContain("cache_control");
    }

    private static JsonNode lastBlock(JsonNode body) {
        JsonNode messages = body.path("messages");
        JsonNode content = messages.get(messages.size() - 1).path("content");
        return content.isArray() ? content.get(content.size() - 1) : content;
    }

    private JsonNode sent() throws Exception {
        assertThat(bodies).isNotEmpty();
        return json.readTree(bodies.get(bodies.size() - 1));
    }

    private static ResolvedProvider provider(ProviderProtocol protocol, String baseUrl) {
        return new ResolvedProvider(UUID.randomUUID(), protocol.displayName(), protocol, baseUrl, "test-key", Map.of(), false);
    }

    private String serve() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = MESSAGE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
