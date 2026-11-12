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
    @DisplayName("a turn that failed is read back with why, after its steps and before the next message")
    void aFailedTurnSaysWhy() {
        // A scheduled run whose model could not be reached: its message, and the failure marked on it.
        ChatMessage opened = user("[Scheduled run] answer the comments");
        opened.setMetadataJson(JpaChatMemoryRepository.withTurnError(null, "The server refused the connection."));
        assertThat(shape(transcript(List.of(opened))))
                .containsExactly("USER:[Scheduled run] answer the comments", "ASSISTANT:The server refused the connection.");
        assertThat(transcript(List.of(opened)).get(1).error()).isTrue();

        // A turn that took a step before failing, then a later message: the failure sits between them.
        ChatMessage step = assistant("", "a");
        ChatMessage answered = results("a");
        answered.setMetadataJson(JpaChatMemoryRepository.withTurnError(null, "Rate limited."));
        var transcript = transcript(List.of(user("q"), step, answered, user("again"), assistant("ok")));
        assertThat(shape(transcript)).containsExactly(
                "USER:q", "ASSISTANT:[a]", "ASSISTANT:Rate limited.", "USER:again", "ASSISTANT:ok");
        assertThat(transcript.get(2).error()).isTrue();
        assertThat(transcript.get(4).error()).isNull();

        // Marked on a row the transcript skips (a skill the turn loaded): still shown.
        ChatMessage skill = user("q2");
        ChatMessage loaded = row(MessageRole.USER, null);
        loaded.setContent(skillMarkerContent());
        loaded.setMetadataJson(JpaChatMemoryRepository.withTurnError(null, "No model."));
        assertThat(shape(transcript(List.of(skill, loaded)))).containsExactly("USER:q2", "ASSISTANT:No model.");
    }

    // A skill-loaded row as SmartChatMemory writes it.
    private static String skillMarkerContent() {
        String content = "[SKILL: Agent team] Instructions.";
        assertThat(SmartChatMemory.isSkillMarker(content)).isTrue();
        return content;
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

    private ChatMessage reported(String agentId, String title) {
        return row(MessageRole.USER, ai.proxyma.core.chat.agents.AgentInbox.render(List.of(
                new ai.proxyma.core.chat.agents.AgentInbox.Taken(null, ai.proxyma.core.chat.agents.AgentInbox.Kind.REPORT,
                        java.util.UUID.fromString(agentId), title, "The report.", java.time.Instant.now()),
                new ai.proxyma.core.chat.agents.AgentInbox.Taken(null, ai.proxyma.core.chat.agents.AgentInbox.Kind.ALARM,
                        null, null, "Check the build.", java.time.Instant.now()))));
    }

    private static final String AGENT = "11111111-2222-3333-4444-555555555555";

    @Test
    @DisplayName("an agent's mail - a report, an alarm - is not something the user said")
    void aReportIsNotAUserMessage() {
        List<ChatMessage> rows = List.of(user("q"), delegated("d1"), delegateResult("d1", AGENT),
                assistant("It is running."), reported(AGENT, "Read the page"),
                assistant("It found the price."));

        var transcript = transcript(rows);

        assertThat(shape(transcript)).containsExactly(
                "USER:q", "ASSISTANT:It is running.[d1]", "ASSISTANT:", "ASSISTANT:It found the price.");
        var notice = transcript.get(2);
        assertThat(notice.id()).as("nothing on it can be rated, shared or rewound").isNull();
        assertThat(notice.agentMail()).hasSize(2);
        assertThat(notice.agentMail().get(0)).satisfies(r -> {
            assertThat(r.agentId()).isEqualTo(AGENT);
            assertThat(r.title()).isEqualTo("Read the page");
            assertThat(r.kind()).isEqualTo("report");
        });
        assertThat(notice.agentMail().get(1).kind()).isEqualTo("alarm");
    }

    @Test
    @DisplayName("a report that opened a turn still running is left to the event stream, like the turn")
    void aReportThatOpenedARunningTurn() {
        ChatMessage question = user("q");
        ChatMessage running = assistant("It is running.");
        long floor = running.getId();
        List<ChatMessage> rows = List.of(question, running,
                reported(AGENT, "Read the page"), assistant("partial reaction"));

        // The stream replays that turn from its start - its 'agent-mail' included.
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

    @Test
    @DisplayName("a teammate's reply or message and what the agent told the person are mail with their words, never the person's bubble")
    void agentWordsAreMailNotThePersons() {
        String reportFrom = "[Report from Price check (agent 0f3c9a52-6a33-4d47-9d5b-2d6a0a1b2c3d)]\nThe whole report.";
        var transcript = transcript(List.of(
                user("fix the drafts waiting for approval for now"),
                user("[Reply from Viet tra loi]\nDa nhan. Toi khong dang gi them.\n\nDa gui duyet: 0"),
                user("[Reply from Scout: it could not answer]\nThe quota is spent."),
                user("[Message from Reply Writer (Writes replies), your peer]\nTwo drafts need a decision."),
                user("[You told the person (from your scheduled run)]\nNeeds your decision: 8 items."),
                user(reportFrom),
                assistant("I asked the Reply Writer.")));

        assertThat(transcript).hasSize(7);
        assertThat(transcript.get(0).role()).isEqualTo(MessageRole.USER);
        assertThat(transcript.subList(1, 6)).allSatisfy(m -> {
            assertThat(m.role()).as("drawn as mail, not as the person's").isEqualTo(MessageRole.ASSISTANT);
            assertThat(m.content()).isEmpty();
            assertThat(m.id()).as("nothing on it can be rated or rewound").isNull();
        });
        assertThat(transcript.get(1).agentMail()).singleElement().satisfies(a -> {
            assertThat(a.kind()).isEqualTo("reply");
            assertThat(a.title()).isEqualTo("Viet tra loi");
            assertThat(a.text()).isEqualTo("Da nhan. Toi khong dang gi them.\n\nDa gui duyet: 0");
        });
        assertThat(transcript.get(2).agentMail().get(0).title()).isEqualTo("Scout: it could not answer");
        assertThat(transcript.get(3).agentMail()).singleElement().satisfies(a -> {
            assertThat(a.kind()).isEqualTo("message");
            assertThat(a.text()).isEqualTo("Two drafts need a decision.");
        });
        assertThat(transcript.get(4).agentMail()).singleElement().satisfies(a -> {
            assertThat(a.kind()).isEqualTo("told");
            assertThat(a.text()).isEqualTo("Needs your decision: 8 items.");
        });
        assertThat(transcript.get(5).agentMail()).singleElement().satisfies(a -> {
            assertThat(a.kind()).isEqualTo("report");
            assertThat(a.agentId()).isEqualTo("0f3c9a52-6a33-4d47-9d5b-2d6a0a1b2c3d");
            assertThat(a.text()).as("a report is read in its agent's own panel").isNull();
        });
    }

    @Test
    @DisplayName("the note a turn's opening carries for the model - its preloaded skills - is never a mail's words")
    void openingMailCarriesNoModelNote() {
        // A saved agent's turn opened by a teammate's message, with its skills preloaded: the stored row is the
        // message and then the "[Context: ...]" block for the model (the maintainer's screenshot, 2026-11-05).
        String stored = "[Message from Lead]\nList the new comments."
                + ChatService.skillMentionBlock(List.of("YouTube"));
        var transcript = transcript(List.of(user(stored), assistant("Done.")));

        assertThat(transcript.get(0).agentMail()).singleElement().satisfies(a -> {
            assertThat(a.kind()).isEqualTo("message");
            assertThat(a.text()).isEqualTo("List the new comments.");
        });
        assertThat(transcript).noneSatisfy(m -> assertThat(String.valueOf(m.content()) + m.agentMail())
                .contains("[Context:"));
    }

    @Test
    @DisplayName("a person's message never shows the notes appended for the model, and keeps its chips")
    void personsMessageShowsNoModelNote() {
        var transcript = transcript(List.of(
                user("Summarize the channel." + ChatService.skillMentionBlock(List.of("YouTube"))),
                assistant("Here it is.")));

        assertThat(transcript.get(0).content()).isEqualTo("Summarize the channel.");
        assertThat(transcript.get(0).skillMentions()).containsExactly("YouTube");
    }

    // A module that wrote "[Run, ...]" openings, a header over a person's words, and messages whose sender
    // heading carries words for the model.
    private static final ChatService.ShownBy MODULE = ChatService.ShownBy.of(List.of(new ShowingModule()));

    private static final class ShowingModule implements ai.proxyma.core.chat.module.ChatModule {
        @Override public List<Object> toolObjects() { return List.of(); }
        @Override public Map<String, java.util.Set<String>> skillToolGroups() { return Map.of(); }
        @Override public java.util.Set<String> ownToolNames() { return java.util.Set.of(); }
        @Override public boolean offersToolsIn(String conversationId, String userId) { return false; }
        @Override public java.util.Optional<String> systemContext(String conversationId, String userId) { return java.util.Optional.empty(); }
        @Override public boolean licensed() { return true; }

        @Override
        public java.util.Optional<Shown> shownOpening(String content) {
            if (content.startsWith("[Run, ")) return java.util.Optional.of(Shown.asMail("run_schedule",
                    content.substring(content.indexOf('\n') + 1)));
            if (content.startsWith("[Relayed]\n")) return java.util.Optional.of(Shown.asPerson(content.substring(10)));
            if (content.startsWith("[Throws")) throw new IllegalStateException("a module that fails costs only itself");
            return java.util.Optional.empty();
        }

        @Override
        public ai.proxyma.core.chat.agents.AgentInbox.Item shownMail(ai.proxyma.core.chat.agents.AgentInbox.Item item) {
            return item.title() != null && item.title().endsWith(", your peer")
                    ? new ai.proxyma.core.chat.agents.AgentInbox.Item(item.agentId(),
                            item.title().substring(0, item.title().length() - 11), item.kind(), item.text())
                    : item;
        }
    }

    @Test
    @DisplayName("a module's opening is drawn as the line it names, its header and the model's notes off")
    void moduleOpeningsAreShownAsTheModuleSays() {
        List<ChatMessage> rows = List.of(
                user("[Run, 2026-11-05 09:00 UTC]\nCheck the new comments."
                        + ChatService.skillMentionBlock(List.of("YouTube"))),
                assistant("Checked."),
                user("[Relayed]\nWhat did the team find?"),
                assistant("Two questions."),
                user("[Throws]\nkept as typed"),
                user("[Message from Ana (Writer), your peer]\nA draft is ready."));

        var transcript = ChatService.transcript(rows, Map.of(), null, MODULE);

        assertThat(transcript.get(0).role()).as("nobody typed it").isEqualTo(MessageRole.ASSISTANT);
        assertThat(transcript.get(0).id()).isNull();
        assertThat(transcript.get(0).content()).isEmpty();
        assertThat(transcript.get(0).agentMail()).singleElement().satisfies(a -> {
            assertThat(a.kind()).isEqualTo("run_schedule");
            assertThat(a.text()).as("the task, without the model's note").isEqualTo("Check the new comments.");
        });
        assertThat(transcript.get(2).role()).isEqualTo(MessageRole.USER);
        assertThat(transcript.get(2).content()).isEqualTo("What did the team find?");
        assertThat(transcript.get(4).content()).isEqualTo("[Throws]\nkept as typed");
        assertThat(transcript.get(5).agentMail()).singleElement().satisfies(a -> {
            assertThat(a.title()).isEqualTo("Ana (Writer)");
            assertThat(a.text()).isEqualTo("A draft is ready.");
        });
    }

    @Test
    @DisplayName("a module's opening of a turn still running is the last thing the transcript says, as a person's is")
    void moduleOpeningOfARunningTurn() {
        ChatMessage before = assistant("earlier");
        long floor = before.getId();
        List<ChatMessage> rows = List.of(before, user("[Run, now]\nGo."), assistant("partial"));

        var transcript = ChatService.transcript(rows, Map.of(), floor, MODULE);

        assertThat(transcript).hasSize(2);
        assertThat(transcript.get(1).agentMail()).singleElement()
                .satisfies(a -> assertThat(a.kind()).isEqualTo("run_schedule"));
    }

    @Test
    @DisplayName("several items in one row each keep their own words")
    void severalItemsKeepTheirOwnWords() {
        var items = ai.proxyma.core.chat.agents.AgentInbox.itemsIn(
                "[Reply from A]\nfirst\nanswer\n\n[Reply from B]\nsecond");
        assertThat(items).extracting(ai.proxyma.core.chat.agents.AgentInbox.Item::text)
                .containsExactly("first\nanswer", "second");
        assertThat(ChatService.isScaffolding("[Reply from A]\nfirst")).as("an earlier conversation read back leaves it out")
                .isTrue();
        assertThat(ai.proxyma.core.chat.agents.AgentInbox.shownText("told", "x".repeat(9_000)))
                .hasSizeLessThanOrEqualTo(ai.proxyma.core.chat.agents.AgentInbox.SHOWN_TEXT_MAX);
    }
}
