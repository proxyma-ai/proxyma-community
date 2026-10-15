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
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * NATIVE PROTOCOLS SEND WHAT THEIR VENDOR DEFINES, AND A GATEWAY'S ADDITIONS STAY ON ITS OWN
 * PROTOCOL (the maintainer's rule, 2026-10-13). Read off the requests a loopback server receives,
 * through each adapter's own clients.
 *
 * <ul>
 *   <li><b>OpenRouter</b> caches only when asked, with a top-level {@code cache_control} (2026-10-09,
 *       Claude through OpenRouter was never cached): every call carries it with the turn's lifetime,
 *       a generic one and a stream included. It was a per-instance switch on the OpenAI protocol.</li>
 *   <li><b>Native OpenAI</b> and <b>OpenAI-compatible</b> servers never get it - OpenAI's own API
 *       refuses a field it does not know.</li>
 *   <li><b>Native OpenAI</b> needs a key; an OpenAI-compatible server is sent a placeholder.</li>
 *   <li><b>LiteLLM</b> is spoken on the API its instance chose, and states model limits on its own
 *       {@code /model/info}.</li>
 * </ul>
 */
class NativeAndGatewayRequestsTest {

    private static final String COMPLETION = "{\"id\":\"c1\",\"object\":\"chat.completion\",\"created\":1,"
            + "\"model\":\"anthropic/claude-sonnet-4.5\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
            + "\"content\":\"Hi!\"},\"finish_reason\":\"stop\"}],\"usage\":{\"completion_tokens\":2,\"prompt_tokens\":5,"
            + "\"total_tokens\":7}}";

    private static final String MESSAGE = "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
            + "\"model\":\"claude-haiku-4-5\",\"content\":[{\"type\":\"text\",\"text\":\"Hi!\"}],"
            + "\"stop_reason\":\"end_turn\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":5,\"output_tokens\":2}}";

    private static final String STREAM = String.join("\n",
            "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Hi!\"},\"finish_reason\":null}]}",
            "",
            "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}",
            "",
            "data: [DONE]",
            "", "");

    private final ObjectMapper json = new ObjectMapper();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final SimpleClientHttpRequestFactory http = new SimpleClientHttpRequestFactory();
    private HttpServer server;

    private record Request(String path, String authorization, String body) {}

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    // -- OpenRouter's cache instruction ------------------------------------------------------------

    @Test
    @DisplayName("OpenRouter, a one-hour turn: a top-level cache_control with a ttl of 1h")
    void openRouterOneHour() throws Exception {
        OpenRouterAdapter adapter = new OpenRouterAdapter(http, WebClient.builder());
        ResolvedProvider provider = provider(ProviderProtocol.OPENROUTER, serve(200, "application/json", COMPLETION));
        call(adapter, provider, PromptCache.ONE_HOUR);

        JsonNode control = sent().path("cache_control");
        assertThat(control.path("type").asText()).isEqualTo("ephemeral");
        assertThat(control.path("ttl").asText()).isEqualTo("1h");
    }

    @Test
    @DisplayName("OpenRouter, a five-minute turn - and Auto, should it arrive unresolved: ephemeral, no ttl")
    void openRouterFiveMinutes() throws Exception {
        OpenRouterAdapter adapter = new OpenRouterAdapter(http, WebClient.builder());
        ResolvedProvider provider = provider(ProviderProtocol.OPENROUTER, serve(200, "application/json", COMPLETION));
        call(adapter, provider, PromptCache.FIVE_MINUTES);
        call(adapter, provider, PromptCache.AUTO);

        for (int i = 0; i < 2; i++) {
            JsonNode control = sent(i).path("cache_control");
            assertThat(control.path("type").asText()).isEqualTo("ephemeral");
            assertThat(control.has("ttl")).isFalse();
        }
    }

    @Test
    @DisplayName("OpenRouter, a call with no options of its own (condensation, titles) is cached too")
    void openRouterGenericCalls() throws Exception {
        OpenRouterAdapter adapter = new OpenRouterAdapter(http, WebClient.builder());
        ResolvedProvider provider = provider(ProviderProtocol.OPENROUTER, serve(200, "application/json", COMPLETION));
        model(adapter, provider).call(new Prompt(new UserMessage("hi")));

        assertThat(sent().path("cache_control").path("type").asText()).isEqualTo("ephemeral");
    }

    @Test
    @DisplayName("OpenRouter, a streamed turn carries it as well")
    void openRouterStreams() throws Exception {
        OpenRouterAdapter adapter = new OpenRouterAdapter(http, WebClient.builder());
        ResolvedProvider provider = provider(ProviderProtocol.OPENROUTER, serve(200, "text/event-stream", STREAM));
        ToolCallingChatOptions options = adapter.buildChatOptions(provider, "anthropic/claude-sonnet-4.5",
                Tone.CONCISE, 1024, List.of(), PromptCache.ONE_HOUR);
        model(adapter, provider).stream(new Prompt(new UserMessage("hi"), options))
                .collectList().block(Duration.ofSeconds(10));

        assertThat(sent().path("cache_control").path("ttl").asText()).isEqualTo("1h");
    }

    // -- What a native protocol and a generic server are NOT sent ---------------------------------

    @Test
    @DisplayName("native OpenAI and OpenAI-compatible servers get no cache_control anywhere")
    void noCacheControlOffOpenRouter() throws Exception {
        String url = serve(200, "application/json", COMPLETION);
        for (OpenAiShapedAdapter adapter : List.of(
                new OpenAiAdapter(http, WebClient.builder()),
                new OpenAiCompatibleAdapter(http, WebClient.builder()))) {
            ResolvedProvider provider = provider(adapter.protocol(), url);
            call(adapter, provider, PromptCache.ONE_HOUR);
            model(adapter, provider).call(new Prompt(new UserMessage("hi")));
        }

        assertThat(requests).hasSize(4).allSatisfy(r -> assertThat(r.body()).doesNotContain("cache_control"));
    }

    @Test
    @DisplayName("native OpenAI needs a key; an OpenAI-compatible server is sent a placeholder")
    void keys() throws Exception {
        String url = serve(200, "application/json", COMPLETION);
        ResolvedProvider keyless = new ResolvedProvider(UUID.randomUUID(), "Box", ProviderProtocol.OPENAI_COMPATIBLE,
                url, null, Map.of(), false);

        assertThatThrownBy(() -> new OpenAiAdapter(http, WebClient.builder())
                .buildChatModel(keyless, "gpt-x", Tone.CONCISE, 1024))
                .hasMessageContaining("no API key");

        model(new OpenAiCompatibleAdapter(http, WebClient.builder()), keyless).call(new Prompt(new UserMessage("hi")));
        assertThat(requests.get(0).authorization()).isEqualTo("Bearer not-required");
    }

    // -- LiteLLM ---------------------------------------------------------------------------------

    @Test
    @DisplayName("LiteLLM is spoken on the API its instance chose")
    void liteLlmRoutes() throws Exception {
        String url = serve(200, "application/json", MESSAGE);
        LiteLlmAdapter adapter = liteLlm();
        ResolvedProvider messages = liteLlmProvider(url, GatewayApi.ANTHROPIC_MESSAGES);
        adapter.buildChatModel(messages, "claude-haiku-4-5", Tone.CONCISE, 1024).call(new Prompt(new UserMessage("hi")));
        assertThat(requests.get(0).path()).isEqualTo("/v1/messages");

        server.stop(0);
        requests.clear();
        url = serve(200, "application/json", COMPLETION);
        ResolvedProvider chat = liteLlmProvider(url, GatewayApi.OPENAI_CHAT);
        adapter.buildChatModel(chat, "claude-haiku-4-5", Tone.CONCISE, 1024).call(new Prompt(new UserMessage("hi")));
        assertThat(requests.get(0).path()).isEqualTo("/v1/chat/completions");
    }

    @Test
    @DisplayName("LiteLLM states a model's limits on /model/info, by the model's name")
    void liteLlmLimits() throws Exception {
        String url = serve(200, "application/json", "{\"data\":["
                + "{\"model_name\":\"other\",\"model_info\":{\"max_input_tokens\":1000}},"
                + "{\"model_name\":\"claude-haiku-4-5\",\"model_info\":{\"max_input_tokens\":200000,\"max_output_tokens\":64000}}]}");
        ModelLimits limits = liteLlm().modelLimits(liteLlmProvider(url, GatewayApi.ANTHROPIC_MESSAGES), "claude-haiku-4-5");

        assertThat(requests.get(0).path()).isEqualTo("/model/info");
        assertThat(limits).isEqualTo(new ModelLimits(200000, 64000));
    }

    @Test
    @DisplayName("a key limited to the LLM routes is refused /model/info - a failure the factory reads as unknown")
    void liteLlmLimitsRefused() throws Exception {
        String url = serve(403, "application/json", "{\"detail\":\"Virtual key is not allowed to call this route.\"}");

        assertThatThrownBy(() -> liteLlm().modelLimits(liteLlmProvider(url, GatewayApi.OPENAI_CHAT), "claude-haiku-4-5"))
                .isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    @Test
    @DisplayName("only a LiteLLM instance told so stops streaming")
    void streamsUnlessAGatewayIsToldNot() {
        assertThat(new ResolvedProvider(UUID.randomUUID(), "L", ProviderProtocol.LITELLM, "http://x", null, Map.of(),
                false, GatewayApi.OPENAI_CHAT, true).streams()).isFalse();
        assertThat(new ResolvedProvider(UUID.randomUUID(), "L", ProviderProtocol.LITELLM, "http://x", null, Map.of(),
                false, GatewayApi.OPENAI_CHAT, false).streams()).isTrue();
        // A native protocol carrying the flag (a row moved between protocols) still streams.
        assertThat(new ResolvedProvider(UUID.randomUUID(), "A", ProviderProtocol.ANTHROPIC, null, "k", Map.of(),
                false, null, true).streams()).isTrue();
    }

    // -- Harness -----------------------------------------------------------------------------------

    private LiteLlmAdapter liteLlm() {
        return new LiteLlmAdapter(new AnthropicAdapter(http, WebClient.builder()), http, WebClient.builder());
    }

    private static ResolvedProvider liteLlmProvider(String baseUrl, GatewayApi api) {
        return new ResolvedProvider(UUID.randomUUID(), "LiteLLM", ProviderProtocol.LITELLM, baseUrl, "sk-test",
                Map.of(), false, api, false);
    }

    private static ResolvedProvider provider(ProviderProtocol protocol, String baseUrl) {
        return new ResolvedProvider(UUID.randomUUID(), protocol.displayName(), protocol, baseUrl, "test-key",
                Map.of(), false);
    }

    private static void call(LlmProviderAdapter adapter, ResolvedProvider provider, PromptCache cache) {
        ToolCallingChatOptions options = adapter.buildChatOptions(provider, "anthropic/claude-sonnet-4.5",
                Tone.CONCISE, 1024, List.of(), cache);
        model(adapter, provider).call(new Prompt(new UserMessage("hi"), options));
    }

    private static ChatModel model(LlmProviderAdapter adapter, ResolvedProvider provider) {
        return adapter.buildChatModel(provider, "anthropic/claude-sonnet-4.5", Tone.CONCISE, 1024);
    }

    private JsonNode sent() throws Exception {
        return sent(requests.size() - 1);
    }

    private JsonNode sent(int i) throws Exception {
        return json.readTree(requests.get(i).body());
    }

    private String serve(int status, String contentType, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new Request(exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
