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
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
 * A CALL OPENROUTER'S CREDIT CAN PAY FOR IS SENT AGAIN AT THE CEILING THE CREDIT COVERS (2026-11-23: every call of a
 * YouTube team failed with "You requested up to 128000 tokens, but can only afford 79672" while the account held
 * credit). Against a loopback server that refuses, as OpenRouter does, any ceiling above what its balance reserves -
 * through the adapter's own clients, blocking and streamed.
 */
class OpenRouterCreditCeilingTest {

    private static final String MODEL = "anthropic/claude-sonnet-4.5";
    /** What a retry asks for when the credit covers 79,672 tokens: a share of it, room for what is spent meanwhile. */
    private static final int AT_SHARE = (int) (79_672 * OpenRouterCreditCeiling.SHARE);

    private static final String COMPLETION = "{\"id\":\"c1\",\"object\":\"chat.completion\",\"created\":1,"
            + "\"model\":\"" + MODEL + "\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
            + "\"content\":\"Hi!\"},\"finish_reason\":\"stop\"}],\"usage\":{\"completion_tokens\":2,\"prompt_tokens\":5,"
            + "\"total_tokens\":7}}";

    private static final String STREAM = String.join("\n",
            "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Hi!\"},\"finish_reason\":null}]}",
            "",
            "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}",
            "",
            "data: [DONE]",
            "", "");

    private final ObjectMapper json = new ObjectMapper();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    /** Each request's method, path and whether it carried the key, in order. */
    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final SimpleClientHttpRequestFactory http = new SimpleClientHttpRequestFactory();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    @DisplayName("a blocking call refused for its ceiling is sent once more at what the credit covers, and answered")
    void blockingCallRetried() throws Exception {
        OpenRouterAdapter adapter = new OpenRouterAdapter(http, WebClient.builder());
        ResolvedProvider provider = provider(openRouter(79_672, false));

        ChatResponse r = model(adapter, provider).call(new Prompt(new UserMessage("hi"), options(adapter, provider, 128_000)));

        assertThat(r.getResult().getOutput().getText()).isEqualTo("Hi!");
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).path("max_tokens").asInt()).isEqualTo(128_000);
        assertThat(requests.get(1).path("max_tokens").asInt()).isEqualTo(AT_SHARE);
        // Nothing else in the request changes.
        assertThat(requests.get(1).path("cache_control").path("type").asText()).isEqualTo("ephemeral");
        assertThat(requests.get(1).path("messages")).isEqualTo(requests.get(0).path("messages"));
    }

    @Test
    @DisplayName("under the JDK client production uses, the call sent again goes to the same address with the same key")
    void retryUnderTheJdkClient() throws Exception {
        // RestClientConfig.llmClientHttpRequestFactory is a JdkClientHttpRequestFactory; the cases above use the simple
        // one and never looked at where the second request went (2026-11-25, while OpenRouter answered every call
        // "400 Request path cannot be relayed").
        OpenRouterAdapter adapter = new OpenRouterAdapter(new org.springframework.http.client.JdkClientHttpRequestFactory(),
                WebClient.builder());
        ResolvedProvider provider = provider(openRouter(79_672, false));

        model(adapter, provider).call(new Prompt(new UserMessage("hi"), options(adapter, provider, 128_000)));

        assertThat(lines).hasSize(2);
        assertThat(lines.get(1)).isEqualTo(lines.get(0)).isEqualTo("POST /v1/chat/completions Bearer test-key");
        assertThat(requests.get(1).path("max_tokens").asInt()).isEqualTo(AT_SHARE);
    }

    @Test
    @DisplayName("a streamed turn refused for its ceiling is sent once more and streams")
    void streamRetried() throws Exception {
        OpenRouterAdapter adapter = new OpenRouterAdapter(http, WebClient.builder());
        ResolvedProvider provider = provider(openRouter(79_672, true));

        List<ChatResponse> chunks = model(adapter, provider)
                .stream(new Prompt(new UserMessage("hi"), options(adapter, provider, 128_000)))
                .collectList().block(Duration.ofSeconds(10));

        assertThat(chunks).isNotNull();
        assertThat(chunks.stream().map(c -> c.getResult() == null ? "" : String.valueOf(c.getResult().getOutput().getText()))
                .reduce("", String::concat)).contains("Hi!");
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).path("max_tokens").asInt()).isEqualTo(AT_SHARE);
        assertThat(requests.get(1).path("stream").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("a call with no ceiling of its own - the model's - is given the one the credit covers")
    void noCeilingGetsOne() throws Exception {
        OpenRouterAdapter adapter = new OpenRouterAdapter(http, WebClient.builder());
        ResolvedProvider provider = provider(openRouter(79_672, false));

        adapter.buildChatModel(provider, MODEL, Tone.CONCISE, 0)
                .call(new Prompt(new UserMessage("hi"), options(adapter, provider, 0)));

        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).has("max_tokens")).isFalse();
        assertThat(requests.get(1).path("max_tokens").asInt()).isEqualTo(AT_SHARE);
    }

    @Test
    @DisplayName("credit whose share is under FLOOR tokens is spent: the 402 stands, its reason kept, and nothing is sent again")
    void spentCreditStands() throws Exception {
        OpenRouterAdapter adapter = new OpenRouterAdapter(http, WebClient.builder());
        ResolvedProvider provider = provider(openRouter(OpenRouterCreditCeiling.FLOOR, false));

        assertThatThrownBy(() -> model(adapter, provider)
                .call(new Prompt(new UserMessage("hi"), options(adapter, provider, 128_000))))
                .hasMessageContaining("can only afford " + OpenRouterCreditCeiling.FLOOR);
        assertThat(requests).hasSize(1);
    }

    @Test
    @DisplayName("lowered(): only a 402 naming a ceiling, only downward, in the field the request uses")
    void loweredRules() throws Exception {
        String refusal = "{\"error\":{\"message\":\"This request requires more credits, or fewer max_tokens. You requested"
                + " up to 128000 tokens, but can only afford 79672.\",\"code\":402}}";
        byte[] r = refusal.getBytes(StandardCharsets.UTF_8);

        assertThat(json.readTree(OpenRouterCreditCeiling.lowered(body("{\"max_tokens\":128000}"), r))
                .path("max_tokens").asInt()).isEqualTo(AT_SHARE);
        JsonNode completion = json.readTree(OpenRouterCreditCeiling.lowered(body("{\"max_completion_tokens\":128000}"), r));
        assertThat(completion.path("max_completion_tokens").asInt()).isEqualTo(AT_SHARE);
        assertThat(completion.has("max_tokens")).isFalse();
        // Already at or under it: sending again would be refused the same way.
        assertThat(OpenRouterCreditCeiling.lowered(body("{\"max_tokens\":4096}"), r)).isNull();
        // Room for the call before: the whole affordable figure lost the race to a call that spent credit first.
        assertThat(AT_SHARE).isLessThan(79_672).isGreaterThan(70_000);
        // A refusal that names no ceiling (a key, a spent balance), and a body that is not JSON.
        assertThat(OpenRouterCreditCeiling.lowered(body("{\"max_tokens\":128000}"),
                body("{\"error\":{\"message\":\"Insufficient credits\",\"code\":402}}"))).isNull();
        assertThat(OpenRouterCreditCeiling.lowered(body("not json"), r)).isNull();
    }

    @Test
    @DisplayName("an OpenAI-compatible server's 402 is not sent again - the sentence is OpenRouter's")
    void notOffOpenRouter() throws Exception {
        OpenAiCompatibleAdapter adapter = new OpenAiCompatibleAdapter(http, WebClient.builder());
        ResolvedProvider provider = new ResolvedProvider(UUID.randomUUID(), "Box", ProviderProtocol.OPENAI_COMPATIBLE,
                openRouter(79_672, false), "test-key", Map.of(), false);

        assertThatThrownBy(() -> adapter.buildChatModel(provider, MODEL, Tone.CONCISE, 128_000)
                .call(new Prompt(new UserMessage("hi"))));
        assertThat(requests).hasSize(1);
    }

    // -- Harness -----------------------------------------------------------------------------------

    private static byte[] body(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static ResolvedProvider provider(String baseUrl) {
        return new ResolvedProvider(UUID.randomUUID(), "OpenRouter", ProviderProtocol.OPENROUTER, baseUrl, "test-key",
                Map.of(), false);
    }

    private static ToolCallingChatOptions options(LlmProviderAdapter adapter, ResolvedProvider provider, int ceiling) {
        return adapter.buildChatOptions(provider, MODEL, Tone.CONCISE, ceiling, List.of(), PromptCache.FIVE_MINUTES);
    }

    private static ChatModel model(LlmProviderAdapter adapter, ResolvedProvider provider) {
        return adapter.buildChatModel(provider, MODEL, Tone.CONCISE, 128_000);
    }

    /** OpenRouter with a balance that reserves {@code afford} output tokens: a 402 for a larger ceiling or none. */
    private String openRouter(int afford, boolean stream) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody().readAllBytes());
            requests.add(request);
            lines.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " "
                    + exchange.getRequestHeaders().getFirst("Authorization"));
            JsonNode asked = request.has("max_tokens") ? request.get("max_tokens") : request.get("max_completion_tokens");
            int ceiling = asked == null ? 128_000 : asked.asInt();
            int status;
            String type;
            String answer;
            if (ceiling > afford) {
                status = 402;
                type = "application/json";
                answer = "{\"error\":{\"message\":\"This request requires more credits, or fewer max_tokens. You requested"
                        + " up to " + ceiling + " tokens, but can only afford " + afford + ". To increase, visit"
                        + " https://openrouter.ai/settings/credits and add more credits\",\"code\":402,"
                        + "\"metadata\":{\"limit_source\":\"openrouter_credits\"}}}";
            } else {
                status = 200;
                type = stream ? "text/event-stream" : "application/json";
                answer = stream ? STREAM : COMPLETION;
            }
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", type);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
