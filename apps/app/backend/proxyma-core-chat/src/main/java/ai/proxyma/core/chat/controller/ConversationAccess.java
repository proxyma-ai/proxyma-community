package ai.proxyma.core.chat.controller;

import ai.proxyma.core.chat.model.Conversation;
import org.springframework.http.HttpCookie;
import org.springframework.http.ResponseCookie;
import org.springframework.web.server.ServerWebExchange;

import java.time.Duration;
import java.util.UUID;

/**
 * Who may read or write a given conversation, in one place.
 *
 * <p>Extracted from ChatController when a SECOND controller came to need it: the workflow-run read
 * endpoint serves a conversation's own content (step titles and step output) and sits under the
 * same permitAll {@code /api/v1/chat/} prefix as every other read of it, so it needs the identical
 * check. Two copies of an access rule is how the copies come to disagree, and the one that stays
 * permissive is the one nobody notices.
 *
 * <p>ChatController keeps thin private delegates onto this rather than being rewritten at its
 * fifteen call sites - the rule moved, the call sites did not.
 */
public final class ConversationAccess {

    private ConversationAccess() {}

    /**
     * Binds an anonymous conversation to the browser that created it - a guest never logs in, so
     * this cookie is the only identity a guest conversation can be scoped to. Never used for
     * anything else (search scope, settings and personality all still see a plain null userId for
     * anonymous callers).
     */
    public static final String GUEST_COOKIE_NAME = "PROXYMA-GUEST-ID";
    public static final Duration GUEST_COOKIE_MAX_AGE = Duration.ofDays(365);

    /**
     * A conversation with a real owner (userId set) is only ever accessible to that same user. An
     * anonymous conversation (userId null) is scoped to the guest cookie that created it once one
     * was recorded (see SmartChatMemory.ensureConversation); one with neither is nobody's, and
     * nobody may open it. On Desktop and Home Server there is one person and every conversation is
     * theirs.
     */
    public static boolean canAccess(Conversation conv, String userId, String guestSessionId, boolean multiUser) {
        // ONE PERSON, NO WALL. Desktop and Home Server have no accounts, and every conversation on
        // them is that one person's - so the guest-cookie binding below, which exists to keep
        // anonymous VISITORS of a server apart, has nobody to keep apart there. Applied anyway, it
        // tied each conversation to the browser that started it: the history listed it, and
        // opening it from another browser, from a phone on a Home Server, or after cookies were
        // cleared answered 404. Found by the browser suite, which opens conversations by URL.
        if (!multiUser) return true;
        if (conv.getUserId() != null) return userId != null && conv.getUserId().equals(userId);
        // Nobody's: no owner and no visitor. Every anonymous conversation a multi-user server
        // creates records its visitor, so this is a row a future writer forgot to bind - and it is
        // refused rather than opened to anyone (chat-api-data-21). It used to be open, for rows from
        // before the binding existed, which no install still has.
        if (conv.getGuestSessionId() == null) return false;
        return guestSessionId != null && conv.getGuestSessionId().equals(guestSessionId);
    }

    /**
     * Reads the guest-session cookie identifying this browser to anonymous callers.
     *
     * <p>With {@code mint=true} (only the conversation-creating POST /chat), mints and sets one if
     * missing so a guest's very first message already gets a stable id; every other call site
     * passes false - minting there would serve no purpose (a visitor with no cookie reading someone
     * else's conversation is correctly denied either way) and would set a cookie on every anonymous
     * page view for no reason.
     */
    public static String guestSessionId(ServerWebExchange exchange, boolean mint) {
        HttpCookie existing = exchange.getRequest().getCookies().getFirst(GUEST_COOKIE_NAME);
        if (existing != null && !existing.getValue().isBlank()) return existing.getValue();
        if (!mint) return null;
        String id = UUID.randomUUID().toString();
        boolean secure = "https".equalsIgnoreCase(exchange.getRequest().getURI().getScheme());
        exchange.getResponse().addCookie(ResponseCookie.from(GUEST_COOKIE_NAME, id)
                .path("/").maxAge(GUEST_COOKIE_MAX_AGE).httpOnly(true).secure(secure).sameSite("Lax").build());
        return id;
    }
}
