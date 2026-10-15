package ai.proxyma.core.chat.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.DynamicUpdate;
import java.time.Instant;

// INDEXED BY OWNER, since 2026-10-03: the history list is one user's conversations, newest
// first, and was a scan of everybody's.
//
// @DynamicUpdate: a save writes the columns it changed and no others. Every counter and flag has an
// update of its own (ConversationRepository), and this keeps a whole-row save that is added later
// from writing a stale copy of the rest back over them.
@Entity
@DynamicUpdate
@Table(name = "conversations", indexes = {
        @Index(name = "idx_conversation_user", columnList = "user_id,updatedAt"),
})
public class Conversation {

    @Id
    private String id;

    @Column(name = "user_id")
    private String userId;

    // Set only for anonymous conversations (userId null) created after this field was
    // introduced - the guest cookie value that created them, so a later request can only reopen
    // them from the same browser/session. Pre-existing anonymous rows have this null and stay
    // openly accessible to anyone, matching their original behavior - see ChatController's
    // canAccessConversation().
    @Column(name = "guest_session_id")
    private String guestSessionId;

    private String title;
    private Instant createdAt;
    private Instant updatedAt;
    private int messageCount;

    // Exempts a conversation from the retention sweep (see ConversationRetentionSweep) and sorts
    // it to the top of the history list regardless of recency.
    @Column(nullable = false)
    private boolean pinned = false;

    // Cumulative real usage reported by the LLM provider across every turn of this conversation
    // (see TokenUsageExtractor) - grows monotonically, never reset by condensation. Distinct from
    // contextTokens below, which is a live snapshot, not a running total. Nullable/defaulted to 0
    // for rows created before these columns existed.
    @Column(name = "input_tokens", nullable = false)
    @ColumnDefault("0")
    private long inputTokens = 0;

    @Column(name = "output_tokens", nullable = false)
    @ColumnDefault("0")
    private long outputTokens = 0;

    @Column(name = "cache_read_tokens", nullable = false)
    @ColumnDefault("0")
    private long cacheReadTokens = 0;

    @Column(name = "cache_write_tokens", nullable = false)
    @ColumnDefault("0")
    private long cacheWriteTokens = 0;

    // The real promptTokens the provider reported for this conversation's most recent LLM call
    // (see SmartChatMemory.recordContextTokens()) - NOT an estimate, and NOT the same call as a
    // condensation summarizer's own usage, which never touches this field. Overwritten (not
    // accumulated) after every main-flow call, so it drops back down once condensing shrinks the
    // live history instead of growing forever like the counters above. This is what the chat UI's
    // context-quota meter fills against ChatSettings.memoryMaxTokens, and what SmartChatMemory.get()
    // itself compares against that same limit to decide whether to condense.
    @Column(name = "context_tokens", nullable = false)
    @ColumnDefault("0")
    private int contextTokens = 0;

    // Cumulative estimated USD cost across every LLM call this conversation has made (main-flow
    // iterations and condensation summarizer calls alike - see SmartChatMemory.addCumulativeUsage),
    // computed at write time from the input/output/cache-read/cache-write tokens above multiplied
    // against whichever ModelPricing row matched the provider+model active for that specific call
    // (see ModelPricingService.estimateCostUsd). An unconfigured (provider, model) pair simply
    // contributes 0 - this is an estimate, not a billing ledger.
    @Column(name = "estimated_cost_usd", nullable = false)
    @ColumnDefault("0")
    private double estimatedCostUsd = 0;

    // The skill ids whose full instructions have been loaded into this conversation's history by a
    // composer pick (see ChatService.applyPickedSkills). Purely a ledger for deciding whether the
    // next pick of the same skill needs another copy of the body or just a one-line pointer back to
    // it - the system prompt no longer depends on it, and advertising is independent of it. Reset to
    // null when the history is cleared, since the bodies it refers to go with it. Comma-separated;
    // ids are slugs, so they never contain a comma. Null before any pick.
    @Column(name = "enabled_skill_ids")
    private String enabledSkillIds;

    // The skill NAMES the agent has actually read (readSkill) in this conversation - which is what
    // unlocks that skill's tools for the rest of it (see ToolSkillGating). Persisted rather than
    // re-derived from message history on every iteration, because condensation folds away the very
    // assistant messages that history scan reads, which silently un-advertised a connector's tools
    // mid-conversation. NEWLINE-separated, not comma: a skill name is arbitrary user text and may
    // contain a comma, but never a newline (ChatSkillService.sanitizeLine strips those).
    //
    // Null means "never recorded" and falls back to the history scan, so conversations that predate
    // this column keep their unlocked tools; empty-string means "recorded, nothing unlocked" and
    // does not fall back - that distinction is what lets unloading a skill actually revoke it.
    @Column(name = "unlocked_skill_names", length = 2000)
    private String unlockedSkillNames;

    // The Jira/Confluence/Bitbucket server this conversation's tools use, when the user picked one
    // other than their default with useServer: newline-separated TYPE=name. Null means the defaults.
    // Written by its own UPDATE (ConversationRepository.updateActiveServers), never by saving the
    // entity: tool calls run in parallel with the turn's own saves, and a whole-row save from either
    // side would overwrite the other's columns. Hence insertable and updatable false: the column is
    // left out of every INSERT and entity UPDATE Hibernate generates, so a Conversation loaded before
    // useServer ran cannot write the old value back when it is saved. The JPQL UPDATE is unaffected.
    @Column(name = "active_servers", length = 1000, insertable = false, updatable = false)
    private String activeServers;

    public String getActiveServers() { return activeServers; }

    // Where this conversation is held when it is not the chat page - see ConversationSurface. Null is
    // the ordinary chat. Written once, by ensureConversation from the first message, and never again;
    // nullable, so ddl-auto adds it to a populated table and every existing row reads as ordinary.
    // 64 is ConversationSurface.MAX_LENGTH, written out so the generated data model can read it.
    @Column(name = "surface", length = 64)
    private String surface;

    public String getSurface() { return surface; }
    public void setSurface(String surface) { this.surface = surface; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getGuestSessionId() { return guestSessionId; }
    public void setGuestSessionId(String guestSessionId) { this.guestSessionId = guestSessionId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public int getMessageCount() { return messageCount; }
    public void setMessageCount(int messageCount) { this.messageCount = messageCount; }
    public boolean isPinned() { return pinned; }
    public void setPinned(boolean pinned) { this.pinned = pinned; }
    public long getInputTokens() { return inputTokens; }
    public void setInputTokens(long inputTokens) { this.inputTokens = inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(long outputTokens) { this.outputTokens = outputTokens; }
    public long getCacheReadTokens() { return cacheReadTokens; }
    public void setCacheReadTokens(long cacheReadTokens) { this.cacheReadTokens = cacheReadTokens; }
    public long getCacheWriteTokens() { return cacheWriteTokens; }
    public void setCacheWriteTokens(long cacheWriteTokens) { this.cacheWriteTokens = cacheWriteTokens; }
    public int getContextTokens() { return contextTokens; }
    public void setContextTokens(int contextTokens) { this.contextTokens = contextTokens; }
    public double getEstimatedCostUsd() { return estimatedCostUsd; }
    public void setEstimatedCostUsd(double estimatedCostUsd) { this.estimatedCostUsd = estimatedCostUsd; }
    public String getEnabledSkillIds() { return enabledSkillIds; }
    public void setEnabledSkillIds(String enabledSkillIds) { this.enabledSkillIds = enabledSkillIds; }
    public String getUnlockedSkillNames() { return unlockedSkillNames; }
    public void setUnlockedSkillNames(String unlockedSkillNames) { this.unlockedSkillNames = unlockedSkillNames; }
}
