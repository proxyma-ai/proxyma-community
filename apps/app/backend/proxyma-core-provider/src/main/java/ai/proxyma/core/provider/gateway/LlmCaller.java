package ai.proxyma.core.provider.gateway;

import ai.proxyma.core.common.spend.SpendSubject;

import java.util.Objects;

/**
 * WHO is making a model call and FOR WHOM - the two things every ledger row needs, passed to the
 * gateway explicitly on every call.
 *
 * <p>{@code service} is the {@link LlmRole}: which part of the product is calling, shown as
 * "service" in the per-call log and the model it resolves to. {@code subject} is who the call is
 * billed to.
 *
 * <p>AN ARGUMENT, NOT A THREAD-LOCAL, since 2026-09-22 (maintainer's decision). The gateway used
 * to read the subject from {@code SpendContext} at call time, and the failure of that was
 * invisible: a pooled thread that never saw the request filed a signed-in user's calls under
 * SYSTEM, or under the previous request's user when a value leaked. A required parameter makes
 * "who is this for" a question every call site has to answer, and the compiler finds the one that
 * did not. {@code SpendContext} still exists for the shared services that sit several frames below
 * the code that knows the user - OCR inside document conversion, the metered embedding bean
 * inside a vector store - and those read it at their own boundary and pass it on from there.
 *
 * <p>On Desktop and Home Server there is one person, and the ledgers file every subject under
 * {@link SpendSubject#LOCAL} whatever arrives here - see LlmCallLogService.
 *
 * <p>{@code conversationId} is the conversation the call was made FOR, or null for a call made for
 * none (an embedding batch, a rerank, a background sweep). Every agent - the one the user talks to, a
 * deployed one, a saved agent of a team - is a conversation of its own on the one turn loop, so this
 * is what lets the per-call log tell one agent's calls from another's (LlmCall.conversationId). An
 * argument for the same reason as the subject: a turn's model call runs on another thread.
 */
public record LlmCaller(LlmRole service, SpendSubject subject, String conversationId) {

    public LlmCaller {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(subject, "subject");
        if (conversationId != null && conversationId.isBlank()) conversationId = null;
    }

    /** A call made for no conversation. */
    public LlmCaller(LlmRole service, SpendSubject subject) {
        this(service, subject, null);
    }

    /** The same caller, for this conversation (null for none). */
    public LlmCaller inConversation(String id) {
        return new LlmCaller(service, subject, id);
    }

    /** A call on behalf of this subject. */
    public static LlmCaller of(LlmRole service, SpendSubject subject) {
        return new LlmCaller(service, subject);
    }

    /** A person's call - ANONYMOUS when there is no account, see {@link SpendSubject#user}. */
    public static LlmCaller forUser(LlmRole service, String userId) {
        return new LlmCaller(service, SpendSubject.user(userId));
    }

    /** Work on something a user owns - SYSTEM when it has no owner, see {@link SpendSubject#owner}. */
    public static LlmCaller forOwner(LlmRole service, String ownerId) {
        return new LlmCaller(service, SpendSubject.owner(ownerId));
    }

    /** Work with nobody behind it: a scheduled sweep, a startup probe. */
    public static LlmCaller system(LlmRole service) {
        return new LlmCaller(service, SpendSubject.SYSTEM);
    }
}
