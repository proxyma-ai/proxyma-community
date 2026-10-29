package ai.proxyma.core.provider.adapter;

import ai.proxyma.core.settings.dto.ChatSettings.Tone;
import ai.proxyma.core.settings.provider.ProviderProtocol;
import ai.proxyma.core.settings.provider.ResolvedProvider;
import ai.proxyma.core.system.util.Failures;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * An Ollama refusal reaches the user with Ollama's reason (2026-10-27): a model without tool support
 * listed fine under Test and answered every chat with "400 Bad Request from POST .../api/chat", and
 * nothing else - the reason is the response BODY, which a streamed error's message leaves out.
 *
 * <p>Against a real HTTP server on loopback answering as Ollama does, through the adapter's own
 * streaming client, and worded by {@link Failures#describe} - what the chat error and its log line
 * both say.
 */
class OllamaRefusalTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private String refusalSays(String ollamaBody) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = ollamaBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        OllamaAdapter adapter = new OllamaAdapter(RestClient.builder().requestFactory(new SimpleClientHttpRequestFactory()),
                WebClient.builder());
        ResolvedProvider provider = new ResolvedProvider(UUID.randomUUID(), "Ollama", ProviderProtocol.OLLAMA,
                "http://127.0.0.1:" + server.getAddress().getPort(), null, Map.of(), false);
        ChatModel model = adapter.buildChatModel(provider, "gemma3:4b", Tone.CONCISE, 1024);
        Throwable failure = catchThrowable(() -> model.stream(new Prompt(new UserMessage("hi")))
                .blockLast(Duration.ofSeconds(10)));
        assertThat(failure).isNotNull();
        return Failures.describe(failure);
    }

    @Test
    @DisplayName("a model without tool support is named, and what to pick instead")
    void modelWithoutTools() throws Exception {
        String said = refusalSays("{\"error\":\"registry.ollama.ai/library/gemma3:4b does not support tools\"}");
        assertThat(said).startsWith("This model cannot use tools")
                .contains("gemma3:4b does not support tools")
                .contains("400");
    }

    @Test
    @DisplayName("an embedding model picked for chat says so")
    void embeddingModel() throws Exception {
        String said = refusalSays("{\"error\":\"\\\"nomic-embed-text\\\" does not support chat\"}");
        assertThat(said).startsWith("This is an embedding model").contains("\"nomic-embed-text\" does not support chat");
    }
}
