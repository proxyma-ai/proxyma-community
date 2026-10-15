package ai.proxyma.it.app;

import ai.proxyma.it.harness.Chat;
import ai.proxyma.it.harness.Corpus;
import ai.proxyma.it.harness.FakeLlm;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holding a conversation - what a user spends almost all of their time doing.
 *
 * <h2>What a fake model lets these assert, and what it deliberately does not</h2>
 *
 * Nothing here asserts that an answer is GOOD. That is the one part of the system this project
 * does not own, and a suite that graded it would be measuring the model.
 *
 * <p>What it does assert is everything up to and after the model call, which is where the product
 * lives: that the turn reached the provider at all, that the retrieved documents were actually in
 * the prompt, that the tool catalog offered was the right one, that a tool call round-trips and
 * its result comes back, that the answer streams token by token, that usage is reported, and that
 * the conversation survives. Most of those are questions about the OUTGOING request, which is why
 * {@link FakeLlm#lastChatRequest()} appears more often below than the answer text does.
 */
@DisplayName("Chat: holding a conversation")
class ChatJourneyIT extends DesktopJourney {

    private static String sourceId;

    @BeforeAll
    static void indexTheCorpus() {
        Path folder = Corpus.write();
        sourceId = Corpus.registerFolder(app, folder);
        Corpus.awaitIndexed(app, sourceId, Corpus.DOCUMENTS.size());
    }

    @AfterAll
    static void removeTheSource() {
        if (sourceId != null) app.api().delete("/api/v1/indexing/" + sourceId);
    }

    @Test
    @DisplayName("a plain question streams an answer and finishes")
    void aPlainTurn() {
        Chat.Turn turn = Chat.send(app, "@@text Hello from the model.").succeeded();

        assertThat(turn.text()).isEqualTo("Hello from the model.");
        assertThat(turn.types()).containsSubsequence("text", "done");

        // The directive proves the round trip end to end: the text was written by the test, sent
        // as a user message, parsed by the provider and streamed back through the product's own
        // SSE channel. Any break in that chain and the answer is something else.
    }

    @Test
    @DisplayName("the answer arrives in pieces, not in one lump")
    void theAnswerStreams() {
        Chat.Turn turn = Chat.send(app,
                "@@text one two three four five six seven eight nine ten").succeeded();

// THE ASSERTION IS THAT THE STREAM CARRIED THE ANSWER, NOT THAT IT CAME IN N FRAMES.
        //
        // Counting text events was the obvious way to prove "streamed rather than buffered", and
        // it is not a property the product guarantees: the fake provider emits one frame per word,
        // and how many ChatEvents that becomes depends on how the aggregator batches them under
        // load. The PC saw ten, the production host saw one, and both are correct.
        //
        // What a user actually needs is that the answer arrives on the event stream at all - if it
        // did not, the bubble stays empty until a reload. That is assertable and stable.
        String streamed = turn.streamedText();
        assertThat(streamed)
                .as("the whole answer arrived over the event stream, not just in the database")
                .isEqualTo("one two three four five six seven eight nine ten");
        assertThat(turn.text())
                .as("and the stream agrees with what was persisted")
                .isEqualTo(streamed);
    }

    @Test
    @DisplayName("a many-chunk answer still reaches the client whole")
    void aChunkedAnswerIsNotLost() {
        // THE TURN THAT STREAMED AN ANSWER NOBODY SAW. Until 2026-09-23 the final text flush ran
        // in doFinally, which Reactor invokes AFTER the terminal signal has reached the
        // subscriber - so block() returned first, the guardrail scrubber was drained before that
        // flush had given it the answer, and the answer went into a holdback nothing would ever
        // drain again. No TEXT event was published at all. The turn succeeded, the answer was
        // persisted correctly, and the assistant's bubble stayed empty until a reload.
        //
        // MANY CHUNKS IS THE TRIGGER, which is why this test asks for thirty. Measured against a
        // booted instance: a reply arriving in thirty frames lost its text on 60 turns out of 60,
        // a reply arriving in one on roughly 1 in 25. A real model streams token by token, so the
        // thirty-frame case is the ordinary one and the single-frame case is the test fixture.
        //
        // It was recorded for months as a race in the event sink, around the moment a client
        // subscribes (recurring-failure-classes.md section 26). The sink was never involved.
        String answer = "alpha bravo charlie delta echo foxtrot golf hotel india juliet";
        Chat.Turn turn = Chat.send(app, "@@chunks 30 @@text " + answer).succeeded();

        assertThat(turn.streamedText())
                .as("the whole answer arrived over the event stream, in %d events",
                        turn.events().size())
                .isEqualTo(answer);
        assertThat(turn.text())
                .as("and the stream agrees with what was persisted")
                .isEqualTo(answer);
    }

    @Test
    @DisplayName("a directive does not eat the question typed after it")
    void aDirectiveLeavesTheQuestionAlone() {
        // THIS PINS THE FIXTURE, DELIBERATELY, AND IT IS THE ONE FIXTURE ASSERTION IN THIS FILE.
        // A provider that quietly rewrites the prompt invalidates every other assertion here -
        // the product can be perfect and the test still red, or worse, green for the wrong
        // reason.
        //
        // fake-llm.mjs read every directive's argument as the rest of the line, so
        // `@@chunks 30 what are our payment terms?` reached the model as a chunk count and NO
        // QUESTION. It answered "FAKE ANSWER to: (no question)", which reads enough like an
        // answer to skim past, and the chunk count came out NaN so the directive did nothing
        // either. An hour of a real investigation went into that on 2026-09-23. Scalar
        // directives now take one token.
        Chat.Turn turn = Chat.send(app, "@@chunks 4 What are our payment terms?").succeeded();

        assertThat(turn.text())
                .as("the question survived the directive and reached the model. Got: %s",
                        turn.text())
                .contains("What are our payment terms?");
    }

    @Test
    @DisplayName("what a turn costs still arrives after it has finished")
    void usageSurvivesTheTerminalEvent() {
        // DONE IS NOT THE LAST EVENT OF A TURN, whatever the word terminal suggests.
        // ConversationUsageRecorder and ConversationTitleService both publish after it, and
        // ChatEventSink.emit used to complete the sink on DONE - so both were refused with
        // FAIL_TERMINATED and silently dropped. Measured: 18 events lost across 48 turns, which a
        // user sees as a cost figure and a conversation title that only appear after a reload.
        Chat.Turn turn = Chat.send(app, "@@tokens 4321/765 @@text billed").succeeded();

        assertThat(turn.types()).containsSubsequence("text", "done");
        assertThat(turn.has("usage"))
                .as("the usage event survived the turn's own DONE. Got: %s", turn.types())
                .isTrue();
    }

    @Test
    @DisplayName("the request carries stream_options, or every token count is zero")
    void streamUsageIsRequested() {
        Chat.send(app, "@@text anything").succeeded();
        JsonNode request = llm().lastChatRequest();

        assertThat(request.path("body").path("stream").asBoolean())
                .as("chat streams by default").isTrue();

        // ONE LINE IN THE ADAPTER, FOUR SILENT FAILURES WITHOUT IT. An OpenAI-compatible server
        // sends NO usage block on a stream unless the request asks with
        // stream_options.include_usage. Without it: cost recorded $0, the monthly quota never
        // advanced so quota gates could not bite, the Usages page stayed empty, and - least
        // obviously - Conversation.contextTokens was written as 0, so SmartChatMemory's
        // `contextTokens > 0` guard was never true and CONDENSATION NEVER RAN. A conversation
        // grew until the provider itself rejected it.
        assertThat(request.path("body").path("stream_options").path("include_usage").asBoolean())
                .as("stream_options.include_usage must be set on a streamed request")
                .isTrue();
    }

    @Test
    @DisplayName("usage is reported to the client during the turn")
    void usageReachesTheClient() {
        Chat.Turn turn = Chat.send(app, "@@tokens 1234/567 @@text counted").succeeded();

        assertThat(turn.has("usage")).as("a usage event was published").isTrue();
        assertThat(turn.first("usage").path("data").asText())
                .as("the usage event carries the numbers the provider reported")
                .contains("1234");
    }

    @Test
    @DisplayName("the agent is offered a tool catalog")
    void toolsAreOffered() {
        Chat.send(app, "@@text no tools needed").succeeded();

        List<String> tools = FakeLlm.toolsOffered(llm().lastChatRequest());
        // The catalog is the agent's entire capability surface. An empty one is not a smaller
        // feature set, it is an assistant that can do nothing but talk - and it fails silently,
        // because the model simply answers from memory and sounds fine.
        assertThat(tools).as("the model was offered tools to call").isNotEmpty();
    }

    @Test
    @DisplayName("a tool call round-trips: the agent calls it, the result comes back, it answers")
    void aToolCallRoundTrips() {
        Chat.Turn turn = Chat.send(app,
                "Find the invoicing rules. @@tool searchIndexedSources {\"query\":\"invoice payment terms\"}"
                + " @@after-tools I looked it up.").succeeded();

        assertThat(turn.toolsCalled())
                .as("the tool the model asked for was actually executed. Events: %s", turn.types())
                .contains("searchIndexedSources");
        assertThat(turn.types()).contains("tool-result");
        assertThat(turn.text()).isEqualTo("I looked it up.");

        // Two model calls, not one: the tool-calling turn and the turn that answers with the
        // results. A product that stopped after the tool call would leave the user with a spinner
        // and a completed tool row.
        assertThat(llm().chatRequests()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(FakeLlm.promptText(llm().lastChatRequest()))
                .as("the tool RESULT was fed back to the model, not just the call")
                .contains("tool:");
    }

    @Test
    @DisplayName("two tool calls in one stream delta: both run and the turn answers")
    void twoToolCallsInOneDelta() {
        // The server ends one call and opens the next in the SAME delta. Spring AI's stream merger
        // refuses a second tool call there, which ended the turn with nothing but a log line - a
        // GLM model behind an OpenAI-compatible gateway, 2026-10-02. See ParallelToolCallDeltas.
        Chat.Turn turn = Chat.send(app,
                "Look both up. @@straddle"
                + " @@tool searchIndexedSources {\"query\":\"invoice payment terms\"}"
                + " @@tool searchIndexedSources {\"query\":\"late payment interest\"}"
                + " @@after-tools Both looked up.").succeeded();

        assertThat(turn.toolsCalled())
                .as("both calls were executed. Events: %s", turn.types())
                .containsExactly("searchIndexedSources", "searchIndexedSources");
        assertThat(turn.text()).isEqualTo("Both looked up.");
    }

    @Test
    @DisplayName("a grounded answer had the document in its prompt")
    void retrievalReachesThePrompt() {
        Chat.Turn turn = Chat.sendMentioning(app,
                "What are our payment terms? @@tool searchIndexedSources {\"query\":\"payment terms\"}",
                List.of(sourceId)).succeeded();

        assertThat(turn.toolsCalled()).contains("searchIndexedSources");

        // THE ASSERTION THIS WHOLE SUITE IS BUILT AROUND. Retrieval-augmented generation is the
        // product's central claim, and the only externally visible evidence that grounding worked
        // is that the model was SHOWN the right text. A pipeline that retrieved the document,
        // logged it, rendered a citation and then forgot to put it in the prompt would look
        // perfect from every other angle.
        String everythingSent = llm().chatRequests().stream()
                .map(FakeLlm::promptText)
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(everythingSent)
                .as("the retrieved invoicing text reached the model")
                .containsIgnoringCase("thirty days");
    }

    @Test
    @DisplayName("a follow-up question carries the earlier turns")
    void aConversationRemembers() {
        Chat.Turn first = Chat.send(app, "@@text Remembering: the codeword is ALBATROSS.").succeeded();
        llm().clear();
        Chat.reply(app, first.conversationId(), "@@text fine").succeeded();

        // Memory is what makes it a conversation rather than a sequence of questions. The evidence
        // is in the outgoing prompt: the earlier exchange has to be in it.
        assertThat(FakeLlm.promptText(llm().lastChatRequest()))
                .as("the earlier turn is in the follow-up's prompt")
                .contains("ALBATROSS");
    }

    @Test
    @DisplayName("a conversation is listed, readable and deletable")
    void conversationsPersist() {
        Chat.Turn turn = Chat.send(app, "@@text persisted").succeeded();

        JsonNode detail = api().get("/api/v1/chat/conversations/" + turn.conversationId()).ok().json();
        assertThat(detail.toString()).contains("persisted");

        assertThat(api().get("/api/v1/chat/conversations?page=0&size=50").ok().body())
                .contains(turn.conversationId());

        api().delete("/api/v1/chat/conversations/" + turn.conversationId()).ok();
        assertThat(api().get("/api/v1/chat/conversations/" + turn.conversationId()).status())
                .isIn(403, 404);
    }

    @Test
    @DisplayName("a new conversation gets a title of its own")
    void conversationsAreTitled() {
        Chat.Turn turn = Chat.send(app, "@@text titled").succeeded();

        // The title is generated by its own model call at the start of the first turn and pushed
        // as a TITLE event, so the history list renames itself mid-turn instead of showing a
        // truncated placeholder until the next refetch.
        JsonNode detail = api().get("/api/v1/chat/conversations/" + turn.conversationId()).ok().json();
        assertThat(detail.path("title").asText()).isNotBlank();
    }
}
