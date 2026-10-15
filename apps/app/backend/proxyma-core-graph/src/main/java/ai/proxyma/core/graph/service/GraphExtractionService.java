package ai.proxyma.core.graph.service;

import ai.proxyma.core.common.concurrent.BlockingWork;
import ai.proxyma.core.common.spend.SpendContext;
import ai.proxyma.core.common.usage.TokenUsage;
import ai.proxyma.core.provider.gateway.LlmGateway;
import ai.proxyma.core.provider.gateway.LlmCaller;
import ai.proxyma.core.provider.gateway.LlmRole;
import ai.proxyma.core.settings.dto.GraphSettings;
import ai.proxyma.core.settings.service.SettingsService;
import ai.proxyma.core.settings.usage.GlobalQuotaExceededException;
import ai.proxyma.core.settings.usage.GlobalQuotaGate;
import ai.proxyma.core.settings.usage.PlatformService;
import ai.proxyma.core.settings.usage.PlatformSpendService;
import ai.proxyma.core.settings.usage.UserBudgetExceededException;
import ai.proxyma.core.system.util.Failures;
import ai.proxyma.core.system.util.SensitivePaths;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * One LLM call per chunk: entities and relations out of text.
 *
 * <p><b>This is the class that spends the money.</b> It runs once per chunk of every document of
 * every connector that opted in, so everything here is written to be cheap or to fail cheaply:
 * CONCISE tone, an output ceiling from settings, a hard timeout, and gleaning rounds the operator
 * chooses rather than a number hard-coded here.
 *
 * <p>The shape follows Microsoft GraphRAG's construction half - extract, then glean - which is
 * what proxyma-docs/architecture/knowledge-graph.md settled on after the 2026-08-15 reversal away
 * from cheap NER. The community-summarization half of GraphRAG is deliberately absent: it is a
 * separate phase in that document and it carries an unresolved question (no mature Leiden for
 * Java) that construction does not need to wait on.
 *
 * <h2>Failure is always local</h2>
 *
 * A chunk whose extraction fails, times out or comes back unparseable returns
 * {@link ExtractedGraph#empty()} rather than throwing. One bad chunk must not fail a document's
 * ingest - the document is still searchable by every existing channel, and a graph missing one
 * chunk is exactly as usable as a graph missing that chunk for any other reason.
 *
 * <p>The ONE exception is a budget refusal - the deployment's, or the source owner's own - which
 * throws on purpose. It is not a bad chunk, it is spending that may not happen, and it has to reach
 * the extraction worker so the document's graph is retried when the budget allows instead of being
 * silently recorded as finished with a third of it missing. It never reaches the document's
 * indexing status - see KnowledgeGraphService.refreshDocument. The gateway's own check raised it on
 * a pool thread, where it arrived wrapped and was read as "the model is unreachable" - one owner
 * over budget stood the worker down for everybody.
 */
@Service
public class GraphExtractionService {

    private static final Logger log = LoggerFactory.getLogger(GraphExtractionService.class);

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // The per-call ceiling moved to GraphSettings.callTimeoutSeconds on 2026-09-06. ChatModel.call()
    // still has no timeout of its own and one is still required - a single unresponsive endpoint
    // stalls a whole sync - but 120 seconds is a hosted-model number, and a local reasoning model
    // exceeds it on ordinary chunks. A floor of 5s is applied at the call site so a mistyped 0
    // cannot disable the bound this comment exists to justify.

    /** Above this, gleaning is refused outright: each round is another full call over the same
     *  chunk, so the cost is linear in this number across the entire corpus. */
    private static final int MAX_GLEANINGS = 5;

    private static final String SYSTEM = """
            You extract a knowledge graph from a passage of a document. You return JSON and nothing else.

            Return exactly this shape:
            {"entities":[{"name":"","type":"","description":"","aliases":[""]}],
             "relations":[{"subject":"","predicate":"","object":"","description":""}]}

            LANGUAGE: write every `name` and `aliases` entry EXACTLY as the passage writes it, in its
            own language and script, diacritics included - never translated, never transliterated.
            Write each `description` in the passage's language. Write `type` and `predicate` in
            English, so they compare across documents written in different languages.

            ENTITIES - the things the passage is about: people, teams, systems, services, products,
            organizations, places, documents. Rules:
            - `name` is the fullest form the passage actually uses. Never invent a fuller one.
            - `type` is a short lowercase English noun you choose (person, team, service, product,
              ...). Leave it "" when the passage does not make the kind of thing clear. Do not guess.
            - `description` is one short sentence: what the passage says this entity is or does.
            - `aliases` are OTHER strings this same passage uses for that same entity - an acronym, a
              short form, a hostname. Only what is written here. Do not invent spellings.
            - Skip pronouns, generic phrases ("the system", "the team") and anything you cannot name.

            RELATIONS - what the passage states between two entities it named. Rules:
            - `subject` and `object` MUST both appear in your entities list, spelled identically.
            - `predicate` is a short lowercase English verb phrase: "owns", "depends on", "reports to".
            - `description` is the passage's own wording for that link, one sentence, quoted or close.
            - ONLY what the passage states. Not what you know, not what is likely, not what is implied
              by two things being mentioned together. An empty list is a correct answer.

            Return the JSON object alone. No prose, no markdown fence, no explanation.
            """;

    private static final String GLEAN = """
            Some entities and relations in that same passage were missed. Return ONLY the ones you did
            not already list, in the same JSON shape. If you missed nothing, return
            {"entities":[],"relations":[]}. Do not repeat anything you already returned, and do not
            lower your standards to find something - returning nothing is the right answer when there
            is nothing left.
            """;

    private final LlmGateway llmGateway;
    private final SettingsService settingsService;
    private final PlatformSpendService platformSpendService;
    private final GlobalQuotaGate quotaGate;

    public GraphExtractionService(LlmGateway llmGateway,
                                  SettingsService settingsService,
                                  PlatformSpendService platformSpendService,
                                  GlobalQuotaGate quotaGate) {
        this.llmGateway = llmGateway;
        this.settingsService = settingsService;
        this.platformSpendService = platformSpendService;
        this.quotaGate = quotaGate;
    }

    /**
     * Whether extraction can run at all right now: the master switch is on AND a graph model
     * actually resolved. Both, because `graph.enabled` with a model that failed to build would
     * otherwise make every chunk take the timeout before returning nothing.
     */
    public boolean isAvailable() {
        return settings().isEnabled() && llmGateway.isAvailable(LlmRole.GRAPH);
    }

    /**
     * One chunk in, entities and relations out - and WHY it is empty when it is.
     *
     * <p>The outcome is part of the answer since 2026-09-16. An empty graph used to mean either
     * "the model read this and found nothing" or "no call ever reached a model", and the caller
     * could not tell the two apart - so a whole corpus extracted during an outage was recorded as
     * read, in half a second, with nothing in the graph. See {@link GraphModelUnreachableException}.
     *
     * <p>Never throws except for quota - see the class note.
     */
    public Attempt extractChunk(String chunkText) {
        if (chunkText == null || chunkText.isBlank()) return Attempt.empty(Outcome.EMPTY);

        if (!llmGateway.isAvailable(LlmRole.GRAPH)) {
            lastFailure = "no knowledge-graph model is configured";
            return Attempt.empty(Outcome.UNREACHABLE);
        }

        // Before the first call of the chunk, not before each gleaning round: a chunk that has
        // already spent its first call should finish the passes it started rather than contribute
        // a half-extracted result that looks complete.
        quotaGate.checkOrRefuse("extracting the knowledge graph from this document");

        GraphSettings s = settings();
        // Read ONCE, here, on the thread GraphExtractionOrchestrator put the source's owner on,
        // and passed to every call of the chunk - the calls themselves run on pool threads.
        LlmCaller caller = LlmCaller.of(LlmRole.GRAPH, SpendContext.current());
        Attempt first = callAndParse(List.of(
                new SystemMessage(SYSTEM),
                new UserMessage("PASSAGE:\n" + chunkText)), s, caller);
        // The FIRST call decides the outcome. A gleaning round that fails after a successful pass
        // costs recall, not correctness: the chunk did reach a model, and what it said is kept.
        if (first.outcome() == Outcome.UNREACHABLE) return first;

        ExtractedGraph result = first.graph();
        int gleanings = Math.min(Math.max(0, s.getGleanings()), MAX_GLEANINGS);
        for (int i = 0; i < gleanings; i++) {
            Attempt more = callAndParse(List.of(
                    new SystemMessage(SYSTEM),
                    new UserMessage("PASSAGE:\n" + chunkText),
                    new UserMessage("You already returned:\n" + toJson(result) + "\n\n" + GLEAN)), s, caller);
            if (more.graph().isEmpty()) break;   // nothing left to find; further rounds are pure cost
            result = result.merge(more.graph());
        }
        return new Attempt(result, result.isEmpty() ? Outcome.EMPTY : Outcome.OK);
    }

    /** What one chunk's extraction produced, and what happened while producing it. */
    public record Attempt(ExtractedGraph graph, Outcome outcome) {
        static Attempt empty(Outcome outcome) { return new Attempt(ExtractedGraph.empty(), outcome); }
    }

    public enum Outcome {
        /** The model answered and named something. */
        OK,
        /** The model answered with nothing, or with something unreadable. A real answer. */
        EMPTY,
        /** No answer at all: refused, reset, timed out, or no model resolved for the role. */
        UNREACHABLE
    }

    /**
     * Why the last call that reached no model failed, in words - "no answer within 120 seconds", a
     * refused connection - for whoever has to say why the graph is not moving. Null until one has.
     */
    private volatile String lastFailure;

    public String lastFailure() {
        return lastFailure;
    }

    private Attempt callAndParse(List<org.springframework.ai.chat.messages.Message> messages,
                                 GraphSettings s, LlmCaller caller) {
        int timeoutSeconds = Math.max(5, s.getCallTimeoutSeconds());
        try {
            LlmGateway.LlmResult result = CompletableFuture
                    .supplyAsync(() -> llmGateway.chat(caller, messages), BlockingWork.executor())
                    .get(timeoutSeconds, TimeUnit.SECONDS);

            recordSpend(result);

            ExtractedGraph parsed = parse(result.text());
            return new Attempt(parsed, parsed.isEmpty() ? Outcome.EMPTY : Outcome.OK);

        } catch (java.util.concurrent.TimeoutException e) {
            // Named separately from the rest because it is the one failure here an operator can
            // act on - raise graph.call-timeout-seconds, or point the role at a faster model. It
            // was indistinguishable from "the model found nothing", both being a DEBUG line and an
            // empty graph; KnowledgeGraphService now warns when a whole document comes back empty.
            log.debug("[Graph] extraction call timed out after {}s - raise graph.call-timeout-seconds "
                    + "or use a faster model", timeoutSeconds);
            lastFailure = "no answer within " + timeoutSeconds + " seconds";
            // UNREACHABLE rather than EMPTY: a chunk that ran out of time was never read, so a
            // document made entirely of them is one to try again, not one to record as finished.
            return Attempt.empty(Outcome.UNREACHABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Attempt.empty(Outcome.UNREACHABLE);
        } catch (Exception e) {
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            // A budget refusal is not a model that did not answer - see the class comment.
            if (cause instanceof GlobalQuotaExceededException quota) throw quota;
            if (cause instanceof UserBudgetExceededException budget) throw budget;
            // Debug rather than warn: on a corpus where the model is unreliable this would
            // otherwise log once per chunk, and the operator-visible signal is the graph staying
            // empty, which the per-connector counts already show.
            log.debug("[Graph] extraction call failed: {}", cause.getMessage());
            lastFailure = SensitivePaths.scrub(Failures.describe(cause, "the call failed with no reason given"));
            return Attempt.empty(Outcome.UNREACHABLE);
        }
    }

    /**
     * Tolerant by design. Models fence JSON in markdown, prefix it with a sentence, or emit one
     * object where a list was asked for, and a strict parse would discard a usable extraction over
     * presentation. Anything genuinely unreadable degrades to empty.
     */
    static ExtractedGraph parse(String raw) {
        if (raw == null || raw.isBlank()) return ExtractedGraph.empty();
        String body = raw.trim();

        // ```json ... ``` and bare ``` ... ```
        if (body.startsWith("```")) {
            int firstNewline = body.indexOf('\n');
            int lastFence = body.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                body = body.substring(firstNewline + 1, lastFence).trim();
            }
        }
        // A leading sentence before the object. Take the outermost braces rather than the first
        // one, so a nested object cannot truncate the parse.
        int open = body.indexOf('{');
        int close = body.lastIndexOf('}');
        if (open < 0 || close <= open) return ExtractedGraph.empty();
        body = body.substring(open, close + 1);

        try {
            ExtractedGraph parsed = JSON.readValue(body, ExtractedGraph.class);
            if (parsed == null) return ExtractedGraph.empty();
            return new ExtractedGraph(
                    parsed.entities() == null ? List.of() : parsed.entities(),
                    parsed.relations() == null ? List.of() : parsed.relations());
        } catch (Exception e) {
            log.debug("[Graph] unparseable extraction result: {}", e.getMessage());
            return ExtractedGraph.empty();
        }
    }

    private String toJson(ExtractedGraph g) {
        try {
            return JSON.writeValueAsString(g);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * GRAPH_EXTRACTION on the Usages breakdown, separable from EMBEDDING - which is the whole point
     * of giving it its own PlatformService constant. Anyone judging whether this feature earns its
     * keep needs its number alone.
     *
     * <p>Bookkeeping never fails an ingest, matching OcrService.recordOcrSpend: one unrecorded cost
     * is a smaller problem than a document that failed to index because a counter could not be
     * written.
     */
    private void recordSpend(LlmGateway.LlmResult result) {
        try {
            TokenUsage usage = result.usage();
            if (usage == TokenUsage.EMPTY) return;
            platformSpendService.record(PlatformService.GRAPH_EXTRACTION, usage,
                    result.provider(), result.model());
        } catch (Exception e) {
            log.debug("[PlatformSpend] Could not record graph extraction spend: {}", e.getMessage());
        }
    }

    private GraphSettings settings() {
        return settingsService.getSettings(GraphSettings.PREFIX, GraphSettings.class);
    }
}
