package ai.proxyma.core.chat.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

// A read-only, link-addressable view of one conversation up to a specific message. Deliberately a
// REFERENCE, not a snapshot copy: nothing here duplicates message content, so a shared link always
// renders the conversation's real current rows through the same ChatService.getMessagesWithToolCalls
// path the owner sees, just truncated at upToMessageId. That is also what makes "the link expires
// when the conversation is deleted" fall out for free rather than needing its own bookkeeping -
// there is nothing left to resolve (ChatService.deleteConversation additionally removes these rows
// so they don't linger as orphans).
//
// One link per (conversation, message), held by the database: createShare's look-then-save let two
// Share clicks at once mint two tokens for one view. And indexed by conversation, which every
// conversation delete deletes by.
@Entity
@Table(name = "conversation_shares",
        uniqueConstraints = @UniqueConstraint(name = "uq_share_conversation_message",
                columnNames = {"conversation_id", "upto_message_id"}))
public class ConversationShare {

    // The share token itself, and the only thing the URL carries - an unguessable UUID, generated
    // in ChatService.createShare(). It is a capability, but not a bearer credential on its own:
    // GET /api/v1/chat/shared/** is authenticated in server mode (see proxyma-core-auth's
    // SecurityConfig), so a leaked link is still useless to anyone outside the deployment.
    @Id
    private String token;

    @Column(name = "conversation_id", nullable = false)
    private String conversationId;

    // Inclusive upper bound - the viewer sees every message from the first one through this one,
    // and nothing the owner said or the agent answered afterwards, even as the conversation keeps
    // growing. This is the message whose Share button was clicked.
    @Column(name = "upto_message_id", nullable = false)
    private Long uptoMessageId;

    // Who created the share. userId is null in desktop mode (no auth at all) and for anonymous
    // server-mode conversations; displayName is best-effort (JwtAuthFilter's request attribute) and
    // is captured HERE, at share time, rather than resolved on read - proxyma-core-chat has no
    // dependency on proxyma-core-auth and so cannot look a user up later.
    @Column(name = "created_by_user_id")
    private String createdByUserId;

    @Column(name = "created_by_name")
    private String createdByName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public Long getUptoMessageId() { return uptoMessageId; }
    public void setUptoMessageId(Long uptoMessageId) { this.uptoMessageId = uptoMessageId; }
    public String getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(String createdByUserId) { this.createdByUserId = createdByUserId; }
    public String getCreatedByName() { return createdByName; }
    public void setCreatedByName(String createdByName) { this.createdByName = createdByName; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
