package ai.proxyma.it.harness;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The fake AI provider, started and controlled from a test.
 *
 * <p>Wraps {@code tools/testing/fake-llm.mjs} - see that file's header for the wire protocol and
 * the directive language. This class adds the two things a Java test needs: a lifecycle, and the
 * ability to read back what the product actually SENT.
 *
 * <h2>Why reading the requests matters more than reading the answers</h2>
 *
 * Proxyma's real work happens before the model is called: deciding which documents to retrieve,
 * building the prompt, choosing which tools to offer, applying the guardrail, attributing the cost
 * to a caller. The answer a model gives is the one part of the system this project does not own.
 *
 * <p>So the most valuable assertions in this suite are not about the reply - they are about the
 * request. Was the retrieved document actually IN the prompt. Was {@code stream_options} set. Were
 * the write tools withheld from a user who never consented. Was the embedding model the one the
 * index was built with. Every one of those is a question about the outgoing HTTP call, and
 * {@link #chatRequests()} is how a test asks it.
 */
public final class FakeLlm implements AutoCloseable {

    private final ManagedProcess process;
    private final Http control;
    private final String baseUrl;

    /** The models {@code /v1/models} advertises. Named for the role, never for a vendor. */
    public static final String CHAT_MODEL = "fake-chat";
    public static final String SMALL_CHAT_MODEL = "fake-chat-mini";
    public static final String EMBEDDING_MODEL = "fake-embed";
    public static final String RERANK_MODEL = "fake-rerank";

    private FakeLlm(ManagedProcess process, String baseUrl) {
        this.process = process;
        this.baseUrl = baseUrl;
        this.control = new Http(baseUrl);
    }

    public static FakeLlm start() {
        return start(384);
    }

    /**
     * Starts the provider on a free port of its own choosing.
     *
     * <p>Port 0, not a fixed one. Suites run one after another in the same JVM and a fixed port
     * would collide with a previous run whose socket is still in TIME_WAIT - a failure that
     * appears only under repetition and reads as flakiness.
     *
     * @param dimension the embedding width. Changing it between two runs against one index is
     *                  exactly the model-changed reindex case, which is a journey in its own right.
     */
    public static FakeLlm start(int dimension) {
        Path scratch = Env.scratch("fake-llm");
        ManagedProcess process = ManagedProcess.start("fake-llm", List.of(
                Env.node(),
                Env.repoRoot().resolve("tools/testing/fake-llm.mjs").toString(),
                "--port", "0",
                "--dim", String.valueOf(dimension)
        ), scratch, Map.of());

        // The port comes from the process's own first line. Reading it back rather than choosing
        // it here removes the window between "found a free port" and "bound it" in which something
        // else can take it.
        String line = process.awaitLine(l -> l.startsWith("listening "), Duration.ofSeconds(30));
        String baseUrl = "http://127.0.0.1:" + line.substring("listening ".length()).trim();

        FakeLlm llm = new FakeLlm(process, baseUrl);
        process.awaitReady("the fake provider's control endpoint",
                () -> llm.control.healthy("/_control/health"), Duration.ofSeconds(30));
        return llm;
    }

    /** What to put in a provider instance's base URL. */
    public String baseUrl() { return baseUrl; }

    // -- Reading what the product sent -----------------------------------------

    /** Every request received since the last {@link #clear()}, oldest first. */
    public List<JsonNode> requests() {
        JsonNode body = control.get("/_control/requests").ok().json();
        if (!body.isArray()) return List.of();
        List<JsonNode> all = new java.util.ArrayList<>();
        body.forEach(all::add);
        return all;
    }

    private List<JsonNode> requestsTo(String path) {
        return requests().stream().filter(r -> path.equals(r.path("path").asText())).toList();
    }

    /** Every chat completion the product asked for. */
    public List<JsonNode> chatRequests() { return requestsTo("/v1/chat/completions"); }

    /** Every batch of text the product asked to embed. */
    public List<JsonNode> embeddingRequests() { return requestsTo("/v1/embeddings"); }

    /** Every rerank call. Empty is itself an assertion - the tier is opt-in per instance. */
    public List<JsonNode> rerankRequests() { return requestsTo("/v1/rerank"); }

    /**
     * The most recent chat completion, or a failure naming what WAS seen.
     *
     * <p>The message is deliberately loud. "No chat request reached the provider" almost always
     * means the turn failed before the model - an unset provider, a guardrail block, a quota - and
     * listing the paths that did arrive is usually enough to say which.
     */
    public JsonNode lastChatRequest() {
        List<JsonNode> chats = chatRequests();
        if (chats.isEmpty()) {
            throw new AssertionError("no chat request reached the fake provider. Paths seen: "
                    + requests().stream().map(r -> r.path("path").asText()).toList());
        }
        return chats.get(chats.size() - 1);
    }

    /**
     * Every message of a chat request flattened into one string.
     *
     * <p>This is how a test asks the question that matters most: did the document the retriever
     * found actually reach the model. Flattened rather than inspected role by role because the
     * product has moved context between the system message and the user message before, and a test
     * about GROUNDING should not fail because the prompt was reorganized.
     */
    public static String promptText(JsonNode chatRequest) {
        StringBuilder text = new StringBuilder();
        for (JsonNode message : chatRequest.path("body").path("messages")) {
            JsonNode content = message.path("content");
            text.append(message.path("role").asText()).append(": ");
            if (content.isTextual()) {
                text.append(content.asText());
            } else if (content.isArray()) {
                // The typed-parts shape, which is what an attachment turn sends.
                content.forEach(part -> text.append(part.path("text").asText("")).append(' '));
            }
            text.append('\n');
        }
        return text.toString();
    }

    /** The tool names offered on a chat request - the tool catalog, as the model saw it. */
    public static List<String> toolsOffered(JsonNode chatRequest) {
        List<String> names = new java.util.ArrayList<>();
        chatRequest.path("tools").forEach(name -> names.add(name.asText()));
        return names;
    }

    /** Forgets every recorded request. Called between tests so assertions cannot see stale calls. */
    public void clear() {
        control.delete("/_control/requests").ok();
        control.delete("/_control/script").ok();
    }

    /**
     * Queues replies for turns a test cannot write the prompt for.
     *
     * <p>Most tests steer the model with an in-prompt directive instead - see the fake provider's
     * header. This is for the turns the PRODUCT originates, where no test wrote the message:
     * memory condensation, graph extraction, query expansion and sub-agent calls all send prompts
     * assembled entirely by the backend.
     */
    public void script(Object... replies) {
        control.post("/_control/script", List.of(replies)).ok();
    }

    @Override
    public void close() { process.stop(); }

    public ManagedProcess process() { return process; }
}
