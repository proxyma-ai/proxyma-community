package ai.proxyma.core.provider.gateway;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * What a prompt was, as far as a provider's cache can tell: its tools, its system prompt and each
 * message, hashed in the order the provider reads them (Anthropic caches tools, then system, then
 * messages). A cached prefix is read only when the next prompt STARTS WITH it, so two prompts are
 * compared by {@link #isPrefixOf}, and {@link #firstDifference} says which part broke the prefix -
 * the cause of a miss nothing but a code change prevents.
 *
 * <p>Hashes only: no prompt text is kept in memory past the call.
 */
public record PromptFingerprint(String tools, String system, List<String> messages) {

    public enum Part { NONE, TOOLS, SYSTEM, MESSAGES }

    // Separators no prompt text is expected to hold, so "ab"+"c" never reads as "a"+"bc".
    private static final char END = 0;
    private static final char ITEM = 1;
    private static final char FIELD = 2;

    public static PromptFingerprint of(List<Message> prompt, List<ToolCallback> tools) {
        StringBuilder system = new StringBuilder();
        List<String> rest = new ArrayList<>();
        if (prompt != null) {
            for (Message m : prompt) {
                if (m == null) continue;
                if (m.getMessageType() == MessageType.SYSTEM) {
                    system.append(m.getText()).append(END);
                } else {
                    rest.add(hash(describe(m)));
                }
            }
        }
        return new PromptFingerprint(hash(describeTools(tools)), hash(system.toString()), List.copyOf(rest));
    }

    public int messageCount() {
        return messages.size();
    }

    /** Whether {@code next} starts with this prompt: same tools, same system prompt, these messages first. */
    public boolean isPrefixOf(PromptFingerprint next) {
        return next != null && firstDifference(next) == Part.NONE;
    }

    /** The first part of {@code next} that is not this prompt continued; NONE when it is. */
    public Part firstDifference(PromptFingerprint next) {
        if (next == null) return Part.MESSAGES;
        if (!tools.equals(next.tools)) return Part.TOOLS;
        if (!system.equals(next.system)) return Part.SYSTEM;
        if (next.messages.size() < messages.size()) return Part.MESSAGES;
        for (int i = 0; i < messages.size(); i++) {
            if (!messages.get(i).equals(next.messages.get(i))) return Part.MESSAGES;
        }
        return Part.NONE;
    }

    // A message as the provider receives it: its role, its text, and the tool calls or results it
    // carries. Ids are part of it - a provider reads them back.
    private static String describe(Message m) {
        StringBuilder s = new StringBuilder(m.getMessageType().name()).append(END);
        if (m.getText() != null) s.append(m.getText());
        if (m instanceof AssistantMessage a && a.getToolCalls() != null) {
            for (AssistantMessage.ToolCall c : a.getToolCalls()) {
                s.append(ITEM).append(c.id()).append(FIELD).append(c.name()).append(FIELD).append(c.arguments());
            }
        }
        if (m instanceof ToolResponseMessage t && t.getResponses() != null) {
            for (ToolResponseMessage.ToolResponse r : t.getResponses()) {
                s.append(ITEM).append(r.id()).append(FIELD).append(r.name()).append(FIELD).append(r.responseData());
            }
        }
        return s.toString();
    }

    // The tools as offered: their order is the order sent, so it counts.
    private static String describeTools(List<ToolCallback> tools) {
        if (tools == null || tools.isEmpty()) return "";
        StringBuilder s = new StringBuilder();
        for (ToolCallback t : tools) {
            if (t == null) continue;
            ToolDefinition d = t.getToolDefinition();
            s.append(d.name()).append(FIELD).append(d.description()).append(FIELD).append(d.inputSchema()).append(ITEM);
        }
        return s.toString();
    }

    static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (int i = 0; i < 16; i++) hex.append(String.format("%02x", digest[i]));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

}
