package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.domain.MessageRole;
import ai.proxyma.core.chat.model.ChatMessage;
import ai.proxyma.core.chat.model.Conversation;
import ai.proxyma.core.chat.model.ConversationShare;
import ai.proxyma.core.chat.agents.AgentInbox;
import ai.proxyma.core.chat.agents.AgentTree;
import ai.proxyma.core.chat.model.AgentNode;
import ai.proxyma.core.chat.repository.ChatMessageRepository;
import ai.proxyma.core.chat.repository.ConversationRepository;
import ai.proxyma.core.chat.repository.ConversationShareRepository;
import ai.proxyma.core.chat.repository.JpaChatMemoryRepository;
import ai.proxyma.core.chat.repository.AgentNodeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Writes a RECORDED conversation back as real rows, with a share link to its final answer.
 *
 * <p>For the public Enterprise sandbox, which is wiped every night and should still open on a
 * finished example run (SandboxScenarioSeeder, in proxyma-core-auth). The input is the JSON
 * {@code tools/dev/export-conversation.mjs} writes from a real run on a running instance, through
 * the same endpoints the chat page reads - so the example is a run that happened, not one written
 * by hand.
 *
 * <p><b>THE FORMAT</b> ({@code "format": "proxyma-conversation", "version": 1}) is the TRANSCRIPT a
 * page is given, not the stored rows, because the transcript is all the REST API hands out:
 * <pre>
 * { "format": "proxyma-conversation", "version": 1,
 *   "title": "...", "createdAt": ms, "updatedAt": ms,
 *   "usage": { "inputTokens", "outputTokens", "cacheReadTokens", "cacheWriteTokens",
 *              "contextTokens", "costUsd" },                                    (optional)
 *   "systemPrompt": "...",                                                      (optional)
 *   "messages": [
 *     { "role": "user", "content": "...", "skills": ["..."], "at": ms },
 *     { "role": "assistant", "content": "...", "at": ms, "stepLimit": n,
 *       "toolCalls": [ { "id", "name", "arguments", "result", "done", "startedAt", "endedAt",
 *                        "agentId" } ] },                 (agentId only on a `delegate` call)
 *     { "role": "reports", "at": ms, "agents": ["agent id", ...] } ],
 *   "subAgents": [ { "id", "title", "mode", "status", "startedAt", "endedAt", "report",
 *                    "items": [SubAgentView.Item...], "provider", "model", "inputTokens",
 *                    "outputTokens", "cacheReadTokens", "cacheWriteTokens", "costUsd",
 *                    "contextTokens", "contextWindow" } ] }
 * </pre>
 * Times are epoch milliseconds. An assistant message's {@code toolCalls} are the calls the
 * transcript shows ABOVE it - the ones it claimed - so each becomes an ASSISTANT row asking for
 * them, a TOOL row answering them (with the {@code toolTimings} the transcript reads durations
 * from), and then the ASSISTANT row with the text. ChatService.transcript reads that back as the
 * same message with the same tool rows. A {@code reports} entry is the marked USER row a
 * background sub-agent's report arrives in.
 *
 * <p><b>A RECORDED SUB-AGENT IS WRITTEN AS A DEPLOYED AGENT</b> (AgentTree): an agent row under a
 * fresh id, finished, with a conversation of its own holding its task and its report, and its
 * {@code delegate} call is the {@code deployAgent} call that started it, its result naming the new
 * agent and conversation. Its report row is the inbox message an agent's report arrives in. A
 * recorded id copied verbatim would collide with itself on a second import into a database that
 * kept the first.
 *
 * <p><b>THE TIMES ARE MOVED</b> so the run ended a few minutes before the import: a visitor reads an
 * example that happened today, not one dated the day it was recorded.
 *
 * <p>Writes through the chat module's own repositories, inside one transaction on the chat
 * datasource; nothing outside this module touches them.
 */
@Service
public class ExampleConversationImporter {

    private static final Logger log = LoggerFactory.getLogger(ExampleConversationImporter.class);

    public static final String FORMAT = "proxyma-conversation";
    public static final int VERSION = 1;

    /** How long before the import the recorded run is made to have ended. */
    static final long ENDED_BEFORE_IMPORT_MS = 10 * 60 * 1000L;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final AgentNodeRepository agentNodes;
    private final ConversationShareRepository shares;

    public ExampleConversationImporter(ConversationRepository conversations, ChatMessageRepository messages,
                                       AgentNodeRepository agentNodes, ConversationShareRepository shares) {
        this.conversations = conversations;
        this.messages = messages;
        this.agentNodes = agentNodes;
        this.shares = shares;
    }

    /** What an import wrote: the conversation, and the message its share link ends at. */
    public record Imported(String conversationId, long sharedUptoMessageId, int rows, int subAgents) {}

    /** Whether a share link with this token exists - the import's idempotency check. */
    public boolean shareExists(String shareToken) {
        return shares.findById(shareToken).isPresent();
    }

    /**
     * Writes the conversation, owned and pinned by {@code ownerUserId}, with a share link under
     * {@code shareToken} covering it up to its final answer. Empty when that token already exists
     * (it was imported before); throws on a document that is not this format.
     */
    @Transactional("chatTransactionManager")
    public Optional<Imported> importConversation(String json, String ownerUserId, String ownerName,
                                                 String shareToken, Instant now) {
        if (shareExists(shareToken)) return Optional.empty();
        Plan plan = plan(read(json), UUID.randomUUID().toString(), ownerUserId, now,
                () -> UUID.randomUUID().toString());
        if (plan.finalAnswerIndex() < 0) {
            throw new IllegalArgumentException("the recorded conversation has no answer to share");
        }
        conversations.save(plan.conversation());
        Long sharedId = null;
        for (int i = 0; i < plan.rows().size(); i++) {
            ChatMessage saved = messages.save(plan.rows().get(i));
            if (i == plan.finalAnswerIndex()) sharedId = saved.getId();
        }
        for (Agent a : plan.subAgents()) {
            agentNodes.save(a.node());
            conversations.save(a.conversation());
            for (ChatMessage row : a.rows()) messages.save(row);
        }

        ConversationShare share = new ConversationShare();
        share.setToken(shareToken);
        share.setConversationId(plan.conversation().getId());
        share.setUptoMessageId(sharedId);
        share.setCreatedByUserId(ownerUserId);
        share.setCreatedByName(ownerName);
        share.setCreatedAt(plan.conversation().getUpdatedAt());
        shares.save(share);
        log.info("[Chat] imported example conversation '{}' ({} rows, {} agent(s)), shared as {}",
                plan.conversation().getTitle(), plan.rows().size(), plan.subAgents().size(), shareToken);
        return Optional.of(new Imported(plan.conversation().getId(), sharedId, plan.rows().size(),
                plan.subAgents().size()));
    }

    // -- The rows, as a function of the document -------------------------------------------------

    /** The rows an import writes, in order; {@code finalAnswerIndex} is the row the share ends at. */
    record Plan(Conversation conversation, List<ChatMessage> rows, List<Agent> subAgents,
                int finalAnswerIndex) {}

    /** A recorded sub-agent as a finished deployed agent: its row, its conversation and its two rows. */
    record Agent(AgentNode node, Conversation conversation, List<ChatMessage> rows) {}

    static JsonNode read(String json) {
        JsonNode doc;
        try {
            doc = JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("not JSON: " + e.getMessage());
        }
        if (doc == null || !FORMAT.equals(doc.path("format").asText(null))) {
            throw new IllegalArgumentException("not a " + FORMAT + " document");
        }
        if (doc.path("version").asInt(-1) != VERSION) {
            throw new IllegalArgumentException("unsupported " + FORMAT + " version " + doc.path("version")
                    + " (this build reads " + VERSION + ")");
        }
        if (!doc.path("messages").isArray() || doc.path("messages").isEmpty()) {
            throw new IllegalArgumentException("the document has no messages");
        }
        return doc;
    }

    static Plan plan(JsonNode doc, String conversationId, String ownerUserId, Instant now, Supplier<String> newIds) {
        long recordedEnd = recordedEnd(doc);
        long shift = now.toEpochMilli() - ENDED_BEFORE_IMPORT_MS - recordedEnd;

        // Recorded sub-agent id -> the id it is written under.
        Map<String, String> agentIds = new HashMap<>();
        Map<String, JsonNode> agentsById = new LinkedHashMap<>();
        for (JsonNode agent : doc.path("subAgents")) {
            String id = agent.path("id").asText(null);
            if (id == null || id.isBlank()) continue;
            agentIds.put(id, newIds.get());
            agentsById.put(id, agent);
        }
        // Recorded sub-agent id -> the conversation it is written as, and the task its delegate gave.
        Map<String, String> conversationIds = new HashMap<>();
        for (String recorded : agentIds.keySet()) conversationIds.put(recorded, newIds.get());
        Map<String, String> taskByAgent = new HashMap<>();

        List<ChatMessage> rows = new ArrayList<>();
        Clock clock = new Clock(shift);
        int finalAnswer = -1;

        String system = doc.path("systemPrompt").asText(null);
        if (system != null && !system.isBlank()) {
            rows.add(row(conversationId, MessageRole.SYSTEM, system, clock.at(doc.path("createdAt").asLong(0))));
        }

        for (JsonNode m : doc.path("messages")) {
            String role = m.path("role").asText("");
            long at = m.path("at").asLong(0);
            switch (role) {
                case "user" -> {
                    String content = m.path("content").asText("");
                    List<String> skills = strings(m.path("skills"));
                    if (!skills.isEmpty()) content = content + ChatService.skillMentionBlock(skills);
                    rows.add(row(conversationId, MessageRole.USER, content, clock.at(at)));
                }
                case "reports" -> {
                    List<AgentInbox.Taken> settled = new ArrayList<>();
                    for (String recorded : strings(m.path("agents"))) {
                        JsonNode agent = agentsById.get(recorded);
                        if (agent == null) continue;
                        settled.add(new AgentInbox.Taken(null, AgentInbox.Kind.REPORT,
                                UUID.fromString(agentIds.get(recorded)), agent.path("title").asText("Agent"),
                                agent.path("report").asText(""), clock.at(at)));
                    }
                    if (!settled.isEmpty()) {
                        rows.add(row(conversationId, MessageRole.USER, AgentInbox.render(settled), clock.at(at)));
                    }
                }
                case "assistant" -> {
                    JsonNode calls = m.path("toolCalls");
                    if (calls.isArray() && !calls.isEmpty()) {
                        long firstStart = Long.MAX_VALUE;
                        long lastEnd = 0;
                        List<ToolCall> asked = new ArrayList<>();
                        List<ToolResponse> answered = new ArrayList<>();
                        Map<String, long[]> timings = new LinkedHashMap<>();
                        for (JsonNode call : calls) {
                            String id = call.path("id").asText(null);
                            String name = call.path("name").asText(null);
                            if (id == null || name == null) continue;
                            String arguments = call.path("arguments").asText("{}");
                            String result = call.path("result").isNull() ? null : call.path("result").asText(null);
                            String recordedAgent = call.path("agentId").asText(null);
                            if (recordedAgent == null) recordedAgent = agentIdIn(result);
                            if (recordedAgent != null && agentIds.containsKey(recordedAgent)) {
                                // The delegate that started it is the deployAgent that would have.
                                name = DEPLOY;
                                result = deployResult(agentIds.get(recordedAgent), conversationIds.get(recordedAgent));
                                taskByAgent.put(recordedAgent, taskIn(arguments));
                            }
                            asked.add(new ToolCall(id, "function", name, arguments));
                            if (call.path("done").asBoolean(result != null)) {
                                answered.add(new ToolResponse(id, name, result == null ? "" : result));
                            }
                            long started = call.path("startedAt").asLong(0);
                            long ended = call.path("endedAt").asLong(0);
                            if (started > 0) firstStart = Math.min(firstStart, started);
                            if (ended > 0) lastEnd = Math.max(lastEnd, ended);
                            if (started > 0 && ended > 0) {
                                timings.put(id, new long[] {started + shift, ended + shift});
                            }
                        }
                        ChatMessage ask = row(conversationId, MessageRole.ASSISTANT, "",
                                clock.at(firstStart == Long.MAX_VALUE ? at : firstStart));
                        ask.setToolCallsJson(json(asked));
                        rows.add(ask);
                        if (!answered.isEmpty()) {
                            ChatMessage answer = row(conversationId, MessageRole.TOOL, null,
                                    clock.at(lastEnd == 0 ? at : lastEnd));
                            answer.setToolResponsesJson(json(answered));
                            if (!timings.isEmpty()) {
                                answer.setMetadataJson(json(Map.of(JpaChatMemoryRepository.TOOL_TIMINGS, timings)));
                            }
                            rows.add(answer);
                        }
                    }
                    String content = m.path("content").asText("");
                    if (!content.isBlank()) {
                        ChatMessage text = row(conversationId, MessageRole.ASSISTANT, content, clock.at(at));
                        if (m.path("stepLimit").isInt()) {
                            text.setMetadataJson(JpaChatMemoryRepository.withStepLimit(null, m.path("stepLimit").asInt()));
                        }
                        rows.add(text);
                        finalAnswer = rows.size() - 1;
                    }
                }
                default -> throw new IllegalArgumentException("unknown message role '" + role + "'");
            }
        }

        List<Agent> runs = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : agentsById.entrySet()) {
            runs.add(agent(e.getValue(), agentIds.get(e.getKey()), conversationIds.get(e.getKey()), conversationId,
                    ownerUserId, taskByAgent.get(e.getKey()), shift));
        }

        Conversation conversation = new Conversation();
        conversation.setId(conversationId);
        conversation.setUserId(ownerUserId);
        conversation.setTitle(doc.path("title").asText("Example conversation"));
        long created = doc.path("createdAt").asLong(0);
        conversation.setCreatedAt(rows.isEmpty() || created <= 0
                ? Instant.ofEpochMilli(recordedEnd + shift) : Instant.ofEpochMilli(created + shift));
        conversation.setUpdatedAt(Instant.ofEpochMilli(recordedEnd + shift));
        // Pinned: the retention sweep deletes an unpinned conversation idle for a week, and this one
        // is idle from the moment it is written.
        conversation.setPinned(true);
        conversation.setMessageCount((int) rows.stream()
                .filter(r -> r.getRole() == MessageRole.USER || r.getRole() == MessageRole.ASSISTANT).count());
        JsonNode usage = doc.path("usage");
        conversation.setInputTokens(usage.path("inputTokens").asLong(0));
        conversation.setOutputTokens(usage.path("outputTokens").asLong(0));
        conversation.setCacheReadTokens(usage.path("cacheReadTokens").asLong(0));
        conversation.setCacheWriteTokens(usage.path("cacheWriteTokens").asLong(0));
        conversation.setContextTokens(usage.path("contextTokens").asInt(0));
        conversation.setEstimatedCostUsd(usage.path("costUsd").asDouble(0));
        return new Plan(conversation, rows, runs, finalAnswer);
    }

    static final String DEPLOY = "deployAgent";

    private static Agent agent(JsonNode agent, String id, String agentConversationId, String parentConversationId,
                               String ownerUserId, String task, long shift) {
        long started = agent.path("startedAt").asLong(0);
        long ended = agent.path("endedAt").asLong(started);
        Instant startedAt = Instant.ofEpochMilli((started > 0 ? started : ended) + shift);
        Instant endedAt = Instant.ofEpochMilli((ended > 0 ? ended : started) + shift);
        String title = agent.path("title").asText("Agent");
        String report = agent.path("report").isNull() ? "" : agent.path("report").asText("");

        AgentNode n = new AgentNode();
        n.setId(UUID.fromString(id));
        n.setConversationId(agentConversationId);
        n.setParentConversationId(parentConversationId);
        n.setRootConversationId(parentConversationId);
        n.setUserId(ownerUserId);
        n.setDepth(1);
        n.setLifetime(AgentNode.SHORT);
        n.setTitle(title.length() <= AgentNode.TITLE_MAX ? title : title.substring(0, AgentNode.TITLE_MAX));
        n.setTask(task == null || task.isBlank() ? title : task);
        n.setModel(agent.path("model").asText(null));
        n.setStatus(switch (agent.path("status").asText("COMPLETED").toUpperCase(Locale.ROOT)) {
            case "FAILED" -> AgentNode.FAILED;
            case "STOPPED" -> AgentNode.STOPPED;
            default -> AgentNode.DONE;
        });
        // Its report reached the conversation when it was recorded; nothing is owed for it now.
        n.setReportOwed(false);
        n.setLastReport(report);
        n.setCreatedAt(startedAt);
        n.setEndedAt(endedAt);

        Conversation c = new Conversation();
        c.setId(agentConversationId);
        c.setUserId(ownerUserId);
        c.setTitle(n.getTitle());
        c.setSurface(AgentTree.SURFACE);
        c.setPinned(true);
        c.setCreatedAt(startedAt);
        c.setUpdatedAt(endedAt);
        c.setMessageCount(2);
        c.setInputTokens(agent.path("inputTokens").asLong(0));
        c.setOutputTokens(agent.path("outputTokens").asLong(0));
        c.setCacheReadTokens(agent.path("cacheReadTokens").asLong(0));
        c.setCacheWriteTokens(agent.path("cacheWriteTokens").asLong(0));
        c.setContextTokens((int) Math.min(Integer.MAX_VALUE, agent.path("contextTokens").asLong(0)));
        c.setEstimatedCostUsd(agent.path("costUsd").asDouble(0));

        List<ChatMessage> rows = List.of(
                row(agentConversationId, MessageRole.USER, n.getTask(), startedAt),
                row(agentConversationId, MessageRole.ASSISTANT, report.isBlank() ? "(no report)" : report, endedAt));
        return new Agent(n, c, rows);
    }

    // What deployAgent returns, naming the agent and its conversation.
    static String deployResult(String agentId, String conversationId) {
        return json(Map.of("success", true, "agentId", agentId, "conversationId", conversationId,
                "status", AgentNode.DONE));
    }

    // The task a recorded delegate call gave, from its arguments.
    static String taskIn(String arguments) {
        try {
            JsonNode a = JSON.readTree(arguments);
            String task = a.path("task").asText(null);
            String instructions = a.path("instructions").asText(null);
            if (task == null) return instructions;
            return instructions == null || instructions.isBlank() ? task : instructions + "\n\n" + task;
        } catch (Exception e) {
            return null;
        }
    }

    private static long recordedEnd(JsonNode doc) {
        long end = Math.max(doc.path("updatedAt").asLong(0), doc.path("createdAt").asLong(0));
        for (JsonNode m : doc.path("messages")) {
            end = Math.max(end, m.path("at").asLong(0));
            for (JsonNode call : m.path("toolCalls")) end = Math.max(end, call.path("endedAt").asLong(0));
        }
        for (JsonNode a : doc.path("subAgents")) end = Math.max(end, a.path("endedAt").asLong(0));
        return end > 0 ? end : System.currentTimeMillis();
    }

    static String agentIdIn(String result) {
        if (result == null || !result.contains("\"agentId\"")) return null;
        try {
            return JSON.readTree(result).path("agentId").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    // The delegate call's result naming the sub-agent's NEW id - which is how the transcript finds
    // its panel (ChatService.subAgentFor).
    static String withAgentId(String result, String agentId) {
        try {
            JsonNode node = result == null ? null : JSON.readTree(result);
            ObjectNode obj = node instanceof ObjectNode o ? o : JSON.createObjectNode();
            obj.put("agentId", agentId);
            return obj.toString();
        } catch (Exception e) {
            return JSON.createObjectNode().put("agentId", agentId).toString();
        }
    }

    private static ChatMessage row(String conversationId, MessageRole role, String content, Instant at) {
        ChatMessage m = new ChatMessage();
        m.setConversationId(conversationId);
        m.setRole(role);
        m.setContent(content);
        m.setCreatedAt(at);
        return m;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array.isArray()) for (JsonNode n : array) if (n.isTextual() && !n.asText().isBlank()) out.add(n.asText());
        return out;
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialize: " + e.getMessage(), e);
        }
    }

    /**
     * Strictly increasing row times, moved by {@code shift}: the transcript reads rows in
     * (createdAt, id) order, and two rows recorded at one millisecond - or a message whose
     * recorded time is earlier than its own tool calls' - must still come out in document order.
     */
    private static final class Clock {
        private final long shift;
        private Instant last = Instant.EPOCH;

        Clock(long shift) {
            this.shift = shift;
        }

        Instant at(long recordedMs) {
            Instant wanted = recordedMs > 0 ? Instant.ofEpochMilli(recordedMs + shift) : last;
            Instant next = wanted.isAfter(last) ? wanted : last.plus(1, ChronoUnit.MICROS);
            last = next;
            return next;
        }
    }
}
