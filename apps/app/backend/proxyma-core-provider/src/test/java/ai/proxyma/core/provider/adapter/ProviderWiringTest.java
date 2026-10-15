package ai.proxyma.core.provider.adapter;

import ai.proxyma.core.settings.dto.ChatSettings.Tone;
import ai.proxyma.core.settings.provider.ProviderProtocol;
import ai.proxyma.core.settings.provider.ResolvedProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * How each adapter's clients are put together, read off what a loopback server receives.
 *
 * <ul>
 *   <li><b>Ollama streams with the instance's own client</b> (2026-10-14). OllamaApi built its
 *       streaming WebClient itself, so a streamed turn carried none of the instance's custom
 *       headers - behind an authenticating reverse proxy, every chat turn was a 401 while the test
 *       button and embeddings worked.</li>
 *   <li><b>OpenAI's reasoning models are sent what OpenAI documents for them</b>: no temperature or
 *       top_p, and the ceiling as max_completion_tokens. Every call to gpt-5-mini or o4-mini was a
 *       400.</li>
 *   <li><b>A blocking error keeps its status and headers</b>, so a 429's Retry-After reaches
 *       ModelCall - Spring AI's own handler kept the status as text and dropped the headers.</li>
 *   <li><b>An SDK's error status is raised the way every other provider's is</b> - Gemini's and
 *       Azure's were invisible to ModelCall, so their rate limits were never waited out.</li>
 *   <li><b>Anthropic's model list is followed past its first page</b> of twenty.</li>
 * </ul>
 */
class ProviderWiringTest {

    private record Request(String path, String query, Map<String, String> headers, String body) {}

    private final ObjectMapper json = new ObjectMapper();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    // -- Ollama ------------------------------------------------------------------------------------

    private static final String OLLAMA_STREAM = String.join("\n",
            "{\"model\":\"llama3\",\"created_at\":\"2026-10-14T00:00:00Z\",\"message\":{\"role\":\"assistant\",\"content\":\"Hi\"},\"done\":false}",
            "{\"model\":\"llama3\",\"created_at\":\"2026-10-14T00:00:01Z\",\"message\":{\"role\":\"assistant\",\"content\":\"!\"},\"done\":false}",
            "{\"model\":\"llama3\",\"created_at\":\"2026-10-14T00:00:02Z\",\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":5,\"eval_count\":2}",
            "");

    @Test
    @DisplayName("Ollama: a STREAMED call carries the instance's custom headers, as the blocking ones do")
    void ollamaStreamsWithTheInstancesHeaders() throws Exception {
        String url = serve(ex -> reply(ex, 200, "application/x-ndjson", OLLAMA_STREAM));
        ResolvedProvider provider = new ResolvedProvider(UUID.randomUUID(), "Ollama", ProviderProtocol.OLLAMA,
                url, null, Map.of("Authorization", "Bearer relay-token"), false);
        ChatModel model = new OllamaAdapter(RestClient.builder(), WebClient.builder())
                .buildChatModel(provider, "llama3", Tone.CONCISE, 1024);

        List<ChatResponse> chunks = model.stream(new Prompt(new UserMessage("hi")))
                .collectList().block(Duration.ofSeconds(10));

        assertThat(chunks).isNotEmpty();
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).path()).isEqualTo("/api/chat");
        assertThat(requests.get(0).headers().get("authorization")).isEqualTo("Bearer relay-token");
    }

    // -- OpenAI's reasoning models -----------------------------------------------------------------

    private static final String COMPLETION = "{\"id\":\"c1\",\"object\":\"chat.completion\",\"created\":1,"
            + "\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
            + "\"content\":\"Hi!\"},\"finish_reason\":\"stop\"}],\"usage\":{\"completion_tokens\":2,\"prompt_tokens\":5,"
            + "\"total_tokens\":7}}";

    @Test
    @DisplayName("native OpenAI, a reasoning model: no temperature or top_p, the ceiling as max_completion_tokens")
    void reasoningModelsGetWhatOpenAiDocuments() throws Exception {
        String url = serve(ex -> reply(ex, 200, "application/json", COMPLETION));
        OpenAiAdapter adapter = new OpenAiAdapter(new SimpleClientHttpRequestFactory(), WebClient.builder());
        for (String model : List.of("gpt-5-mini", "o4-mini")) {
            ResolvedProvider provider = provider(ProviderProtocol.OPENAI, url);
            // A generic call (titles, condensation) and a tuned one (the agent) alike.
            adapter.buildChatModel(provider, model, Tone.NORMAL, 4096).call(new Prompt(new UserMessage("hi")));
            ToolCallingChatOptions options = adapter.buildChatOptions(provider, model, Tone.NORMAL, 4096, List.of(), null);
            adapter.buildChatModel(provider, model, Tone.NORMAL, 4096).call(new Prompt(new UserMessage("hi"), options));
        }
        assertThat(requests).hasSize(4).allSatisfy(r -> {
            JsonNode body = json.readTree(r.body());
            assertThat(body.has("temperature")).as(r.body()).isFalse();
            assertThat(body.has("top_p")).as(r.body()).isFalse();
            assertThat(body.has("max_tokens")).as(r.body()).isFalse();
            assertThat(body.path("max_completion_tokens").asInt()).as(r.body()).isEqualTo(4096);
        });
    }

    @Test
    @DisplayName("native OpenAI, any other model - and an OpenAI-compatible server, whatever the name - keep the tone")
    void otherModelsKeepTheTone() throws Exception {
        String url = serve(ex -> reply(ex, 200, "application/json", COMPLETION));
        new OpenAiAdapter(new SimpleClientHttpRequestFactory(), WebClient.builder())
                .buildChatModel(provider(ProviderProtocol.OPENAI, url), "gpt-4.1", Tone.NORMAL, 4096)
                .call(new Prompt(new UserMessage("hi")));
        new OpenAiCompatibleAdapter(new SimpleClientHttpRequestFactory(), WebClient.builder())
                .buildChatModel(provider(ProviderProtocol.OPENAI_COMPATIBLE, url), "gpt-5-mini", Tone.NORMAL, 4096)
                .call(new Prompt(new UserMessage("hi")));

        assertThat(requests).hasSize(2).allSatisfy(r -> {
            JsonNode body = json.readTree(r.body());
            assertThat(body.path("temperature").asDouble()).isEqualTo(Tone.NORMAL.getTemperature());
            assertThat(body.path("max_tokens").asInt()).isEqualTo(4096);
        });
    }

    @Test
    @DisplayName("which ids are OpenAI reasoning models")
    void reasoningModelIds() {
        assertThat(List.of("o1", "o3-mini", "o4-mini", "gpt-5", "gpt-5-mini", "gpt-5.1", "GPT-5-nano"))
                .allMatch(ReasoningModels::isReasoning);
        assertThat(List.of("gpt-4.1", "gpt-4o-mini", "gpt-5-chat-latest", "omni-moderation", "llama3", ""))
                .noneMatch(ReasoningModels::isReasoning);
    }

    // -- Errors keep their status and headers ------------------------------------------------------

    @Test
    @DisplayName("a blocking 429 reaches the caller typed, with its Retry-After")
    void blockingRateLimitKeepsRetryAfter() throws Exception {
        String url = serve(ex -> {
            ex.getResponseHeaders().add("Retry-After", "7");
            reply(ex, 429, "application/json", "{\"error\":{\"message\":\"slow down\"}}");
        });
        ChatModel model = new OpenAiAdapter(new SimpleClientHttpRequestFactory(), WebClient.builder())
                .buildChatModel(provider(ProviderProtocol.OPENAI, url), "gpt-4.1", Tone.CONCISE, 1024);

        assertThatThrownBy(() -> model.call(new Prompt(new UserMessage("hi"))))
                .isInstanceOfSatisfying(HttpClientErrorException.TooManyRequests.class, e -> {
                    assertThat(e.getResponseHeaders().getFirst("Retry-After")).isEqualTo("7");
                    assertThat(e.getMessage()).startsWith("429").contains("slow down");
                });
        // And it is not retried underneath: ModelCall owns that, with the provider's own wait.
        assertThat(requests).hasSize(1);
    }

    @Test
    @DisplayName("a Gemini SDK failure is raised with its status, wrapped the way Spring AI wraps it")
    void geminiStatusIsRaised() {
        ChatModel failing = failingWith(new RuntimeException("Failed to generate content",
                new com.google.genai.errors.ApiException(429, "RESOURCE_EXHAUSTED", "Quota exceeded")));
        ChatModel model = new SdkStatusChatModel(failing);

        assertThatThrownBy(() -> model.call(new Prompt(new UserMessage("hi"))))
                .isInstanceOfSatisfying(WebClientResponseException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(429);
                    assertThat(e.getMessage()).contains("Quota exceeded");
                });
        assertThatThrownBy(() -> model.stream(new Prompt(new UserMessage("hi"))).blockLast())
                .isInstanceOfSatisfying(WebClientResponseException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(429));
    }

    @Test
    @DisplayName("an Azure SDK failure is raised with its status and its headers")
    void azureStatusIsRaised() {
        com.azure.core.http.HttpResponse response = mock(com.azure.core.http.HttpResponse.class);
        when(response.getStatusCode()).thenReturn(429);
        when(response.getHeaders()).thenReturn(new com.azure.core.http.HttpHeaders().set("Retry-After", "3"));
        ChatModel model = new SdkStatusChatModel(failingWith(
                new com.azure.core.exception.HttpResponseException("Too many requests", response)));

        assertThatThrownBy(() -> model.call(new Prompt(new UserMessage("hi"))))
                .isInstanceOfSatisfying(WebClientResponseException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(429);
                    assertThat(e.getHeaders().getFirst("Retry-After")).isEqualTo("3");
                });
    }

    @Test
    @DisplayName("a failure with no status is passed on as it was")
    void otherFailuresPassThrough() {
        IllegalStateException plain = new IllegalStateException("no key");
        ChatModel model = new SdkStatusChatModel(failingWith(plain));
        assertThatThrownBy(() -> model.call(new Prompt(new UserMessage("hi")))).isSameAs(plain);
    }

    // -- Anthropic's model list --------------------------------------------------------------------

    @Test
    @DisplayName("Anthropic: the model list is asked for in full and followed while it says there is more")
    void anthropicModelListIsFollowed() throws Exception {
        String url = serve(ex -> {
            String query = ex.getRequestURI().getRawQuery();
            String body = query != null && query.contains("after_id=claude-b")
                    ? "{\"data\":[{\"id\":\"claude-c\"}],\"has_more\":false,\"last_id\":\"claude-c\"}"
                    : "{\"data\":[{\"id\":\"claude-b\"},{\"id\":\"claude-a\"}],\"has_more\":true,\"last_id\":\"claude-b\"}";
            reply(ex, 200, "application/json", body);
        });
        ResolvedProvider provider = provider(ProviderProtocol.ANTHROPIC, url);

        List<String> models = new AnthropicAdapter(new SimpleClientHttpRequestFactory(), WebClient.builder())
                .listModels(provider);

        assertThat(models).containsExactly("claude-a", "claude-b", "claude-c");
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).query()).contains("limit=1000");
        assertThat(requests.get(0).headers().get("x-api-key")).isEqualTo("test-key");
    }

    // -- Harness -----------------------------------------------------------------------------------

    private static ChatModel failingWith(RuntimeException failure) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw failure;
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.error(failure);
            }
        };
    }

    private static ResolvedProvider provider(ProviderProtocol protocol, String baseUrl) {
        return new ResolvedProvider(UUID.randomUUID(), protocol.displayName(), protocol, baseUrl, "test-key",
                Map.of(), false);
    }

    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private String serve(Handler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            Map<String, String> headers = new java.util.HashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.get(0)));
            requests.add(new Request(exchange.getRequestURI().getPath(), exchange.getRequestURI().getRawQuery(),
                    headers, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            handler.handle(exchange);
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void reply(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
