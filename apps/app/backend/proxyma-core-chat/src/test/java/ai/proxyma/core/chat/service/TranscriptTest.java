package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.domain.MessageRole;
import ai.proxyma.core.chat.dto.ChatMessageResponse;
import ai.proxyma.core.chat.dto.ChatMessageResponse.ToolCallResponse;
import ai.proxyma.core.chat.model.ChatMessage;
import ai.proxyma.core.chat.repository.JpaChatMemoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a reloaded conversation shows of the steps an agent took - which is everything it took.
 *
 * <p>The transcript is built from the stored rows, and until 2026-10-01 it discarded every tool
 * call still waiting for an answer to attach to whenever it met a USER row. Two things made that
 * wrong. A message typed while a turn runs is stored as a USER row in the MIDDLE of the turn, so
 * the steps taken before it disappeared on reload. And a turn that ends without an answer - Stop
 * pressed while tools ran, the step limit reached, a provider error - left its last steps waiting
 * for a message that never came, and the next question erased them.
 */
class TranscriptTest {

    private long nextId = 1;

    private ChatMessage row(MessageRole role, String content) {
        ChatMessage m = new ChatMessage();
        m.setId(nextId++);
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    private ChatMessage user(String text) { return row(MessageRole.USER, text); }

    private ChatMessage assistant(String text, String... callIds) {
        ChatMessage m = row(MessageRole.ASSISTANT, text);
        if (callIds.length > 0) {
            List<String> calls = new ArrayList<>();
            for (String id : callIds) {
                calls.add("{\"id\":\"" + id + "\",\"type\":\"function\",\"name\":\"searchIndexedSources\",\"arguments\":\"{}\"}");
            }
            m.setToolCallsJson("[" + String.join(",", calls) + "]");
        }
        return m;
    }

    private ChatMessage results(String... callIds) {
        ChatMessage m = row(MessageRole.TOOL, null);
        List<String> responses = new ArrayList<>();
        for (String id : callIds) {
            responses.add("{\"id\":\"" + id + "\",\"name\":\"searchIndexedSources\",\"responseData\":\"{\\\"results\\\":[]}\"}");
        }
        m.setToolResponsesJson("[" + String.join(",", responses) + "]");
        return m;
    }

    private static List<ChatMessageResponse> transcript(List<ChatMessage> rows) {
        return ChatService.transcript(rows, Map.of(), null);
    }

    private static List<String> shape(List<ChatMessageResponse> transcript) {
        return transcript.stream().map(m -> {
            String calls = m.toolCalls() == null ? ""
                    : m.toolCalls().stream().map(ToolCallResponse::callId).toList().toString();
            return m.role() + ":" + m.content() + calls;
        }).toList();
    }

    @Test
    @DisplayName("calls attach to the answer they led to, as before")
    void callsAttachToTheNextAnswer() {
        var transcript = transcript(List.of(
                user("q"), assistant("", "a", "b"), results("a", "b"), assistant("the answer")));

        assertThat(shape(transcript)).containsExactly("USER:q", "ASSISTANT:the answer[a, b]");
        assertThat(transcript.get(1).toolCalls()).allMatch(ToolCallResponse::done);
    }

    @Test
    @DisplayName("a message typed mid-turn keeps the steps taken before it, above it")
    void aMidTurnMessageKeepsTheStepsAboveIt() {
        var transcript = transcript(List.of(
                user("q"), assistant("", "a"), results("a"),
                user("typed while it worked"), assistant("the answer")));

        assertThat(shape(transcript)).containsExactly(
                "USER:q", "ASSISTANT:[a]", "USER:typed while it worked", "ASSISTANT:the answer");
        assertThat(transcript.get(1).id()).as("a row of tool calls is no single stored message").isNull();
    }

    @Test
    @DisplayName("a turn that ended on its tool calls keeps them, and the next reply does not inherit them")
    void aTurnWithNoAnswerKeepsItsCalls() {
        var transcript = transcript(List.of(
                user("q"), assistant("Let me look.", "a"), results("a"),
                user("next question"), assistant("hello")));

        assertThat(shape(transcript)).containsExactly(
                "USER:q", "ASSISTANT:Let me look.", "ASSISTANT:[a]", "USER:next question", "ASSISTANT:hello");
    }

    @Test
    @DisplayName("the last turn's unanswered calls are kept when nothing follows them")
    void trailingCallsAreKept() {
        var transcript = transcript(List.of(user("q"), assistant("", "a", "b"), results("a")));

        assertThat(shape(transcript)).containsExactly("USER:q", "ASSISTANT:[a, b]");
        List<ToolCallResponse> calls = transcript.get(1).toolCalls();
        assertThat(calls.get(0).done()).isTrue();
        // No result was ever stored for b: sent as it is, for the page to show as interrupted.
        assertThat(calls.get(1).done()).isFalse();
        assertThat(calls.get(1).result()).isNull();
    }

    @Test
    @DisplayName("a call carries the times its result row recorded")
    void timingsComeFromTheResultRow() {
        ChatMessage answered = results("a");
        answered.setMetadataJson("{\"" + JpaChatMemoryRepository.TOOL_TIMINGS + "\":{\"a\":[1000,4200]}}");

        var transcript = transcript(List.of(user("q"), assistant("", "a"), answered, assistant("done")));

        ToolCallResponse call = transcript.get(1).toolCalls().get(0);
        assertThat(call.startedAt()).isEqualTo(1000L);
        assertThat(call.endedAt()).isEqualTo(4200L);
    }

    @Test
    @DisplayName("the step-limit notice sits under the answer the last step wrote")
    void stepLimitUnderTheLastAnswer() {
        ChatMessage last = assistant("This is as far as I got.");
        last.setMetadataJson(JpaChatMemoryRepository.withStepLimit(null, 10));

        var transcript = transcript(List.of(user("q"), assistant("", "a"), results("a"), last));

        assertThat(shape(transcript)).containsExactly("USER:q", "ASSISTANT:This is as far as I got.[a]");
        assertThat(transcript.get(1).stepLimit()).isEqualTo(10);
    }

    @Test
    @DisplayName("when the last step asked for tools instead, the notice follows those calls")
    void stepLimitAfterCallsThatWereNotRun() {
        ChatMessage last = assistant("", "b");
        last.setMetadataJson(JpaChatMemoryRepository.withStepLimit(null, 10));

        var transcript = transcript(List.of(
                user("q"), assistant("", "a"), results("a"), last, results("b"), user("continue")));

        assertThat(shape(transcript)).containsExactly("USER:q", "ASSISTANT:[a, b]", "USER:continue");
        assertThat(transcript.get(1).stepLimit()).isEqualTo(10);
    }

    @Test
    @DisplayName("a last step that wrote nothing and called nothing still gets its notice")
    void stepLimitWithNothingToPutItUnder() {
        ChatMessage last = assistant("");
        last.setMetadataJson(JpaChatMemoryRepository.withStepLimit(null, 5));

        var transcript = transcript(List.of(user("q"), last));

        assertThat(shape(transcript)).containsExactly("USER:q", "ASSISTANT:");
        assertThat(transcript.get(1).stepLimit()).isEqualTo(5);
    }

    @Test
    @DisplayName("the step limit is added to metadata without disturbing what a provider needs back")
    void stepLimitKeepsOtherMetadata() {
        String merged = JpaChatMemoryRepository.withStepLimit("{\"thoughtSignatures\":[\"AAEC\"]}", 10);

        assertThat(merged).contains("\"thoughtSignatures\":[\"AAEC\"]");
        assertThat(JpaChatMemoryRepository.readStepLimit(merged)).isEqualTo(10);
        assertThat(JpaChatMemoryRepository.readStepLimit("{\"thoughtSignatures\":[\"AAEC\"]}")).isNull();
    }

    @Test
    @DisplayName("while a turn runs, the transcript stops at the message that opened it")
    void aRunningTurnIsLeftToTheEventStream() {
        ChatMessage earlierQuestion = user("earlier");
        ChatMessage earlierAnswer = assistant("earlier answer");
        long floor = earlierAnswer.getId();
        List<ChatMessage> rows = List.of(earlierQuestion, earlierAnswer,
                user("running question"), assistant("Let me look.", "a"), results("a"),
                user("typed while it worked"), assistant("partial"));

        // The event stream replays the running turn from its start; a transcript that also carried
        // its finished steps showed each of them twice on a page that reconnected.
        assertThat(shape(ChatService.transcript(rows, Map.of(), floor)))
                .containsExactly("USER:earlier", "ASSISTANT:earlier answer", "USER:running question");
        // And once the turn has ended, all of it.
        assertThat(shape(ChatService.transcript(rows, Map.of(), null))).hasSize(7);
    }

    // -- Sub-agents ---------------------------------------------------------------------------

    private ChatMessage delegated(String callId) {
        ChatMessage m = row(MessageRole.ASSISTANT, "");
        m.setToolCallsJson("[{\"id\":\"" + callId + "\",\"type\":\"function\",\"name\":\"delegate\",\"arguments\":\"{}\"}]");
        return m;
    }

    private ChatMessage delegateResult(String callId, String agentId) {
        ChatMessage m = row(MessageRole.TOOL, null);
        m.setToolResponsesJson("[{\"id\":\"" + callId + "\",\"name\":\"delegate\",\"responseData\":"
                + "\"{\\\"status\\\":\\\"RUNNING\\\",\\\"agentId\\\":\\\"" + agentId + "\\\"}\"}]");
        return m;
    }

    private ChatMessage reported(String agentId, String title, String status) {
        return row(MessageRole.USER, SubAgentService.reportMessage(List.of(
                new SubAgentService.Settled(agentId, title, status, "The report.", 12)), List.of()));
    }

    private static final String AGENT = "11111111-2222-3333-4444-555555555555";

    @Test
    @DisplayName("a delegate call carries the sub-agent it started")
    void aDelegateCallCarriesItsSubAgent() {
        var view = new ai.proxyma.core.chat.dto.SubAgentView(AGENT, "async", "completed", "Read the page",
                1_000L, 9_000L, "The report.", List.of(), null, null);
        List<ChatMessage> rows = List.of(user("q"), delegated("d1"), delegateResult("d1", AGENT),
                assistant("It is running."));

        var transcript = ChatService.transcript(rows, Map.of(), null,
                (callId, result) -> result != null && result.contains(AGENT) ? view : null);

        ToolCallResponse call = transcript.get(1).toolCalls().get(0);
        assertThat(call.agent()).isSameAs(view);
        // Only a delegate call is ever looked up.
        var plain = ChatService.transcript(List.of(user("q"), assistant("", "a"), results("a"), assistant("done")),
                Map.of(), null, (callId, result) -> view);
        assertThat(plain.get(1).toolCalls().get(0).agent()).isNull();
    }

    @Test
    @DisplayName("a report handed to the main agent is not something the user said")
    void aReportIsNotAUserMessage() {
        List<ChatMessage> rows = List.of(user("q"), delegated("d1"), delegateResult("d1", AGENT),
                assistant("It is running."), reported(AGENT, "Read the page", "COMPLETED"),
                assistant("It found the price."));

        var transcript = transcript(rows);

        assertThat(shape(transcript)).containsExactly(
                "USER:q", "ASSISTANT:It is running.[d1]", "ASSISTANT:", "ASSISTANT:It found the price.");
        var notice = transcript.get(2);
        assertThat(notice.id()).as("nothing on it can be rated, shared or rewound").isNull();
        assertThat(notice.subAgentReports()).singleElement().satisfies(r -> {
            assertThat(r.agentId()).isEqualTo(AGENT);
            assertThat(r.title()).isEqualTo("Read the page");
            assertThat(r.status()).isEqualTo("completed");
        });
    }

    @Test
    @DisplayName("a report that opened a turn still running is left to the event stream, like the turn")
    void aReportThatOpenedARunningTurn() {
        ChatMessage question = user("q");
        ChatMessage running = assistant("It is running.");
        long floor = running.getId();
        List<ChatMessage> rows = List.of(question, running,
                reported(AGENT, "Read the page", "COMPLETED"), assistant("partial reaction"));

        // The stream replays that turn from its start - its 'sub-agent-reported' included.
        assertThat(shape(ChatService.transcript(rows, Map.of(), floor)))
                .containsExactly("USER:q", "ASSISTANT:It is running.");
    }

    @Test
    @DisplayName("chat-agent-02: calls stored without ids are shown, each with its own result, and nothing throws")
    void callsWithoutIds() {
        // Rows from before every call was given an id: Gemini's two parallel calls, both id "".
        // Both results attached to the first call, and the second read as interrupted.
        ChatMessage calls = row(MessageRole.ASSISTANT, "");
        calls.setToolCallsJson("[{\"id\":\"\",\"type\":\"function\",\"name\":\"searchIndexedSources\",\"arguments\":\"{}\"},"
                + "{\"id\":\"\",\"type\":\"function\",\"name\":\"searchIndexedSources\",\"arguments\":\"{}\"}]");
        // And Ollama's, with no id at all - which threw a NullPointerException and failed the page.
        ChatMessage nullCall = row(MessageRole.ASSISTANT, "");
        nullCall.setToolCallsJson("[{\"type\":\"function\",\"name\":\"searchIndexedSources\",\"arguments\":\"{}\"}]");

        var transcript = transcript(List.of(user("q"), calls, results("", ""), nullCall, assistant("done")));

        assertThat(transcript).hasSize(2);
        assertThat(transcript.get(1).toolCalls()).hasSize(3);
        assertThat(transcript.get(1).toolCalls().subList(0, 2)).allMatch(ToolCallResponse::done);
    }
}
