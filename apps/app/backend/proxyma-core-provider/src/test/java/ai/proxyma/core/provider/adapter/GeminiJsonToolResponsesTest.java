package ai.proxyma.core.provider.adapter;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every tool result reaching Gemini has to be JSON, and one that is not must arrive as the text the
 * tool wrote - both of which went wrong in production.
 *
 * <p>The crash: {@code grep} reporting "No matches for ..." killed the turn with "Failed to parse
 * JSON", because Spring AI's Gemini bridge parses every tool result and has no fallback. The silent
 * one, found while fixing it: a {@code read} result beginning {@code "     1\t"} parsed as the
 * NUMBER 1, so Gemini received {@code {"result": 1}} and the file was gone with no error anywhere.
 *
 * <p>The strings below are the shapes those two tools actually produce. Proved by breaking the
 * class: dropping {@code FAIL_ON_TRAILING_TOKENS} fails {@code jsonWithTrailingTextIsText}, and
 * passing any JSON value through rather than only objects and arrays fails
 * {@code bareScalarIsText}. {@code lineNumberedFileIsText} survives either break alone - a leading
 * {@code 1} is rejected by both guards - and fails only if both go.
 */
class GeminiJsonToolResponsesTest {

    /** Strict on purpose: a lenient reader would accept the very output this class exists to stop. */
    private static final ObjectMapper STRICT = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** The delegate Gemini model, reduced to remembering what it was handed. */
    private static final class Capturing implements ChatModel {
        final List<Prompt> seen = new ArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            seen.add(prompt);
            return null;
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            seen.add(prompt);
            return Flux.empty();
        }
    }

    private static Prompt promptWithToolResults(String... results) {
        List<ToolResponse> responses = new ArrayList<>();
        for (int i = 0; i < results.length; i++) {
            responses.add(new ToolResponse("call-" + i, i % 2 == 0 ? "grep" : "read", results[i]));
        }
        return new Prompt(List.of(
                new UserMessage("find the source field"),
                ToolResponseMessage.builder().responses(responses).build()));
    }

    /** What the delegate received in place of the first tool result, after one call. */
    private static String sentToGemini(String... results) {
        return sentToGeminiAll(results).get(0);
    }

    private static List<String> sentToGeminiAll(String... results) {
        Capturing delegate = new Capturing();
        new GeminiJsonToolResponses(delegate).call(promptWithToolResults(results));
        ToolResponseMessage message = (ToolResponseMessage) delegate.seen.get(0).getInstructions().get(1);
        return message.getResponses().stream().map(ToolResponse::responseData).toList();
    }

    /** The text the model will read back, which must be exactly what the tool wrote. */
    private static String decodedText(String json) throws Exception {
        JsonNode node = STRICT.readTree(json);
        assertTrue(node.isTextual(), "expected a JSON string, got: " + json);
        return node.textValue();
    }

    @Test
    @DisplayName("the grep message that killed the turn arrives as a JSON string holding the same text")
    void grepNoMatchIsWrapped() throws Exception {
        String grep = "No matches for '\"source\":' in searchIndexedSources_eecb4e03.txt (80 lines).";
        assertEquals(grep, decodedText(sentToGemini(grep)));
    }

    @Test
    @DisplayName("a line-numbered file is text, not the number on its first line")
    void lineNumberedFileIsText() throws Exception {
        String read = "     1\tfirst line\n     2\t{\"key\": true}\n     3\tlast line\n";
        assertEquals(read, decodedText(sentToGemini(read)));
    }

    @Test
    @DisplayName("a result that is a bare JSON scalar is still text")
    void bareScalarIsText() throws Exception {
        assertEquals("42", decodedText(sentToGemini("42")));
        assertEquals("true", decodedText(sentToGemini("true")));
    }

    @Test
    @DisplayName("a JSON object or array followed by more text is text")
    void jsonWithTrailingTextIsText() throws Exception {
        String mixed = "{\"a\": 1}\n(truncated)";
        assertEquals(mixed, decodedText(sentToGemini(mixed)));
    }

    @Test
    @DisplayName("a null or empty result becomes the JSON string \"\"")
    void nullAndEmptyBecomeEmptyString() {
        assertEquals("\"\"", sentToGemini((String) null));
        assertEquals("\"\"", sentToGemini(""));
    }

    @Test
    @DisplayName("complete JSON objects and arrays pass through byte for byte, beside a rewritten sibling")
    void jsonPassesThroughUntouched() {
        String object = "{ \"results\" : [ {\"source\": \"a.md\"} ] }";
        String array = "[1, 2, 3]";
        List<String> sent = sentToGeminiAll(object, "(file is empty)", array);
        assertEquals(object, sent.get(0));
        assertEquals("\"(file is empty)\"", sent.get(1));
        assertEquals(array, sent.get(2));
    }

    @Test
    @DisplayName("a prompt needing no rewrite reaches the model as the same instance")
    void allJsonPromptIsNotCopied() {
        Capturing delegate = new Capturing();
        Prompt prompt = promptWithToolResults("{\"ok\": true}", "[]");
        new GeminiJsonToolResponses(delegate).call(prompt);
        assertSame(prompt, delegate.seen.get(0));
    }

    @Test
    @DisplayName("streaming is rewritten the same way as a blocking call")
    void streamIsRewrittenToo() throws Exception {
        Capturing delegate = new Capturing();
        new GeminiJsonToolResponses(delegate).stream(promptWithToolResults("Error: file not found"));
        List<Message> sent = delegate.seen.get(0).getInstructions();
        String data = ((ToolResponseMessage) sent.get(1)).getResponses().get(0).responseData();
        assertEquals("Error: file not found", decodedText(data));
    }
}
