package ai.proxyma.core.chat.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * WHERE a conversation is held, when that is not the chat page: a page of its own that runs a chat
 * beside its content (the key-account panel's {@code key-account}, docs/architecture.html#spec-11-8).
 * Null is the ordinary chat.
 *
 * <p>Taken from the FIRST message of a conversation and never changed afterwards
 * (SmartChatMemory.ensureConversation), so a conversation cannot be moved into or out of the chat
 * page's history by what a later request says. The history list, the assistant's conversation
 * search and the "working" dots list only conversations with no surface unless asked for one.
 *
 * <p>A short lower-case token rather than an enum: a page that adds a panel names its own surface
 * and the chat module needs no change for it - and a STRING column with no CHECK, so a new surface
 * is never refused by a table created before it existed.
 */
public final class ConversationSurface {

    /** The longest surface accepted - and the column's length. */
    public static final int MAX_LENGTH = 64;

    private static final Pattern TOKEN = Pattern.compile("[a-z0-9][a-z0-9-]{0," + (MAX_LENGTH - 1) + "}");

    private ConversationSurface() {}

    /**
     * The surface a request names, or null for the ordinary chat (absent or blank).
     *
     * @throws IllegalArgumentException when it names something that is not a surface token
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.strip().toLowerCase(Locale.ROOT);
        if (!TOKEN.matcher(value).matches()) {
            throw new IllegalArgumentException("A conversation surface is lower-case letters, digits and dashes.");
        }
        return value;
    }
}
