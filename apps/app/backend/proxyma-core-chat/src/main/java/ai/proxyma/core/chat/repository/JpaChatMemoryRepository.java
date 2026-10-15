package ai.proxyma.core.chat.repository;
import ai.proxyma.core.chat.model.ChatMessage;
import ai.proxyma.core.chat.domain.MessageRole;
import ai.proxyma.core.chat.repository.ChatMessageRepository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Repository
public class JpaChatMemoryRepository implements ChatMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(JpaChatMemoryRepository.class);

    // Gemini 3 models return a thought signature alongside each functionCall part and reject the
    // follow-up request (400, "Failed to generate content" once Spring AI's Gemini integration has
    // wrapped it) if those signatures don't come back with the same parts. Spring AI carries them
    // on AssistantMessage metadata under this key, but every ReAct iteration rebuilds its prompt
    // from these rows, so metadata dropped here is metadata the provider never sees again - which
    // broke the request right after any tool call, and kept breaking every later request on the
    // same conversation, since the unsigned call stays in the persisted history.
    private static final String THOUGHT_SIGNATURES = "thoughtSignatures";

    // Two things kept in metadata_json that NO provider reads back - they are for the transcript a
    // person sees, and deserialize() below deliberately leaves both off the message it rebuilds.
    //
    // TOOL_TIMINGS, on a TOOL row: {callId: [startedAt, endedAt]} in epoch milliseconds, so a
    // reloaded conversation can say how long a tool list or a sub-agent took, as the live one does.
    // STEP_LIMIT, on an ASSISTANT row: the step limit the turn stopped at (see
    // ChatService.announceStepLimit), so the notice under that row survives a reload too.
    public static final String TOOL_TIMINGS = "toolTimings";
    public static final String STEP_LIMIT = "stepLimit";

    private static final ObjectMapper NOTES = new ObjectMapper();

    private final ChatMessageRepository recordRepository;
    private final ObjectMapper objectMapper;

    public JpaChatMemoryRepository(ChatMessageRepository recordRepository, ObjectMapper objectMapper) {
        this.recordRepository = recordRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        // What the MODEL reads: rows folded into a summary are left out, the summary stands in.
        List<ChatMessage> records = recordRepository.findByConversationIdAndCondensedFalseOrderByCreatedAtAscIdAsc(conversationId);
        List<Message> messages = new ArrayList<>();
        for (ChatMessage rec : records) messages.add(deserialize(rec));
        return messages;
    }

    // Spring AI's SPI, and nothing here calls it: SmartChatMemory appends, condenses by marking
    // rows and rewinds by time. A whole-conversation rewrite would drop every condensed and summary
    // flag and every row id, so it is refused rather than left to do that quietly for a future caller.
    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        throw new UnsupportedOperationException(
                "A conversation is never rewritten whole - append, condense or rewind it instead.");
    }

    /**
     * Folds messages [from, to] of what {@link #findByConversationId} returns into
     * {@code replacement}: those rows are marked condensed and kept, and the replacement is written
     * as summary rows timed at the start of the range, so the model reads it exactly where the
     * folded messages were. Nothing is deleted and no surviving row changes its id.
     *
     * <p>The replacement is timed from the first folded row, a microsecond apart. The row after the
     * range is always a later turn's USER message (see SmartChatMemory.findCondensableRange), so
     * it was written at a later instant and still sorts after every replacement row.
     */
    @Transactional("chatTransactionManager")
    public void condenseRange(String conversationId, int from, int to, List<Message> replacement) {
        List<ChatMessage> live = recordRepository.findByConversationIdAndCondensedFalseOrderByCreatedAtAscIdAsc(conversationId);
        if (from < 0 || to >= live.size() || from > to) {
            throw new IllegalArgumentException("condense range [" + from + ", " + to + "] outside " + live.size() + " messages");
        }
        Instant base = live.get(from).getCreatedAt();
        for (int i = from; i <= to; i++) {
            live.get(i).setCondensed(true);
            recordRepository.save(live.get(i));
        }
        for (int i = 0; i < replacement.size(); i++) {
            ChatMessage rec = serialize(conversationId, replacement.get(i));
            rec.setSummary(true);
            rec.setCreatedAt(base.plus(i, ChronoUnit.MICROS));
            recordRepository.save(rec);
        }
    }

    /**
     * Rewrites the system message a conversation starts with, in place: the same row, the same id,
     * the same position. False when the first thing the model reads is not a system message.
     */
    @Transactional("chatTransactionManager")
    public boolean replaceSystemMessage(String conversationId, String text) {
        ChatMessage first = recordRepository
                .findFirstByConversationIdAndCondensedFalseOrderByCreatedAtAscIdAsc(conversationId).orElse(null);
        if (first == null || first.getRole() != MessageRole.SYSTEM) return false;
        first.setContent(text);
        recordRepository.save(first);
        return true;
    }

    @Transactional("chatTransactionManager")
    public void appendMessages(String conversationId, List<Message> messages) {
        Instant base = Instant.now();
        long startNano = recordRepository.countByConversationId(conversationId);
        persistMessages(conversationId, messages, base, startNano);
    }

    private void persistMessages(String conversationId, List<Message> messages, Instant base, long startOffset) {
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage rec = serialize(conversationId, messages.get(i));
            // MICROSECONDS, not nanoseconds: the column keeps six digits, so nanosecond offsets
            // all collapsed onto one timestamp. The id tiebreak covers what remains.
            rec.setCreatedAt(base.plus(startOffset + i, ChronoUnit.MICROS));
            recordRepository.save(rec);
        }
    }

    @Override
    @Transactional("chatTransactionManager")
    public void deleteByConversationId(String conversationId) {
        recordRepository.deleteByConversationId(conversationId);
    }

    @Override
    public List<String> findConversationIds() {
        return recordRepository.findDistinctConversationIds();
    }

    private ChatMessage serialize(String conversationId, Message message) {
        ChatMessage rec = new ChatMessage();
        rec.setConversationId(conversationId);
        rec.setContent(message.getText());
        switch (message) {
            case SystemMessage sm -> rec.setRole(MessageRole.SYSTEM);
            case UserMessage um -> rec.setRole(MessageRole.USER);
            case AssistantMessage am -> {
                rec.setRole(MessageRole.ASSISTANT);
                if (am.getToolCalls() != null && !am.getToolCalls().isEmpty())
                    rec.setToolCallsJson(toJson(am.getToolCalls()));
                rec.setMetadataJson(writeReplayMetadata(am.getMetadata()));
            }
            case ToolResponseMessage trm -> {
                rec.setRole(MessageRole.TOOL);
                rec.setContent(null);
                rec.setToolResponsesJson(toJson(trm.getResponses()));
                if (trm.getMetadata().get(TOOL_TIMINGS) instanceof Map<?, ?> timings && !timings.isEmpty()) {
                    rec.setMetadataJson(toJson(Map.of(TOOL_TIMINGS, timings)));
                }
            }
            default -> rec.setRole(MessageRole.USER);
        }
        return rec;
    }

    private Message deserialize(ChatMessage rec) {
        return switch (rec.getRole()) {
            case SYSTEM -> new SystemMessage(nullToEmpty(rec.getContent()));
            case USER -> new UserMessage(nullToEmpty(rec.getContent()));
            case ASSISTANT -> {
                List<ToolCall> toolCalls = rec.getToolCallsJson() != null
                        ? fromJson(rec.getToolCallsJson(), new TypeReference<>() {}) : List.of();
                yield AssistantMessage.builder()
                        .content(nullToEmpty(rec.getContent()))
                        .toolCalls(toolCalls)
                        .properties(readReplayMetadata(rec.getMetadataJson()))
                        .build();
            }
            case TOOL -> {
                List<ToolResponse> responses = rec.getToolResponsesJson() != null
                        ? fromJson(rec.getToolResponsesJson(), new TypeReference<>() {}) : List.of();
                yield ToolResponseMessage.builder().responses(responses).build();
            }
            default -> new UserMessage(nullToEmpty(rec.getContent()));
        };
    }

    // Deliberately a keep-list of exactly what the provider needs on replay, not the whole metadata
    // map: the rest of an AssistantMessage's metadata is per-response bookkeeping (candidateIndex,
    // finishReason, ...) that no later call reads back, and some of it isn't JSON-round-trippable,
    // so persisting it wholesale would trade a real bug for a serialization one.
    private String writeReplayMetadata(Map<String, Object> metadata) {
        if (metadata == null || !(metadata.get(THOUGHT_SIGNATURES) instanceof List<?> signatures)) return null;
        List<String> encoded = new ArrayList<>();
        // Base64 because these are raw byte[] blobs; order is preserved because the provider pairs
        // them positionally with this message's tool calls.
        for (Object signature : signatures) {
            if (signature instanceof byte[] bytes) encoded.add(Base64.getEncoder().encodeToString(bytes));
        }
        return encoded.isEmpty() ? null : toJson(Map.of(THOUGHT_SIGNATURES, encoded));
    }

    private Map<String, Object> readReplayMetadata(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Object> raw = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            if (!(raw.get(THOUGHT_SIGNATURES) instanceof List<?> signatures)) return Map.of();
            // Decoded back to byte[] rather than left as the base64 String Jackson returns - the
            // provider integration casts this value straight to List<byte[]>.
            List<byte[]> decoded = new ArrayList<>();
            for (Object signature : signatures) {
                if (signature instanceof String s) decoded.add(Base64.getDecoder().decode(s));
            }
            return decoded.isEmpty() ? Map.of() : Map.of(THOUGHT_SIGNATURES, decoded);
        } catch (Exception e) {
            // A message that loses its signatures still renders and still replays for every other
            // provider, so this must never fail the whole conversation load.
            log.warn("[ChatMemory] metadata deserialize failed: {}", e.getMessage());
            return Map.of();
        }
    }

    /** The start and end of each call a TOOL row answers, by call id; empty for a row with none. */
    public static Map<String, long[]> readToolTimings(String metadataJson) {
        if (metadataJson == null || !metadataJson.contains(TOOL_TIMINGS)) return Map.of();
        try {
            Map<String, long[]> out = new java.util.HashMap<>();
            for (var e : NOTES.readTree(metadataJson).path(TOOL_TIMINGS).properties()) {
                if (e.getValue().size() == 2) {
                    out.put(e.getKey(), new long[] {e.getValue().get(0).asLong(), e.getValue().get(1).asLong()});
                }
            }
            return out;
        } catch (Exception e) {
            // A time that will not parse costs the reader a duration, never the conversation.
            return Map.of();
        }
    }

    /** The step limit an ASSISTANT row's turn stopped at, or null for every other row. */
    public static Integer readStepLimit(String metadataJson) {
        if (metadataJson == null || !metadataJson.contains(STEP_LIMIT)) return null;
        try {
            var limit = NOTES.readTree(metadataJson).path(STEP_LIMIT);
            return limit.isInt() ? limit.asInt() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code metadataJson} with the step limit added - whatever else it holds is kept. */
    public static String withStepLimit(String metadataJson, int limit) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode node =
                    metadataJson == null || metadataJson.isBlank()
                            ? NOTES.createObjectNode()
                            : (com.fasterxml.jackson.databind.node.ObjectNode) NOTES.readTree(metadataJson);
            node.put(STEP_LIMIT, limit);
            return NOTES.writeValueAsString(node);
        } catch (Exception e) {
            // Unreadable metadata is left exactly as it is: it may hold the thought signatures a
            // provider needs back, and losing a notice is the smaller loss.
            return metadataJson;
        }
    }

    private String toJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { log.warn("[ChatMemory] serialize failed: {}", e.getMessage()); return "[]"; }
    }

    @SuppressWarnings("unchecked")
    private <T> T fromJson(String json, TypeReference<T> type) {
        try { return objectMapper.readValue(json, type); }
        catch (Exception e) { log.warn("[ChatMemory] deserialize failed: {}", e.getMessage()); return (T) List.of(); }
    }

    private static String nullToEmpty(String s) { return s != null ? s : ""; }
}
