package ai.proxyma.core.chat.model;

import ai.proxyma.core.chat.domain.MessageRole;
import jakarta.persistence.*;
import java.time.Instant;

// INDEXED BY CONVERSATION, since 2026-10-03. Every read of this table is "the messages of one
// conversation, in order" - a turn's history, opening a conversation, its count - and there was no
// index at all, so each was a scan of every message every user had ever sent.
@Entity
@Table(name = "chat_messages", indexes = {
        @Index(name = "idx_chat_message_conversation", columnList = "conversationId,createdAt"),
})
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String conversationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MessageRole role;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "TEXT")
    private String toolCallsJson;

    @Column(columnDefinition = "TEXT")
    private String toolResponsesJson;

    // Provider-replay metadata that has to survive the round-trip through this table, because the
    // next LLM call in the same turn rebuilds its whole prompt from these rows. Currently only
    // Gemini thought signatures - see JpaChatMemoryRepository for what is and isn't kept here.
    @Column(columnDefinition = "TEXT")
    private String metadataJson;

    private Instant createdAt;

    // CONDENSATION MARKS ROWS, IT DOES NOT REWRITE THEM (since 2026-09-24). It used to delete the
    // whole conversation and insert a summarized copy, which had two consequences nobody wanted:
    // everything before the last exchange vanished from the transcript after a reload, and every
    // surviving message got a new id - so a rewind to a message still showing its old id deleted
    // the entire conversation, and share links and ratings keyed on ids pointed at nothing.
    //
    // `condensed`: folded into a summary. The model no longer reads it; the reader still does.
    // `summary`: written BY a condensation - the summary, its acknowledgement and any skill or
    // workflow plan carried past it. The model reads it; the transcript hides it by its marker.
    // Both columns carry a database default, per .claude/rules/backend-maven.md - a NOT NULL
    // column without one cannot be added to a populated table.
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean condensed;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean summary;

    public boolean isCondensed() { return condensed; }
    public void setCondensed(boolean condensed) { this.condensed = condensed; }
    public boolean isSummary() { return summary; }
    public void setSummary(boolean summary) { this.summary = summary; }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public MessageRole getRole() { return role; }
    public void setRole(MessageRole role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getToolCallsJson() { return toolCallsJson; }
    public void setToolCallsJson(String toolCallsJson) { this.toolCallsJson = toolCallsJson; }
    public String getToolResponsesJson() { return toolResponsesJson; }
    public void setToolResponsesJson(String toolResponsesJson) { this.toolResponsesJson = toolResponsesJson; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
