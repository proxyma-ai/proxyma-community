package ai.proxyma.core.provider.adapter;

import ai.proxyma.core.settings.dto.ChatSettings.PromptCache;
import ai.proxyma.core.settings.dto.ChatSettings.Tone;
import ai.proxyma.core.settings.provider.ProviderProtocol;
import ai.proxyma.core.settings.provider.ResolvedProvider;
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
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A REQUEST CARRYING HALF AN EMOJI STILL REACHES ANTHROPIC AS JSON IT ACCEPTS (2026-11-06).
 *
 * <p>A Constellations team working on a Vietnamese YouTube channel failed its scheduled run at the first
 * model call, every hour, with "400 ... The request body is not valid JSON: unexpected end of hex escape
 * at line 1 column 26291". The opening message cut a workspace file at a character count, and the cut fell
 * between the two halves of an emoji. Jackson writes a lone surrogate as a six-character hex escape with
 * no partner; Java's parsers take that, Anthropic's (serde_json) refuses a leading surrogate not followed
 * by a trailing one - in exactly those words.
 *
 * <p>Against a loopback server that parses the posted body as strictly as Anthropic does (UTF-8 that
 * decodes, and every surrogate escape paired), through the adapter's own clients, streamed and blocking,
 * with and without the cache breakpoint rewrite.
 */
class AnthropicLoneSurrogateTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REFUSAL = "The request body is not valid JSON: unexpected end of hex escape";

    // A Vietnamese line with two emoji, and the same cut through the last one: the last char is a lone
    // high surrogate, as a substring at a character index leaves it.
    private static final String WHOLE = "K\u00eanh s\u00e1o tr\u00fac \uD83C\uDF8B c\u1ea3m \u01a1n b\u1ea1n \uD83D\uDE0A";
    private static final String CUT_HIGH = WHOLE.substring(0, WHOLE.length() - 1);
    // ... and the other half alone: a lone low surrogate at the start of a text.
    private static final String CUT_LOW = WHOLE.substring(WHOLE.length() - 1) + " xin ch\u00e0o";

    private HttpServer server;
    private final AtomicReference<byte[]> sent = new AtomicReference<>();

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    @DisplayName("a stream with a cut emoji in system, user and tool texts is accepted, with a breakpoint to move")
    void streamWithBreakpoint() throws Exception {
        AnthropicAdapter adapter = adapter();
        ChatModel model = model(adapter);
        model.stream(new Prompt(cutMessages(), adapter.buildChatOptions("m", Tone.CONCISE, 1024, List.of(), PromptCache.FIVE_MINUTES)))
                .blockLast(Duration.ofSeconds(10));
        assertAccepted();
    }

    @Test
    @DisplayName("a stream with nothing for the cache rewrite to move is accepted too")
    void streamWithoutBreakpoint() throws Exception {
        AnthropicAdapter adapter = adapter();
        model(adapter).stream(new Prompt(cutMessages())).blockLast(Duration.ofSeconds(10));
        assertAccepted();
    }

    @Test
    @DisplayName("a blocking call is accepted")
    void blockingCall() throws Exception {
        AnthropicAdapter adapter = adapter();
        model(adapter).call(new Prompt(cutMessages()));
        assertAccepted();
    }

    @Test
    @DisplayName("the strict check refuses what Jackson writes for a lone surrogate, and accepts a pair")
    void theCheckIsAnthropics() throws Exception {
        assertThat(strictProblem(JSON.writeValueAsBytes(Map.of("t", CUT_HIGH)))).contains("unexpected end of hex escape");
        assertThat(strictProblem(JSON.writeValueAsBytes(Map.of("t", CUT_LOW)))).contains("lone trailing");
        assertThat(strictProblem(JSON.writeValueAsBytes(Map.of("t", WHOLE)))).isNull();
    }

    @Test
    @DisplayName("a body with no unpaired surrogate is passed on as the same bytes")
    void untouchedWhenWellFormed() throws Exception {
        byte[] plain = JSON.writeValueAsBytes(Map.of("messages", List.of(Map.of("role", "user", "content", "K\u00eanh s\u00e1o"))));
        assertThat(WellFormedBody.repair(plain)).isSameAs(plain);
        byte[] paired = JSON.writeValueAsBytes(Map.of("t", WHOLE));
        assertThat(WellFormedBody.repair(paired)).isSameAs(paired);
        byte[] notJson = "not json \\ud83c".getBytes(StandardCharsets.UTF_8);
        assertThat(WellFormedBody.repair(notJson)).isSameAs(notJson);
        // A backslash the text holds, followed by "ud83c", is an escaped backslash and letters - not an escape.
        byte[] escapedBackslash = JSON.writeValueAsBytes(Map.of("t", "C:\\ud83c and \\\\ud83c"));
        assertThat(WellFormedBody.repair(escapedBackslash)).isSameAs(escapedBackslash);
    }

    @Test
    @DisplayName("a lone surrogate in a tool's input or a field name is repaired too")
    void everyStringIsRepaired() throws Exception {
        byte[] body = JSON.writeValueAsBytes(Map.of("tools", List.of(Map.of("description", CUT_HIGH)),
                "input", Map.of(CUT_LOW, List.of(CUT_HIGH, 1, true))));
        assertThat(strictProblem(body)).isNotNull();
        byte[] repaired = WellFormedBody.repair(body);
        assertThat(strictProblem(repaired)).isNull();
        assertThat(JSON.readTree(repaired).path("tools").path(0).path("description").asText())
                .isEqualTo(WHOLE.substring(0, WHOLE.length() - 2) + "\uFFFD");
        assertThat(JSON.readTree(repaired).path("input").path("\uFFFD xin ch\u00e0o").path(1).asInt()).isEqualTo(1);
    }

    // What reached the server is valid by Anthropic's rules, each half emoji became U+FFFD, and the whole
    // emoji survived.
    private void assertAccepted() throws Exception {
        byte[] body = sent.get();
        assertThat(body).as("the request reached the server").isNotNull();
        assertThat(strictProblem(body)).as("Anthropic's parser would refuse this body").isNull();
        String all = JSON.readTree(body).toString();
        assertThat(all).as("each half emoji became the replacement character").contains("\uFFFD");
        assertThat(all).as("a whole emoji is left as it was").contains("\uD83C\uDF8B");
    }

    private static List<Message> cutMessages() {
        return List.of(
                new SystemMessage("You are the Comment Scout. " + CUT_HIGH),
                new UserMessage("## shared/knowledge/VOICE.md\n" + CUT_HIGH + "\n[... cut here - read it whole]"),
                AssistantMessage.builder().content(CUT_LOW)
                        .toolCalls(List.of(new AssistantMessage.ToolCall("t1", "function", "readWorkspaceFile", "{\"path\":\"NEXT.md\"}")))
                        .build(),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse("t1", "readWorkspaceFile", CUT_HIGH)))
                        .build(),
                new UserMessage(CUT_LOW + " " + CUT_HIGH));
    }

    // A loopback Anthropic: a body its parser refuses gets the 400 Anthropic sends, anything else a short
    // stream or a whole message.
    private AnthropicAdapter adapter() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            sent.set(body);
            String problem = strictProblem(body);
            boolean stream = problem == null && new String(body, StandardCharsets.UTF_8).contains("\"stream\":true");
            String reply = problem != null
                    ? "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"" + REFUSAL + "\"}}"
                    : stream ? SSE
                    : "{\"id\":\"m\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],"
                    + "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", stream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(problem != null ? 400 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new AnthropicAdapter(new SimpleClientHttpRequestFactory(), WebClient.builder());
    }

    private static final String SSE = String.join("\n",
            "event: message_start",
            "data: {\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"m\",\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}",
            "",
            "event: message_delta",
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":1}}",
            "",
            "event: message_stop",
            "data: {\"type\":\"message_stop\"}",
            "", "");

    private ChatModel model(AnthropicAdapter adapter) {
        ResolvedProvider provider = new ResolvedProvider(UUID.randomUUID(), "S", ProviderProtocol.ANTHROPIC,
                "http://127.0.0.1:" + server.getAddress().getPort(), "k", Map.of(), false);
        return adapter.buildChatModel(provider, "m", Tone.CONCISE, 1024);
    }

    /**
     * Why a JSON parser as strict as Anthropic's (serde_json, validating UTF-8 strings) would refuse this
     * body, or null: bytes that are not UTF-8, a leading surrogate escape not followed by a trailing one
     * ("unexpected end of hex escape"), or a trailing one alone; then anything Jackson itself refuses.
     */
    static String strictProblem(byte[] body) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException e) {
            return "invalid UTF-8";
        }
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!inString) {
                if (c == '"') inString = true;
                continue;
            }
            if (c == '"') {
                inString = false;
            } else if (c == '\\') {
                char e = text.charAt(++i);
                if (e != 'u') continue;
                int n = hex(text, i + 1);
                i += 4;
                if (n >= 0xDC00 && n <= 0xDFFF) return "lone trailing surrogate at " + i;
                if (n >= 0xD800 && n <= 0xDBFF) {
                    if (i + 2 >= text.length() || text.charAt(i + 1) != '\\' || text.charAt(i + 2) != 'u') {
                        return "unexpected end of hex escape at " + i;
                    }
                    int n2 = hex(text, i + 3);
                    if (n2 < 0xDC00 || n2 > 0xDFFF) return "lone leading surrogate at " + i;
                    i += 6;
                }
            }
        }
        try {
            JSON.readTree(text);
        } catch (Exception e) {
            return "not JSON: " + e.getMessage();
        }
        return null;
    }

    private static int hex(String s, int at) {
        return Integer.parseInt(s.substring(at, at + 4), 16);
    }
}
