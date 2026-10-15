package ai.proxyma.core.provider.adapter;

import ai.proxyma.core.common.usage.TokenUsage;
import ai.proxyma.core.provider.usage.TokenUsageExtractor;
import ai.proxyma.core.provider.usage.WireUsage;
import ai.proxyma.core.settings.dto.ChatSettings.Tone;
import ai.proxyma.core.settings.provider.GatewayApi;
import ai.proxyma.core.settings.provider.ProviderProtocol;
import ai.proxyma.core.settings.provider.ResolvedProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WHAT A PROVIDER'S RESPONSE STATED REACHES THE COUNTS (0.29.2 feedback, a probe of a LiteLLM
 * gateway fronting Claude). Against a loopback server replaying that gateway's responses, through
 * the LiteLLM protocol's own clients on each of its two APIs, read the way the gateway reads them:
 * the tap in the stream's context, or open on the thread of a blocking call, and every chunk
 * corrected by it. And native OpenAI reads the same OpenAI-route body as OpenAI counts it - the
 * cache-outside reading is LiteLLM's alone (2026-10-13).
 *
 * <p>Without WireUsage the streamed Anthropic case reads 0 input and 0 cache - Spring AI keeps
 * those from message_start, which this gateway sends as zeros - and the OpenAI-route case reads an
 * uncached input of 0 and no cache write at all.
 */
class WireUsageThroughAdaptersTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    // The gateway's stream for a cached prompt: zeros in message_start, the counts in message_delta,
    // a key with a zero count left out (probe section 4.3).
    private static final String ANTHROPIC_STREAM = String.join("\n",
            "event: message_start",
            "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"claude-sonnet-4-6\",\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":0,\"output_tokens\":0,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0}}}",
            "",
            "event: content_block_start",
            "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
            "",
            "event: content_block_delta",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi!\"}}",
            "",
            "event: content_block_stop",
            "data: {\"type\":\"content_block_stop\",\"index\":0}",
            "",
            "event: message_delta",
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"input_tokens\":0,\"output_tokens\":5,\"cache_read_input_tokens\":10823}}",
            "",
            "event: message_stop",
            "data: {\"type\":\"message_stop\"}",
            "", "");

    @Test
    @DisplayName("a streamed Anthropic call that states its counts only in message_delta is counted in full")
    void anthropicStreamCountsFromMessageDelta() throws Exception {
        ChatModel model = anthropic(serve("text/event-stream", ANTHROPIC_STREAM));
        WireUsage wire = new WireUsage();
        List<ChatResponse> chunks = model.stream(new Prompt(new UserMessage("hi")))
                .map(wire::correct)
                .contextWrite(wire::into)
                .collectList().block(Duration.ofSeconds(10));

        TokenUsage usage = fold(chunks);
        assertThat(usage.cacheReadTokens()).isEqualTo(10823);
        assertThat(usage.outputTokens()).isEqualTo(5);
        assertThat(usage.promptTokensTotal()).as("the context the model was sent").isEqualTo(10823);
        assertThat(usage.uncachedInputTokens()).isZero();
        assertThat(text(chunks)).as("the answer is untouched").isEqualTo("Hi!");
    }

    @Test
    @DisplayName("the one-hour split a stream states is kept, and marking by the request cannot override it")
    void anthropicStreamKeepsTheStatedLifetime() throws Exception {
        String stream = ANTHROPIC_STREAM.replace(
                "\"usage\":{\"input_tokens\":0,\"output_tokens\":5,\"cache_read_input_tokens\":10823}",
                "\"usage\":{\"input_tokens\":3,\"output_tokens\":5,\"cache_creation_input_tokens\":10827,"
                        + "\"cache_creation\":{\"ephemeral_5m_input_tokens\":10827,\"ephemeral_1h_input_tokens\":0}}");
        ChatModel model = anthropic(serve("text/event-stream", stream));
        WireUsage wire = new WireUsage();
        TokenUsage usage = fold(model.stream(new Prompt(new UserMessage("hi")))
                .map(wire::correct).contextWrite(wire::into)
                .collectList().block(Duration.ofSeconds(10)));

        assertThat(usage.cacheWriteTokens()).isEqualTo(10827);
        assertThat(usage.uncachedInputTokens()).isEqualTo(3);
        assertThat(usage.cacheLifetimeStated()).isTrue();
        // The session asked for an hour; the response says five minutes - priced as five minutes.
        assertThat(usage.withLongCacheWrites().longCacheWriteTokens()).isZero();
    }

    @Test
    @DisplayName("with no tap open the stream reads exactly as Spring AI reads it")
    void noTapNoChange() throws Exception {
        ChatModel model = anthropic(serve("text/event-stream", ANTHROPIC_STREAM));
        List<ChatResponse> chunks = model.stream(new Prompt(new UserMessage("hi")))
                .collectList().block(Duration.ofSeconds(10));
        assertThat(fold(chunks).cacheReadTokens()).as("Spring AI alone loses it - which is the defect").isZero();
        assertThat(text(chunks)).isEqualTo("Hi!");
    }

    @Test
    @DisplayName("a blocking Anthropic call keeps the one-hour split its body states")
    void anthropicCallReadsTheBody() throws Exception {
        String body = "{\"id\":\"msg_2\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet-4-6\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"Hi!\"}],\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":0,\"output_tokens\":10,\"cache_creation_input_tokens\":10823,"
                + "\"cache_creation\":{\"ephemeral_5m_input_tokens\":0,\"ephemeral_1h_input_tokens\":10823}}}";
        ChatModel model = anthropic(serve("application/json", body));
        WireUsage wire = new WireUsage();
        ChatResponse response;
        try (WireUsage.Open open = wire.bind()) {
            response = wire.correct(model.call(new Prompt(new UserMessage("hi"))));
        }
        TokenUsage usage = TokenUsageExtractor.extract(response);
        assertThat(usage.cacheWriteTokens()).isEqualTo(10823);
        assertThat(usage.longCacheWriteTokens()).isEqualTo(10823);
        assertThat(usage.cacheLifetimeStated()).isTrue();
        assertThat(response.getResult().getOutput().getText()).isEqualTo("Hi!");
    }

    // The OpenAI route of the same gateway, reading a cache: prompt_tokens EXCLUDES the 10827
    // cached tokens, which the total gives away (probe section 5.2).
    private static final String OPENAI_STREAM = String.join("\n",
            "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"claude-sonnet-4-6\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Hi!\"},\"finish_reason\":null}]}",
            "",
            "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"claude-sonnet-4-6\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}",
            "",
            "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"claude-sonnet-4-6\",\"choices\":[],\"usage\":{\"completion_tokens\":19,\"prompt_tokens\":10,\"total_tokens\":10856,\"prompt_tokens_details\":{\"cached_tokens\":10827,\"cache_write_tokens\":0,\"cache_creation_tokens\":0,\"cache_creation_token_details\":{\"ephemeral_5m_input_tokens\":0,\"ephemeral_1h_input_tokens\":0}}}}",
            "",
            "data: [DONE]",
            "", "");

    @Test
    @DisplayName("an OpenAI-route stream whose prompt excludes the cache is counted as the gateway bills it")
    void openAiStreamWithCacheOutsideThePrompt() throws Exception {
        ChatModel model = openAi(serve("text/event-stream", OPENAI_STREAM));
        WireUsage wire = new WireUsage();
        List<ChatResponse> chunks = model.stream(new Prompt(new UserMessage("hi")))
                .map(wire::correct).contextWrite(wire::into)
                .collectList().block(Duration.ofSeconds(10));

        TokenUsage usage = fold(chunks);
        assertThat(usage.cacheReadTokens()).isEqualTo(10827);
        assertThat(usage.promptTokensTotal()).isEqualTo(10837);
        assertThat(usage.uncachedInputTokens()).as("not clamped to zero").isEqualTo(10);
        assertThat(text(chunks)).isEqualTo("Hi!");
    }

    @Test
    @DisplayName("native OpenAI reads the same body as OpenAI counts it - cached tokens inside the prompt")
    void nativeOpenAiKeepsTheCacheInsideThePrompt() throws Exception {
        ChatModel model = nativeOpenAi(serve("text/event-stream", OPENAI_STREAM));
        WireUsage wire = new WireUsage();
        TokenUsage usage = fold(model.stream(new Prompt(new UserMessage("hi")))
                .map(wire::correct).contextWrite(wire::into)
                .collectList().block(Duration.ofSeconds(10)));

        assertThat(usage.promptTokensTotal()).as("prompt_tokens, as OpenAI states it").isEqualTo(10);
    }

    @Test
    @DisplayName("an OpenAI-route call states its cache write and its lifetime, which Spring AI has no field for")
    void openAiCallReadsTheWrite() throws Exception {
        String body = "{\"id\":\"c2\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"claude-sonnet-4-6\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"Hi!\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"completion_tokens\":10,\"prompt_tokens\":7,\"total_tokens\":10840,\"prompt_tokens_details\":"
                + "{\"cached_tokens\":0,\"cache_write_tokens\":10823,\"cache_creation_tokens\":10823,"
                + "\"cache_creation_token_details\":{\"ephemeral_5m_input_tokens\":0,\"ephemeral_1h_input_tokens\":10823}}}}";
        ChatModel model = openAi(serve("application/json", body));
        WireUsage wire = new WireUsage();
        ChatResponse response;
        try (WireUsage.Open open = wire.bind()) {
            response = wire.correct(model.call(new Prompt(new UserMessage("hi"))));
        }
        TokenUsage usage = TokenUsageExtractor.extract(response);
        assertThat(usage.cacheWriteTokens()).isEqualTo(10823);
        assertThat(usage.longCacheWriteTokens()).isEqualTo(10823);
        assertThat(usage.uncachedInputTokens()).isEqualTo(7);
        assertThat(usage.promptTokensTotal()).isEqualTo(10830);
    }

    // -- Harness -----------------------------------------------------------------------------------

    private String serve(String contentType, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static ChatModel anthropic(String baseUrl) {
        return liteLlm(baseUrl, GatewayApi.ANTHROPIC_MESSAGES);
    }

    private static ChatModel openAi(String baseUrl) {
        return liteLlm(baseUrl, GatewayApi.OPENAI_CHAT);
    }

    private static ChatModel liteLlm(String baseUrl, GatewayApi api) {
        ResolvedProvider provider = new ResolvedProvider(UUID.randomUUID(), "Gateway", ProviderProtocol.LITELLM,
                baseUrl, "test-key", Map.of(), false, api, false);
        SimpleClientHttpRequestFactory http = new SimpleClientHttpRequestFactory();
        return new LiteLlmAdapter(new AnthropicAdapter(http, WebClient.builder()), http, WebClient.builder())
                .buildChatModel(provider, "claude-sonnet-4-6", Tone.CONCISE, 1024);
    }

    private static ChatModel nativeOpenAi(String baseUrl) {
        ResolvedProvider provider = new ResolvedProvider(UUID.randomUUID(), "OpenAI", ProviderProtocol.OPENAI,
                baseUrl, "test-key", Map.of(), false);
        return new OpenAiAdapter(new SimpleClientHttpRequestFactory(), WebClient.builder())
                .buildChatModel(provider, "gpt-x", Tone.CONCISE, 1024);
    }

    // How the gateway and the chat fold a stream's chunks.
    private static TokenUsage fold(List<ChatResponse> chunks) {
        TokenUsage usage = TokenUsage.EMPTY;
        for (ChatResponse chunk : chunks) usage = usage.maxWith(TokenUsageExtractor.extract(chunk));
        return usage;
    }

    private static String text(List<ChatResponse> chunks) {
        return chunks.stream()
                .flatMap(c -> c.getResults().stream())
                .map(Generation::getOutput)
                .filter(Objects::nonNull)
                .map(o -> o.getText() == null ? "" : o.getText())
                .collect(Collectors.joining());
    }
}
