package ai.proxyma.core.chat.question;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The answers to an askUser call, read from a request body. They reach the model verbatim, so what
 * is pinned is the shape - several picks stay a list - and that nothing here is unbounded.
 */
class QuestionAnswersTest {

    @Test
    @DisplayName("several picks stay a list, one entry per choice - a label may hold a comma")
    void severalPicksStayAList() {
        List<Map<String, Object>> answers = QuestionAnswers.read(List.of(
                Map.of("question", "Which sources?", "answer", List.of("Confluence", "Files, local")),
                Map.of("question", "How long?", "answer", "One page")));

        assertThat(answers).hasSize(2);
        assertThat(answers.get(0)).containsEntry("question", "Which sources?")
                .containsEntry("answer", List.of("Confluence", "Files, local"));
        assertThat(answers.get(1)).containsEntry("answer", "One page");
    }

    @Test
    @DisplayName("one pick of a question that took several is still a list")
    void onePickOfSeveralIsStillAList() {
        List<Map<String, Object>> answers = QuestionAnswers.read(List.of(
                Map.of("question", "Which sources?", "answer", List.of("Jira"))));

        assertThat(answers.get(0).get("answer")).isEqualTo(List.of("Jira"));
    }

    @Test
    @DisplayName("an entry with no question, no answer or only blank picks is left out")
    void whatIsNotAnAnswerIsLeftOut() {
        List<Map<String, Object>> answers = QuestionAnswers.read(Arrays.asList(
                Map.of("answer", "PDF"),
                Map.of("question", "Which format?"),
                Map.of("question", "Which sources?", "answer", Arrays.asList(" ", null)),
                "not a map",
                null,
                Map.of("question", "Which sources?", "answer", Arrays.asList("Jira", "", null))));

        assertThat(answers).hasSize(1);
        assertThat(answers.get(0).get("answer")).isEqualTo(List.of("Jira"));
        assertThat(QuestionAnswers.read("text")).isEmpty();
        assertThat(QuestionAnswers.read(null)).isEmpty();
    }

    @Test
    @DisplayName("everything is bounded: the questions, the picks of one, and the length of each")
    void everythingIsBounded() {
        String longText = "x".repeat(5000);
        List<Object> manyPicks = new ArrayList<>(Collections.nCopies(100, longText));
        List<Object> manyQuestions = new ArrayList<>(Collections.nCopies(50,
                Map.of("question", longText, "answer", manyPicks)));
        manyQuestions.add(0, Map.of("question", "Q?", "answer", longText));

        List<Map<String, Object>> answers = QuestionAnswers.read(manyQuestions);

        assertThat(answers).hasSize(QuestionAnswers.MAX_QUESTIONS);
        assertThat((String) answers.get(0).get("answer")).hasSize(QuestionAnswers.MAX_ANSWER_CHARS);
        assertThat((String) answers.get(1).get("question")).hasSize(QuestionAnswers.MAX_QUESTION_CHARS);
        @SuppressWarnings("unchecked")
        List<String> picks = (List<String>) answers.get(1).get("answer");
        assertThat(picks).hasSize(QuestionAnswers.MAX_PICKS)
                .allSatisfy(pick -> assertThat(pick).hasSize(QuestionAnswers.MAX_ANSWER_CHARS));
    }
}
