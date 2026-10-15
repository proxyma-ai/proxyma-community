package ai.proxyma.core.provider.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Thinking is never answer text, on any protocol - and the answer is never lost to it.
 *
 * <p>The shapes are the ones Spring AI 1.1.7 actually produces, read from its source: Anthropic's
 * thinking as a generation of its own marked by {@code signature} / {@code data}, OpenAI-compatible
 * {@code reasoningContent} message metadata, Ollama's {@code thinking} generation metadata, and a
 * model writing {@code <think>} into the text itself.
 */
class ModelThinkingTest {

    @Test
    @DisplayName("Anthropic: the thinking generation is thinking, and the answer is the OTHER generation - not the first")
    void anthropicThinkingIsItsOwnGeneration() {
        ChatResponse response = new ChatResponse(List.of(
                withProps("I should look at the ledger.", Map.of("signature", "sig")),
                withProps("The ledger balances.", Map.of())));
        assertEquals("The ledger balances.", ModelThinking.answerText(response));
        assertTrue(ModelThinking.hasThinking(response));
    }

    @Test
    @DisplayName("Anthropic: an empty (omitted) thinking block still counts as thinking and leaves the answer alone")
    void omittedThinkingStillSignals() {
        Map<String, Object> props = new HashMap<>();
        props.put("signature", null);
        ModelThinking.Piece piece = new ModelThinking.Splitter().accept(
                new ChatResponse(List.of(withProps("", props))));
        assertTrue(piece.thinkingSignal());
        assertEquals("", piece.answer());
    }

    @Test
    @DisplayName("Anthropic: redacted thinking (data, no text) is thinking")
    void redactedThinking() {
        assertTrue(ModelThinking.isThinkingGeneration(withProps(null, Map.of("data", "opaque"))));
        assertFalse(ModelThinking.isThinkingGeneration(withProps("an answer", Map.of("data", "x"))));
    }

    @Test
    @DisplayName("OpenAI-compatible: reasoningContent is thinking, the text beside it is the answer")
    void openAiReasoningContent() {
        ModelThinking.Piece piece = new ModelThinking.Splitter().accept(new ChatResponse(List.of(
                withProps("Hello", Map.of("reasoningContent", "user greets me")))));
        assertEquals("Hello", piece.answer());
        assertEquals("user greets me", piece.thinking());
        assertTrue(piece.thinkingSignal());
    }

    @Test
    @DisplayName("OpenAI-compatible: the empty reasoningContent Spring AI puts on EVERY chunk is not a signal")
    void emptyReasoningContentIsNothing() {
        ModelThinking.Piece piece = new ModelThinking.Splitter().accept(new ChatResponse(List.of(
                withProps("Hello", Map.of("reasoningContent", "")))));
        assertFalse(piece.thinkingSignal());
        assertEquals("Hello", piece.answer());
    }

    @Test
    @DisplayName("Ollama: generation metadata `thinking` is thinking")
    void ollamaThinking() {
        Generation g = new Generation(new AssistantMessage("Answer."),
                ChatGenerationMetadata.builder().metadata("thinking", "hmm").build());
        assertEquals("hmm", ModelThinking.thinkingOf(g));
        assertEquals("Answer.", ModelThinking.answerText(new ChatResponse(List.of(g))));
    }

    @Test
    @DisplayName("inline <think>: split across chunks, including a tag cut in half, and never shown as answer")
    void inlineThinkAcrossChunks() {
        ModelThinking.Splitter splitter = new ModelThinking.Splitter();
        StringBuilder answer = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        for (String chunk : List.of("<thi", "nk>plan the", " reply</th", "ink>The answer", " is 4.")) {
            ModelThinking.Piece piece = splitter.accept(new ChatResponse(List.of(withProps(chunk, Map.of()))));
            answer.append(piece.answer());
            thinking.append(piece.thinking());
        }
        answer.append(splitter.finish().answer());
        assertEquals("The answer is 4.", answer.toString());
        assertEquals("plan the reply", thinking.toString());
    }

    @Test
    @DisplayName("inline: a '<' that turns out not to start a tag is answer after all")
    void aLoneAngleBracketIsAnswer() {
        ModelThinking.Splitter splitter = new ModelThinking.Splitter();
        String first = splitter.accept(new ChatResponse(List.of(withProps("x <", Map.of())))).answer();
        String second = splitter.accept(new ChatResponse(List.of(withProps(" 5", Map.of())))).answer();
        assertEquals("x < 5", first + second + splitter.finish().answer());
    }

    @Test
    @DisplayName("stripInline: blocks, an unterminated block, and a bare closing tag the prompt opened")
    void stripInline() {
        assertEquals("Answer.", ModelThinking.stripInline("<think>why</think>Answer."));
        assertEquals("A B", ModelThinking.stripInline("A <think>x</think>B"));
        assertEquals("Answer.", ModelThinking.stripInline("still thinking here</think>\nAnswer."));
        assertEquals("Start", ModelThinking.stripInline("Start<think>never closed"));
        assertEquals("no tags at all", ModelThinking.stripInline("no tags at all"));
    }

    @Test
    @DisplayName("the token estimate is about four characters a token, and never zero for some text")
    void estimate() {
        assertEquals(0, ModelThinking.estimateTokens(0));
        assertEquals(1, ModelThinking.estimateTokens(1));
        assertEquals(25, ModelThinking.estimateTokens(100));
    }

    private static Generation withProps(String text, Map<String, Object> props) {
        return new Generation(AssistantMessage.builder().content(text).properties(props).build());
    }
}
