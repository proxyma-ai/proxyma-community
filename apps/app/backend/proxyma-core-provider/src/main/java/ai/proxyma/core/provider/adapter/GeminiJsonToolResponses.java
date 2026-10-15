package ai.proxyma.core.provider.adapter;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * Wraps a Gemini {@link ChatModel} so every tool result reaching it is valid JSON.
 *
 * <h2>The failure this exists for</h2>
 *
 * Gemini's wire format has no equivalent of the plain string OpenAI and Anthropic accept as a tool
 * result: a {@code functionResponse} part carries a structured {@code response} OBJECT. Spring AI
 * bridges that in {@code GoogleGenAiChatModel.parseJsonToMap} (verified against the 1.1.7 jar),
 * which reads the tool's {@code responseData} with Jackson and, on any parse failure, throws
 * {@code RuntimeException("Failed to parse JSON: " + data, cause)}. It never falls back.
 *
 * <p>Two of this codebase's sixty tools return raw text on purpose - {@code grep}
 * (SearchTextFileTool) and {@code read} (ReadTextFileTool), both via {@code RawTextResultConverter},
 * so an offloaded file's real newlines survive instead of being re-escaped to a single {@code \n}
 * -laden JSON line for the model to read. Every other provider takes that string verbatim. Gemini
 * choked on the first one it saw, which in practice was the message
 *
 * <pre>Failed to parse JSON: No matches for '"source":' in searchIndexedSources_....txt (80 lines).</pre>
 *
 * <p>- a grep that simply found nothing, killing the whole turn.
 *
 * <h2>Why it is fixed HERE and not in the tools</h2>
 *
 * Making the two tools return JSON would fix Gemini by regressing every other provider: the point
 * of the raw-text converter is that the model sees a file's lines as lines. Gemini's requirement is
 * a property of one protocol, so it is one adapter's job to satisfy it - the same rule
 * {@code ProviderProtocol} already applies to embedding and rerank support.
 *
 * <p>It also cannot live in the tools for a second reason: a tool has no idea which provider is
 * about to read its output. The same conversation can move between instances between turns.
 *
 * <h2>What it does</h2>
 *
 * A result that is already a COMPLETE JSON object or array is passed through UNTOUCHED, so the 58
 * map-returning tools are byte-for-byte unaffected and keep their object shape (Gemini maps a JSON
 * object straight onto {@code response}). Everything else is wrapped by serialising it as a JSON
 * string, which
 * {@code parseJsonToMap} then hands back as {@code {"result": "<the text>"}} - the same envelope it
 * puts a JSON array or scalar in. The text itself is unchanged, so the model reads what the tool
 * wrote.
 *
 * <p>NOT verified against a live Gemini endpoint - only against the decompiled 1.1.7 bridge whose
 * behaviour it is written to satisfy.
 */
final class GeminiJsonToolResponses implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(GeminiJsonToolResponses.class);
    // FAIL_ON_TRAILING_TOKENS is the whole point of this mapper, not a detail. Jackson stops at
    // the first complete value by default, so a 'read' result beginning "     1	first line"
    // parses "1" as a JSON number and reports success with the rest of the file ignored. Gemini
    // then receives {"result": 1} and the file the model asked for is GONE - a silent wrong
    // answer, which is worse than the crash this class was written for. Confirmed against
    // GoogleGenAiChatModel.parseJsonToMap, which uses a default mapper and has the same hole.
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final ChatModel delegate;

    GeminiJsonToolResponses(ChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return delegate.call(rewrite(prompt));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(rewrite(prompt));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    /**
     * A copy of the prompt whose tool responses are all JSON, or the prompt itself when none needed
     * changing - which is the common case, so a turn using only map-returning tools allocates
     * nothing here.
     */
    private static Prompt rewrite(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        List<Message> rewritten = null;

        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (!(message instanceof ToolResponseMessage toolResponses)) continue;

            ToolResponseMessage fixed = fix(toolResponses);
            if (fixed == null) continue;

            if (rewritten == null) rewritten = new ArrayList<>(messages);
            rewritten.set(i, fixed);
        }

        return rewritten == null ? prompt : new Prompt(rewritten, prompt.getOptions());
    }

    /** Null when every response in this message is already JSON and the message can stand as it is. */
    private static ToolResponseMessage fix(ToolResponseMessage message) {
        List<ToolResponse> responses = message.getResponses();
        List<ToolResponse> rewritten = null;

        for (int i = 0; i < responses.size(); i++) {
            ToolResponse response = responses.get(i);
            if (isJson(response.responseData())) continue;

            if (rewritten == null) rewritten = new ArrayList<>(responses);
            rewritten.set(i, new ToolResponse(response.id(), response.name(),
                    asJsonString(response.responseData())));
        }

        if (rewritten == null) return null;
        return ToolResponseMessage.builder()
                .responses(rewritten)
                .metadata(message.getMetadata())
                .build();
    }

    /**
     * Whether this result can be handed to Gemini as it stands: a COMPLETE JSON object or array,
     * with nothing after it.
     *
     * <p>Both halves of that are load-bearing. "Complete" is what stops a line-numbered file being
     * mistaken for the number on its first line (see the mapper above). "Object or array" is what
     * the 58 map-returning tools produce and what Gemini's response field is shaped for; a bare
     * scalar is not something any tool here returns, and treating one as structured would only
     * re-open the same ambiguity for a file that happens to start with a digit or the word true.
     *
     * <p>Null is not passed through either - parseJsonToMap would hand Jackson a null and fail the
     * same way. It becomes the JSON string "" below, which is what an empty tool result means.
     */
    private static boolean isJson(String data) {
        if (data == null) return false;
        try {
            JsonNode node = JSON.readTree(data);
            return node != null && (node.isObject() || node.isArray());
        } catch (Exception e) {
            return false;
        }
    }

    private static String asJsonString(String text) {
        try {
            return JSON.writeValueAsString(text == null ? "" : text);
        } catch (Exception e) {
            // writeValueAsString on a String cannot fail, but a tool result must never be the
            // reason a turn dies - which is the entire bug this class exists to close.
            log.warn("[Proxy] Could not JSON-encode a tool result for Gemini: {}", e.getMessage());
            return "\"\"";
        }
    }
}
