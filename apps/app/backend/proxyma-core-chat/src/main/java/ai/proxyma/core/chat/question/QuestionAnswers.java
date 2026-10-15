package ai.proxyma.core.chat.question;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The user's answers to an askUser call, as the page posts them and as the model reads them.
 *
 * <p>An answer is text - the chosen label, or the user's own words - or, for a question that took
 * several picks, a LIST of text with one entry per choice ticked. It was always text until
 * 2026-10-02, the picks joined by a comma: "Confluence, Files, local" for two choices, one of
 * which holds a comma, so the model could not tell two picks from three.
 *
 * <p>Everything here reaches the model verbatim, from a request body, so all of it is bounded:
 * the questions, the picks of one question, and the length of each.
 */
public final class QuestionAnswers {

    /** askUser takes 1-4 questions; anything past this is not an answer to one of them. */
    static final int MAX_QUESTIONS = 8;
    /** A question offers 2-4 choices and a row for the user's own words. */
    static final int MAX_PICKS = 12;
    static final int MAX_QUESTION_CHARS = 500;
    static final int MAX_ANSWER_CHARS = 2000;

    private QuestionAnswers() {}

    /** The {@code answers} of a request body: [{question, answer}], an answer being text or a list of text. */
    public static List<Map<String, Object>> read(Object raw) {
        List<Map<String, Object>> answers = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return answers;
        for (Object o : list) {
            if (answers.size() >= MAX_QUESTIONS) break;
            if (!(o instanceof Map<?, ?> m)) continue;
            Object question = m.get("question");
            Object answer = answer(m.get("answer"));
            if (question == null || answer == null) continue;
            // Insertion order, so the model reads the question before its answer.
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("question", truncate(question.toString(), MAX_QUESTION_CHARS));
            entry.put("answer", answer);
            answers.add(entry);
        }
        return answers;
    }

    // A list stays a list - one pick included, so the shape says the question took several.
    private static Object answer(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof List<?> picks)) return truncate(raw.toString(), MAX_ANSWER_CHARS);
        List<String> kept = new ArrayList<>();
        for (Object pick : picks) {
            if (kept.size() >= MAX_PICKS) break;
            if (pick == null || pick.toString().isBlank()) continue;
            kept.add(truncate(pick.toString(), MAX_ANSWER_CHARS));
        }
        return kept.isEmpty() ? null : kept;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
