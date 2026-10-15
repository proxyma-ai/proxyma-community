package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.domain.MessageRole;
import ai.proxyma.core.chat.model.ChatMessage;
import ai.proxyma.core.chat.model.Conversation;
import ai.proxyma.core.chat.model.ConversationShare;
import ai.proxyma.core.chat.model.SubAgentRun;
import ai.proxyma.core.chat.repository.ChatMessageRepository;
import ai.proxyma.core.chat.repository.ConversationRepository;
import ai.proxyma.core.chat.repository.ConversationShareRepository;
import ai.proxyma.core.chat.repository.JpaChatMemoryRepository;
import ai.proxyma.core.chat.repository.SubAgentRunRepository;
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
 * <p><b>SUB-AGENTS GET NEW IDS.</b> Each recorded one is written as a SubAgentRun under a fresh id,
 * and every place that names it - its {@code delegate} call's result, which is how the transcript
 * finds the panel, and the report row - is rewritten to that id. A recorded id copied verbatim
 * would collide with itself on a second import into a database that kept the first.
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
    private final SubAgentRunRepository subAgentRuns;
    private final ConversationShareRepository shares;

    public ExampleConversationImporter(ConversationRepository conversations, ChatMessageRepository messages,
                                       SubAgentRunRepository subAgentRuns, ConversationShareRepository shares) {
        this.conversations = conversations;
        this.messages = messages;
        this.subAgentRuns = subAgentRuns;
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
        subAgentRuns.saveAll(plan.subAgents());

        ConversationShare share = new ConversationShare();
        share.setToken(shareToken);
        share.setConversationId(plan.conversation().getId());
        share.setUptoMessageId(sharedId);
        share.setCreatedByUserId(ownerUserId);
        share.setCreatedByName(ownerName);
        share.setCreatedAt(plan.conversation().getUpdatedAt());
        shares.save(share);
        log.info("[Chat] imported example conversation '{}' ({} rows, {} sub-agent(s)), shared as {}",
                plan.conversation().getTitle(), plan.rows().size(), plan.subAgents().size(), shareToken);
        return Optional.of(new Imported(plan.conversation().getId(), sharedId, plan.rows().size(),
                plan.subAgents().size()));
    }

    // -- The rows, as a function of the document -------------------------------------------------

    /** The rows an import writes, in order; {@code finalAnswerIndex} is the row the share ends at. */
    record Plan(Conversation conversation, List<ChatMessage> rows, List<SubAgentRun> subAgents,
                int finalAnswerIndex) {}

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
        // The delegate call that started each one - SubAgentRun.callId.
        Map<String, String> callIdByAgent = new HashMap<>();

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
                    List<SubAgentService.Settled> settled = new ArrayList<>();
                    for (String recorded : strings(m.path("agents"))) {
                        JsonNode agent = agentsById.get(recorded);
                        if (agent == null) continue;
                        long started = agent.path("startedAt").asLong(0);
                        long ended = agent.path("endedAt").asLong(started);
                        settled.add(new SubAgentService.Settled(agentIds.get(recorded),
                                agent.path("title").asText("Sub-agent"),
                                agent.path("status").asText("COMPLETED").toUpperCase(Locale.ROOT),
                                agent.path("report").asText(""),
                                Math.max(0, (ended - started) / 1000)));
                    }
                    if (!settled.isEmpty()) {
                        rows.add(row(conversationId, MessageRole.USER,
                                SubAgentService.reportMessage(settled, null), clock.at(at)));
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
                            asked.add(new ToolCall(id, "function", name, call.path("arguments").asText("{}")));
                            String result = call.path("result").isNull() ? null : call.path("result").asText(null);
                            String recordedAgent = call.path("agentId").asText(null);
                            if (recordedAgent == null) recordedAgent = agentIdIn(result);
                            if (recordedAgent != null && agentIds.containsKey(recordedAgent)) {
                                result = withAgentId(result, agentIds.get(recordedAgent));
                                callIdByAgent.put(recordedAgent, id);
                            }
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

        List<SubAgentRun> runs = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : agentsById.entrySet()) {
            runs.add(subAgentRun(e.getValue(), agentIds.get(e.getKey()), conversationId,
                    callIdByAgent.get(e.getKey()), shift));
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

    private static SubAgentRun subAgentRun(JsonNode agent, String id, String conversationId, String callId,
                                           long shift) {
        SubAgentRun run = new SubAgentRun(id, conversationId, callId, agent.path("title").asText("Sub-agent"),
                agent.path("mode").asText("ASYNC").toUpperCase(Locale.ROOT));
        run.setStatus(agent.path("status").asText("COMPLETED").toUpperCase(Locale.ROOT));
        long started = agent.path("startedAt").asLong(0);
        long ended = agent.path("endedAt").asLong(0);
        if (started > 0) run.setStartedAt(Instant.ofEpochMilli(started + shift));
        if (ended > 0) run.setEndedAt(Instant.ofEpochMilli(ended + shift));
        run.setReport(agent.path("report").isNull() ? null : agent.path("report").asText(null));
        run.setItemsJson(shiftedItems(agent.path("items"), shift));
        // Its report reached the conversation when it was recorded; nothing is owed for it now.
        run.setDelivered(true);
        run.setProvider(agent.path("provider").asText(null));
        run.setModel(agent.path("model").asText(null));
        run.setInputTokens(agent.path("inputTokens").asLong(0));
        run.setOutputTokens(agent.path("outputTokens").asLong(0));
        run.setCacheReadTokens(agent.path("cacheReadTokens").asLong(0));
        run.setCacheWriteTokens(agent.path("cacheWriteTokens").asLong(0));
        run.setCostUsd(agent.path("costUsd").asDouble(0));
        run.setContextTokens(agent.path("contextTokens").asLong(0));
        run.setContextWindow(agent.path("contextWindow").asLong(0));
        return run;
    }

    // The sub-agent's steps, as SubAgentView.Item JSON, with their times moved like everything else.
    private static String shiftedItems(JsonNode items, long shift) {
        if (!items.isArray()) return null;
        ArrayNode out = JSON.createArrayNode();
        for (JsonNode item : items) {
            if (!item.isObject()) continue;
            ObjectNode copy = ((ObjectNode) item).deepCopy();
            for (String field : List.of("startedAt", "endedAt")) {
                if (copy.path(field).canConvertToLong() && copy.path(field).asLong() > 0) {
                    copy.put(field, copy.path(field).asLong() + shift);
                }
            }
            out.add(copy);
        }
        return out.toString();
    }

    // The latest time the document mentions, which is when the recorded run ended.
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
