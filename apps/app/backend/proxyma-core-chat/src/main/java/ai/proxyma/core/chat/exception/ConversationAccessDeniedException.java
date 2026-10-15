package ai.proxyma.core.chat.exception;

// Thrown when a conversation's stored userId doesn't match the caller resolved for the current
// request. In practice this almost always means the caller's session lapsed mid-conversation
// (access cookie expired, request fell through to anonymous) rather than a genuine cross-user
// access attempt - callers use the distinct type to surface a "please sign in again" signal
// instead of a generic chat error.
public class ConversationAccessDeniedException extends RuntimeException {
    public ConversationAccessDeniedException(String message) {
        super(message);
    }
}
