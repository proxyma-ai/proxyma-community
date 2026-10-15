package ai.proxyma.core.provider.gateway;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.Map;

/**
 * What a model THOUGHT, told apart from what it ANSWERED - on every protocol, in one place.
 *
 * <p>Reasoning models stream their thinking alongside the answer, and each protocol, as Spring AI
 * 1.1.7 surfaces it, puts it somewhere different (researched 2026-09-25):
 * <ul>
 *   <li><b>Anthropic</b> - a Generation OF ITS OWN whose text IS the thinking, told apart only by
 *       its metadata: {@code signature} on a thinking block, {@code data} (and no text) on a
 *       redacted one. Read as ordinary text it leaks into the answer; read as "the first
 *       generation" it REPLACES the answer - which is what every Claude model that thinks by
 *       default did to titles, condensation summaries and every other one-shot call.</li>
 *   <li><b>OpenAI-compatible</b> - AssistantMessage metadata {@code reasoningContent}, from the
 *       {@code reasoning_content} delta field (DeepSeek, llama.cpp). vLLM, Ollama's /v1 and LM
 *       Studio send a field named {@code reasoning} instead, which Spring AI drops; the
 *       OpenAI-compatible adapter renames it on the way in (ReasoningFieldAlias) so it lands here
 *       too.</li>
 *   <li><b>Ollama native</b> - Generation metadata {@code thinking}, on the non-streaming path only.
 *       Spring AI's native stream discards it entirely, so there it is invisible, not leaked.</li>
 *   <li><b>Inline {@code <think>...</think>}</b> - a model whose server does not parse reasoning
 *       (Qwen3, R1 distills) writes it into the answer text itself. {@link Splitter} takes it out
 *       while streaming, {@link #stripInline} from a finished text.</li>
 * </ul>
 * OpenAI and Azure send no reasoning text on Chat Completions at all, and Gemini's thought parts
 * are only returned when includeThoughts is set, which Proxyma does not set - so neither has
 * anything here to separate.
 *
 * <p>Pure functions and one small state machine, so they are tested directly (ModelThinkingTest).
 */
public final class ModelThinking {

    static final String OPEN = "<think>";
    static final String CLOSE = "</think>";

    private ModelThinking() {}

    /** An Anthropic thinking or redacted-thinking generation - never part of the answer. */
    public static boolean isThinkingGeneration(Generation generation) {
        AssistantMessage out = generation.getOutput();
        if (out == null || out.getMetadata() == null) return false;
        Map<String, Object> md = out.getMetadata();
        if (md.containsKey("signature")) return true;
        return md.containsKey("data") && (out.getText() == null || out.getText().isEmpty());
    }

    /** The thinking text one generation carries, wherever its protocol put it, or empty. */
    public static String thinkingOf(Generation generation) {
        AssistantMessage out = generation.getOutput();
        if (out == null) return "";
        if (isThinkingGeneration(generation)) return nullToEmpty(out.getText());
        Object reasoning = out.getMetadata() == null ? null : out.getMetadata().get("reasoningContent");
        if (reasoning instanceof String s && !s.isEmpty()) return s;
        Object thinking = generation.getMetadata() == null ? null : generation.getMetadata().get("thinking");
        if (thinking instanceof String s && !s.isEmpty()) return s;
        return "";
    }

    /** Whether a response carried any thinking at all - including a thinking block with no text. */
    public static boolean hasThinking(ChatResponse response) {
        if (response == null || response.getResults() == null) return false;
        for (Generation g : response.getResults()) {
            if (isThinkingGeneration(g) || !thinkingOf(g).isEmpty()) return true;
        }
        return false;
    }

    /**
     * The ANSWER of a complete response: every non-thinking generation's text, joined, with inline
     * thinking removed. Never null.
     */
    public static String answerText(ChatResponse response) {
        if (response == null || response.getResults() == null) return "";
        StringBuilder text = new StringBuilder();
        for (Generation g : response.getResults()) {
            if (isThinkingGeneration(g) || g.getOutput() == null) continue;
            text.append(nullToEmpty(g.getOutput().getText()));
        }
        return stripInline(text.toString());
    }

    /**
     * A finished text with inline thinking removed: every {@code <think>...</think>} block, an
     * unterminated one at the end, and - the case a template produces by opening the thought in
     * the PROMPT - everything before a {@code </think>} that has no opening tag.
     */
    public static String stripInline(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        if (!text.contains(OPEN) && !text.contains(CLOSE)) return text;
        String rest = text;
        int close = rest.indexOf(CLOSE);
        int open = rest.indexOf(OPEN);
        if (close >= 0 && (open < 0 || close < open)) rest = rest.substring(close + CLOSE.length());
        StringBuilder out = new StringBuilder();
        while (true) {
            int start = rest.indexOf(OPEN);
            if (start < 0) {
                out.append(rest);
                break;
            }
            out.append(rest, 0, start);
            int end = rest.indexOf(CLOSE, start + OPEN.length());
            if (end < 0) break;
            rest = rest.substring(end + CLOSE.length());
        }
        return out.toString().strip();
    }

    /** A rough token count for thinking text - there is no per-delta count on any protocol. */
    public static int estimateTokens(int characters) {
        return characters <= 0 ? 0 : Math.max(1, Math.round(characters / 4f));
    }

    /** One chunk, split. */
    public record Piece(String answer, String thinking, boolean thinkingSignal) {}

    /**
     * Splits a STREAM chunk by chunk. One per stream: it remembers whether it is inside an inline
     * {@code <think>} block, and holds back the end of a chunk that could be the start of a tag,
     * so a tag split across two chunks - {@code "<thi"} then {@code "nk>"} - is still recognised
     * and never flashes into the answer.
     */
    public static final class Splitter {
        private boolean inThink;
        private String carry = "";

        public Piece accept(ChatResponse chunk) {
            if (chunk == null || chunk.getResults() == null) return new Piece("", "", false);
            StringBuilder answer = new StringBuilder();
            StringBuilder thinking = new StringBuilder();
            boolean signal = false;
            for (Generation g : chunk.getResults()) {
                String thought = thinkingOf(g);
                if (!thought.isEmpty() || isThinkingGeneration(g)) {
                    signal = true;
                    thinking.append(thought);
                }
                if (isThinkingGeneration(g) || g.getOutput() == null) continue;
                String text = g.getOutput().getText();
                if (text != null && !text.isEmpty()) split(text, answer, thinking);
            }
            if (thinking.length() > 0) signal = true;
            return new Piece(answer.toString(), thinking.toString(), signal);
        }

        /** Whatever is still held back when the stream ends - text that never became a tag. */
        public Piece finish() {
            String held = carry;
            carry = "";
            return inThink ? new Piece("", held, !held.isEmpty()) : new Piece(held, "", false);
        }

        private void split(String text, StringBuilder answer, StringBuilder thinking) {
            String rest = carry + text;
            carry = "";
            while (!rest.isEmpty()) {
                String tag = inThink ? CLOSE : OPEN;
                StringBuilder into = inThink ? thinking : answer;
                int at = rest.indexOf(tag);
                if (at >= 0) {
                    into.append(rest, 0, at);
                    rest = rest.substring(at + tag.length());
                    inThink = !inThink;
                    continue;
                }
                int keep = partialTagSuffix(rest, tag);
                into.append(rest, 0, rest.length() - keep);
                carry = rest.substring(rest.length() - keep);
                break;
            }
        }

        /** The length of the longest end of {@code text} that is a proper prefix of {@code tag}. */
        private static int partialTagSuffix(String text, String tag) {
            for (int n = Math.min(tag.length() - 1, text.length()); n > 0; n--) {
                if (text.endsWith(tag.substring(0, n))) return n;
            }
            return 0;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
