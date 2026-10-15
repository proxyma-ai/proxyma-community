package ai.proxyma.core.chat.model;

import ai.proxyma.core.chat.domain.FeedbackMood;
import ai.proxyma.core.chat.domain.FeedbackSource;
import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import java.time.Instant;

// General-purpose feedback entity - replaces the old per-message ChatMessageFeedback (one row
// per message, upsert semantics). This is append-only: both the floating feedback button (no
// chat context) and a chat message dislike (conversationId/messageId set, optional transcript
// snapshot) write a new row here, so there is exactly one admin view of all feedback. A third
// flow, the QUOTA_MILESTONE spend-bracket survey, also writes here (see `source`/`ratingsJson`
// below) rather than getting its own table - same reasoning, one admin view of all feedback.
@Entity
// Indexed by conversation: every conversation delete and the retention sweep delete by it.
@Table(name = "feedback", indexes = @Index(name = "idx_feedback_conversation", columnList = "conversationId"))
public class Feedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FeedbackMood mood;

    // Which flow produced this row - GENERAL (floating feedback button / chat dislike, the
    // original two flows) or QUOTA_MILESTONE (the $5-spend-bracket survey). Left nullable (not
    // nullable=false) so ddl-auto=update can add this column to a non-empty "feedback" table
    // without a backfill migration - existing rows read back as null, treated as GENERAL by every
    // reader (see FeedbackController/AdminFeedbackController). New rows always have it set
    // explicitly by the submitting controller.
    @Enumerated(EnumType.STRING)
    @Column
    private FeedbackSource source = FeedbackSource.GENERAL;

    @Column(columnDefinition = "TEXT")
    private String comment;

    // JSON-serialized Map<String, FeedbackMood> keyed by question id, for the 5-question
    // QUOTA_MILESTONE survey (overall/search_quality/speed/ease_of_use/recommend - see
    // QuotaMilestoneFeedbackModal). Null for GENERAL rows, which only ever use the single `mood`
    // field above. Follows the same "entity holds a plain TEXT column, service layer owns the
    // Jackson (de)serialization" convention as ChatMessage.toolCallsJson/toolResponsesJson - no
    // JPA @Convert/AttributeConverter is used anywhere else in this codebase.
    @Column(columnDefinition = "TEXT")
    private String ratingsJson;

    // Always true now. Kept as a column rather than dropped so existing rows - which have both
    // values - still load and still read correctly in the admin view.
    @Column(nullable = false)
    private boolean anonymous;

    // Optional contact address the user typed themselves, and the only identifying value a
    // submission carries since feedback became unconditionally anonymous.
    @Column
    private String contactEmail;

    // Whether proxyma-telemetry accepted this submission. False means it exists ONLY in this
    // local mirror - an air-gapped install, a deployment with no telemetry URL, or a network
    // failure - which is worth being able to see when someone asks why a report never arrived.
    //
    // @ColumnDefault IS LOAD-BEARING, and its absence broke this table in production. There is no
    // Flyway or Liquibase anywhere in this project; schema changes are applied by Hibernate's
    // ddl-auto=update. For a NOT NULL column with no default that emits
    //
    //     alter table if exists feedback add column delivered_to_proxyma boolean not null
    //
    // which PostgreSQL rejects outright on a table that already has rows ("contains null values").
    // ddl-auto=update logs the failure and carries on, so the application starts perfectly happily
    // with the column MISSING, and every insert afterwards fails on a column that does not exist.
    // Adding the default makes it `... boolean default false not null`, which PostgreSQL accepts
    // and backfills.
    //
    // `source` above dodged this by being nullable, and says so in its own comment. Any future
    // NOT NULL column added to a table that already exists in the wild needs one of the two.
    @Column(nullable = false)
    @ColumnDefault("false")
    private boolean deliveredToProxyma;

    // Never set when anonymous is true.
    private String userId;

    // Submitter's display name at submission time, captured from the JWT so the admin feedback
    // list can show a human-readable name instead of the raw userId UUID. Never set when
    // anonymous is true, same rule as userId.
    private String submitterName;

    private String conversationId;

    private Long messageId;

    @Column(columnDefinition = "TEXT")
    private String conversationTranscriptSnapshot;

    private Instant createdAt;

    private boolean resolved;

    private Instant resolvedAt;

    @Column(columnDefinition = "TEXT")
    private String resolutionNote;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public FeedbackMood getMood() { return mood; }
    public void setMood(FeedbackMood mood) { this.mood = mood; }
    public FeedbackSource getSource() { return source; }
    public void setSource(FeedbackSource source) { this.source = source; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public String getRatingsJson() { return ratingsJson; }
    public void setRatingsJson(String ratingsJson) { this.ratingsJson = ratingsJson; }
    public boolean isAnonymous() { return anonymous; }

    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }

    public boolean isDeliveredToProxyma() { return deliveredToProxyma; }
    public void setDeliveredToProxyma(boolean deliveredToProxyma) { this.deliveredToProxyma = deliveredToProxyma; }
    public void setAnonymous(boolean anonymous) { this.anonymous = anonymous; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getSubmitterName() { return submitterName; }
    public void setSubmitterName(String submitterName) { this.submitterName = submitterName; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public Long getMessageId() { return messageId; }
    public void setMessageId(Long messageId) { this.messageId = messageId; }
    public String getConversationTranscriptSnapshot() { return conversationTranscriptSnapshot; }
    public void setConversationTranscriptSnapshot(String conversationTranscriptSnapshot) { this.conversationTranscriptSnapshot = conversationTranscriptSnapshot; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public boolean isResolved() { return resolved; }
    public void setResolved(boolean resolved) { this.resolved = resolved; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    public String getResolutionNote() { return resolutionNote; }
    public void setResolutionNote(String resolutionNote) { this.resolutionNote = resolutionNote; }
}
