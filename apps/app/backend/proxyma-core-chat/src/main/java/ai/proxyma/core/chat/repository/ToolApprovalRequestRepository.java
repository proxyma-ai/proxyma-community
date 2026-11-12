package ai.proxyma.core.chat.repository;

import ai.proxyma.core.chat.model.ToolApprovalRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ToolApprovalRequestRepository extends JpaRepository<ToolApprovalRequest, UUID> {

    @Query("select distinct r.conversationId from ToolApprovalRequest r where r.status = 'PENDING'")
    List<String> conversationsWithPending();

    long countByConversationIdAndStatus(String conversationId, String status);

    // The lists a page reads. Their order is the Pageable's own sort (ApprovalInbox.order), which ends on
    // the id: the time alone ties for drafts kept in the same instant, and a tie came back in another
    // order on every read, so the page's rows changed places between polls.
    Page<ToolApprovalRequest> findByUserIdAndStatusIn(String userId, Collection<String> statuses, Pageable page);

    Page<ToolApprovalRequest> findByUserIdIsNullAndStatusIn(Collection<String> statuses, Pageable page);

    Page<ToolApprovalRequest> findByRootConversationIdInAndStatusIn(Collection<String> roots, Collection<String> statuses,
                                                                     Pageable page);

    long countByRootConversationIdInAndStatus(Collection<String> roots, String status);

    // -- The Decided list's filters (ApprovalInbox.decided) -------------------------------------------
    // One query a filter that is not a plain status, each a fixed JPQL with no parameter standing in for
    // a column or an operator: the filter is a whitelisted enum, never text from the request.

    /** The approved requests of these trees a PERSON approved: no decider recorded (Desktop) or a person's id. */
    @Query("select r from ToolApprovalRequest r where r.rootConversationId in :roots and r.status = 'APPROVED' "
            + "and (r.decidedBy is null or r.decidedBy not like 'auto:%')")
    Page<ToolApprovalRequest> approvedByAPerson(@Param("roots") Collection<String> roots, Pageable page);

    /** The approved requests of these trees the SERVER approved (ApprovalInbox.AUTO_PROJECT, AUTO_AGENT). */
    @Query("select r from ToolApprovalRequest r where r.rootConversationId in :roots and r.status = 'APPROVED' "
            + "and r.decidedBy like 'auto:%'")
    Page<ToolApprovalRequest> approvedAutomatically(@Param("roots") Collection<String> roots, Pageable page);

    /** The approved requests of these trees whose call went one of these ways (FAILED; HELD and RETRYING). */
    @Query("select r from ToolApprovalRequest r where r.rootConversationId in :roots and r.status = 'APPROVED' "
            + "and r.runOutcome in :outcomes")
    Page<ToolApprovalRequest> approvedAndRan(@Param("roots") Collection<String> roots,
                                             @Param("outcomes") Collection<String> outcomes, Pageable page);

    /** These trees' requests in these statuses, counted by status and how the call went (ApprovalInbox.decidedCounts). */
    @Query("select r.status, r.runOutcome, count(r) from ToolApprovalRequest r where r.rootConversationId in :roots "
            + "and r.status in :statuses group by r.status, r.runOutcome")
    List<Object[]> countByStatusAndOutcome(@Param("roots") Collection<String> roots,
                                           @Param("statuses") Collection<String> statuses);

    /** How many of these trees' approved requests the server approved itself. */
    @Query("select count(r) from ToolApprovalRequest r where r.rootConversationId in :roots and r.status = 'APPROVED' "
            + "and r.decidedBy like 'auto:%'")
    long countApprovedAutomatically(@Param("roots") Collection<String> roots);

    // -- The same, about these subjects only (a team page's filters by tag, viewer, video or kind) -------
    // The subjects are the server's own ("youtube-comment:<id>"), found from what the filter names - never
    // text from the request itself.

    Page<ToolApprovalRequest> findByRootConversationIdInAndSubjectInAndStatusIn(Collection<String> roots,
                                                                                 Collection<String> subjects,
                                                                                 Collection<String> statuses,
                                                                                 Pageable page);

    @Query("select r from ToolApprovalRequest r where r.rootConversationId in :roots and r.subject in :subjects "
            + "and r.status = 'APPROVED' and (r.decidedBy is null or r.decidedBy not like 'auto:%')")
    Page<ToolApprovalRequest> approvedByAPersonAbout(@Param("roots") Collection<String> roots,
                                                     @Param("subjects") Collection<String> subjects, Pageable page);

    @Query("select r from ToolApprovalRequest r where r.rootConversationId in :roots and r.subject in :subjects "
            + "and r.status = 'APPROVED' and r.decidedBy like 'auto:%'")
    Page<ToolApprovalRequest> approvedAutomaticallyAbout(@Param("roots") Collection<String> roots,
                                                         @Param("subjects") Collection<String> subjects, Pageable page);

    @Query("select r from ToolApprovalRequest r where r.rootConversationId in :roots and r.subject in :subjects "
            + "and r.status = 'APPROVED' and r.runOutcome in :outcomes")
    Page<ToolApprovalRequest> approvedAndRanAbout(@Param("roots") Collection<String> roots,
                                                  @Param("subjects") Collection<String> subjects,
                                                  @Param("outcomes") Collection<String> outcomes, Pageable page);

    @Query("select r.status, r.runOutcome, count(r) from ToolApprovalRequest r where r.rootConversationId in :roots "
            + "and r.subject in :subjects and r.status in :statuses group by r.status, r.runOutcome")
    List<Object[]> countByStatusAndOutcomeAbout(@Param("roots") Collection<String> roots,
                                                @Param("subjects") Collection<String> subjects,
                                                @Param("statuses") Collection<String> statuses);

    @Query("select count(r) from ToolApprovalRequest r where r.rootConversationId in :roots and r.subject in :subjects "
            + "and r.status = 'APPROVED' and r.decidedBy like 'auto:%'")
    long countApprovedAutomaticallyAbout(@Param("roots") Collection<String> roots,
                                         @Param("subjects") Collection<String> subjects);

    /**
     * How many requests of these trees in these statuses each tree has for each subject (a null subject its own group) - a
     * team page's filter counts, which narrow each other by the tree's agent and the subject's comment together.
     */
    @Query("select r.rootConversationId, r.subject, count(r) from ToolApprovalRequest r where r.rootConversationId in :roots "
            + "and r.status in :statuses group by r.rootConversationId, r.subject")
    List<Object[]> countByRootAndSubject(@Param("roots") Collection<String> roots,
                                         @Param("statuses") Collection<String> statuses);

    /**
     * Whether this person has a request for this tool and subject that was approved and is made, being made or
     * held for a retry - not one that FAILED (ApprovalInbox.approveAutomatically: a second automatic reply to one
     * comment is not made).
     */
    @Query("select count(r) from ToolApprovalRequest r where r.toolName = :tool and r.subject = :subject "
            + "and r.userId = :user and r.status = 'APPROVED' and (r.runOutcome is null or r.runOutcome <> 'FAILED')")
    long countApprovedNotFailed(@Param("tool") String tool, @Param("subject") String subject, @Param("user") String user);

    /** The same, for Desktop's one local person, whose requests carry no user. */
    @Query("select count(r) from ToolApprovalRequest r where r.toolName = :tool and r.subject = :subject "
            + "and r.userId is null and r.status = 'APPROVED' and (r.runOutcome is null or r.runOutcome <> 'FAILED')")
    long countApprovedNotFailedForNobody(@Param("tool") String tool, @Param("subject") String subject);

    List<ToolApprovalRequest> findByStatusAndRequestedAtBefore(String status, Instant before);

    /** This person's APPROVED requests of a tool for a subject whose call went one of these ways (HELD, RETRYING). */
    List<ToolApprovalRequest> findByToolNameAndSubjectAndUserIdAndStatusAndRunOutcomeIn(String toolName, String subject,
                                                                                      String userId, String status,
                                                                                      Collection<String> runOutcomes);

    /** The same, for Desktop's one local person, whose requests carry no user. */
    List<ToolApprovalRequest> findByToolNameAndSubjectAndUserIdIsNullAndStatusAndRunOutcomeIn(String toolName, String subject,
                                                                                           String status,
                                                                                           Collection<String> runOutcomes);

    /** Of these subjects, this person's requests in a status whose call went one of these ways (ApprovalInbox.heldSubjects). */
    List<ToolApprovalRequest> findBySubjectInAndUserIdAndStatusAndRunOutcomeIn(Collection<String> subjects, String userId,
                                                                              String status, Collection<String> runOutcomes);

    /** The same, for Desktop's one local person. */
    List<ToolApprovalRequest> findBySubjectInAndUserIdIsNullAndStatusAndRunOutcomeIn(Collection<String> subjects, String status,
                                                                                    Collection<String> runOutcomes);

    /** The subjects of these that wait in this status for this person under one of these root conversations. */
    @Query("select distinct r.subject from ToolApprovalRequest r where r.status = :status and r.userId = :user "
            + "and r.subject in :subjects and r.rootConversationId in :roots")
    List<String> subjectsWaitingUnder(@Param("subjects") Collection<String> subjects, @Param("user") String user,
                                      @Param("status") String status, @Param("roots") Collection<String> roots);

    /** The same, for Desktop's one local person, whose requests carry no user. */
    @Query("select distinct r.subject from ToolApprovalRequest r where r.status = :status and r.userId is null "
            + "and r.subject in :subjects and r.rootConversationId in :roots")
    List<String> subjectsWaitingUnderForNobody(@Param("subjects") Collection<String> subjects, @Param("status") String status,
                                               @Param("roots") Collection<String> roots);

    /** The subjects of these that wait in this status for this person (ApprovalInbox.waitingSubjects). */
    @Query("select distinct r.subject from ToolApprovalRequest r where r.status = :status and r.userId = :user "
            + "and r.subject in :subjects")
    List<String> subjectsWaiting(@Param("subjects") Collection<String> subjects, @Param("user") String user,
                                 @Param("status") String status);

    /** The subjects of these with an approved write of this person whose call went this way (ApprovalInbox.subjectsRan). */
    @Query("select distinct r.subject from ToolApprovalRequest r where r.status = 'APPROVED' and r.runOutcome = :outcome "
            + "and r.userId = :user and r.subject in :subjects")
    List<String> subjectsRan(@Param("subjects") Collection<String> subjects, @Param("user") String user,
                             @Param("outcome") String outcome);

    /** The same, for Desktop's one local person. */
    @Query("select distinct r.subject from ToolApprovalRequest r where r.status = 'APPROVED' and r.runOutcome = :outcome "
            + "and r.userId is null and r.subject in :subjects")
    List<String> subjectsRanForNobody(@Param("subjects") Collection<String> subjects, @Param("outcome") String outcome);

    /** The same, for Desktop's one local person, whose requests carry no user. */
    @Query("select distinct r.subject from ToolApprovalRequest r where r.status = :status and r.userId is null "
            + "and r.subject in :subjects")
    List<String> subjectsWaitingForNobody(@Param("subjects") Collection<String> subjects, @Param("status") String status);

    /** Claims a pending request for one decision: 1 when this call took it, 0 when another did. */
    @Modifying
    @Transactional("chatTransactionManager")
    @Query("update ToolApprovalRequest r set r.status = :status, r.decidedAt = :at, r.decidedBy = :by, r.note = :note "
            + "where r.id = :id and r.status = 'PENDING'")
    int decide(@Param("id") UUID id, @Param("status") String status, @Param("at") Instant at,
               @Param("by") String by, @Param("note") String note);

    /** The requests of this person in this status for the same tool and subject, newest first (ApprovalInbox.park). */
    List<ToolApprovalRequest> findByToolNameAndSubjectAndUserIdAndStatusOrderByRequestedAtDesc(
            String toolName, String subject, String userId, String status);

    List<ToolApprovalRequest> findByToolNameAndSubjectAndUserIdIsNullAndStatusOrderByRequestedAtDesc(
            String toolName, String subject, String status);

    /** One person's requests of one tool in this status, in the Pageable's order (ApprovalInbox.waiting). */
    List<ToolApprovalRequest> findBySubjectAndUserIdAndStatusOrderByRequestedAtDesc(String subject, String userId, String status);

    List<ToolApprovalRequest> findBySubjectAndUserIdIsNullAndStatusOrderByRequestedAtDesc(String subject, String status);

    Page<ToolApprovalRequest> findByRootConversationIdInAndUserIdAndStatus(Collection<String> roots, String userId,
                                                                           String status, Pageable page);

    Page<ToolApprovalRequest> findByRootConversationIdInAndUserIdIsNullAndStatus(Collection<String> roots, String status,
                                                                                 Pageable page);

    Page<ToolApprovalRequest> findByToolNameAndUserIdAndStatus(String toolName, String userId, String status, Pageable page);

    Page<ToolApprovalRequest> findByToolNameAndUserIdIsNullAndStatus(String toolName, String status, Pageable page);

    /**
     * A context looked up after the request was kept (ApprovalInbox.fillContext): written only while the
     * request still has none, and counted as one more attempt whatever came back. 1 when it had none.
     */
    @Modifying
    @Transactional("chatTransactionManager")
    @Query("update ToolApprovalRequest r set r.context = :context, "
            + "r.contextAttempts = coalesce(r.contextAttempts, 0) + 1 where r.id = :id and r.context is null")
    int fillContext(@Param("id") UUID id, @Param("context") String context);

    /**
     * A waiting request's card made whole by the inbox's check (ApprovalInbox.recheck) - its context replaced
     * whatever it held, only while the request still waits. 1 when it did.
     */
    @Modifying
    @Transactional("chatTransactionManager")
    @Query("update ToolApprovalRequest r set r.context = :context where r.id = :id and r.status = 'PENDING'")
    int completeContext(@Param("id") UUID id, @Param("context") String context);

    /**
     * The newest request of this person for the same tool and subject that has a context - the same video,
     * viewer and comment - which a request whose own lookup failed borrows (ApprovalInbox.fillContext).
     */
    Optional<ToolApprovalRequest> findFirstByToolNameAndSubjectAndUserIdAndContextIsNotNullOrderByRequestedAtDesc(
            String toolName, String subject, String userId);

    Optional<ToolApprovalRequest> findFirstByToolNameAndSubjectAndUserIdIsNullAndContextIsNotNullOrderByRequestedAtDesc(
            String toolName, String subject);

    /** The oldest requests in this status still without a context and with attempts left (ApprovalInbox.fillMissingContexts). */
    List<ToolApprovalRequest> findTop20ByStatusAndContextIsNullAndContextAttemptsLessThanOrderByRequestedAtAsc(
            String status, Integer attempts);

    /** A context gained after the call ran - its result's link (ApprovalInbox.run). */
    @Modifying
    @Transactional("chatTransactionManager")
    @Query("update ToolApprovalRequest r set r.context = :context where r.id = :id")
    int setContext(@Param("id") UUID id, @Param("context") String context);

    /** The arguments a person edited before approving, and the agent's own draft beside them. */
    @Modifying
    @Transactional("chatTransactionManager")
    @Query("update ToolApprovalRequest r set r.arguments = :arguments, r.draftArguments = :draft, r.context = :context "
            + "where r.id = :id")
    int edited(@Param("id") UUID id, @Param("arguments") String arguments, @Param("draft") String draft,
               @Param("context") String context);

    @Modifying
    @Transactional("chatTransactionManager")
    int deleteByConversationIdIn(Collection<String> conversationIds);

    // -- Held writes (ApprovalInbox.retryHeld) --------------------------------------------------------

    /** The held writes due a retry, the longest due first (ApprovalInbox.retryHeld). */
    List<ToolApprovalRequest> findTop20ByRunOutcomeAndRetryAtLessThanEqualOrderByRetryAtAsc(String runOutcome, Instant at);

    /** Claims one held write for its retry: 1 when this call took it, 0 when it was no longer held. */
    @Modifying
    @Transactional("chatTransactionManager")
    @Query("update ToolApprovalRequest r set r.runOutcome = 'RETRYING' where r.id = :id and r.runOutcome = 'HELD'")
    int claimRetry(@Param("id") UUID id);

    /** Approved writes with no outcome and no result, decided since a time: those a restart cut short (ApprovalInbox). */
    List<ToolApprovalRequest> findByStatusAndRunOutcomeIsNullAndResultIsNullAndDecidedAtAfter(String status,
                                                                                          java.time.Instant after);

    /** A retry the server stopped in the middle of is held again, so the next pass takes it (ApprovalInbox.loadPending). */
    @Modifying
    @Transactional("chatTransactionManager")
    @Query("update ToolApprovalRequest r set r.runOutcome = 'HELD' where r.runOutcome = 'RETRYING'")
    int releaseRetries();

    // -- The live figures (ApprovalInbox.figures) -------------------------------------------------------

    /**
     * One person's requests of these trees, counted by tool, status and how the call went: those waiting and
     * held whenever asked, the rest when decided or run since {@code since}. Each row: tool, status, runOutcome,
     * count, the soonest retry.
     */
    @Query(value = "select r.toolName, r.status, r.runOutcome, count(r), min(r.retryAt) from ToolApprovalRequest r "
            + "where r.rootConversationId in :roots and r.userId = :user and (r.status = 'PENDING' "
            + "or r.runOutcome in ('HELD', 'RETRYING') or r.decidedAt >= :since or r.ranAt >= :since) "
            + "and not (r.status = 'APPROVED' and r.runOutcome is null and r.result is not null) "
            + "group by r.toolName, r.status, r.runOutcome")
    List<Object[]> figures(@Param("roots") Collection<String> roots, @Param("user") String user,
                           @Param("since") Instant since);

    /** The same, for Desktop's one local person, whose requests carry no user. */
    @Query(value = "select r.toolName, r.status, r.runOutcome, count(r), min(r.retryAt) from ToolApprovalRequest r "
            + "where r.rootConversationId in :roots and r.userId is null and (r.status = 'PENDING' "
            + "or r.runOutcome in ('HELD', 'RETRYING') or r.decidedAt >= :since or r.ranAt >= :since) "
            + "and not (r.status = 'APPROVED' and r.runOutcome is null and r.result is not null) "
            + "group by r.toolName, r.status, r.runOutcome")
    List<Object[]> figuresForNobody(@Param("roots") Collection<String> roots, @Param("since") Instant since);

    /**
     * Why the requests of these trees failed or were withdrawn since {@code since}, newest first: tool, status,
     * note (a withdrawal's reason), result (a failure's error) - a page of them, for the commonest reasons.
     */
    @Query("select r.toolName, r.status, r.note, r.result from ToolApprovalRequest r where r.rootConversationId in :roots "
            + "and r.userId = :user and ((r.status = 'WITHDRAWN' and (r.decidedAt >= :since or r.ranAt >= :since)) "
            + "or (r.runOutcome = 'FAILED' and r.ranAt >= :since)) order by r.decidedAt desc")
    List<Object[]> reasons(@Param("roots") Collection<String> roots, @Param("user") String user,
                           @Param("since") Instant since, Pageable page);

    @Query("select r.toolName, r.status, r.note, r.result from ToolApprovalRequest r where r.rootConversationId in :roots "
            + "and r.userId is null and ((r.status = 'WITHDRAWN' and (r.decidedAt >= :since or r.ranAt >= :since)) "
            + "or (r.runOutcome = 'FAILED' and r.ranAt >= :since)) order by r.decidedAt desc")
    List<Object[]> reasonsForNobody(@Param("roots") Collection<String> roots, @Param("since") Instant since,
                                    Pageable page);

    // -- How often a person stepped in (ApprovalInbox.decidedSince) ---------------------------------------

    /**
     * These trees' requests decided since {@code since} - approved, denied or withdrawn - newest first, with only what
     * the step-in figures need: tool, status, decider, when, the tree, whether a note was written, a withdrawal's
     * reason (null for every other status), and the agent's draft and what was sent - both null unless the person
     * edited it - and what it was about (its subject). Every person's: the figures are a project's, and carry no text.
     */
    @Query("select r.toolName, r.status, r.decidedBy, r.decidedAt, r.rootConversationId, "
            + "case when r.note is null then 0 else 1 end, "
            + "case when r.status = 'WITHDRAWN' then r.note end, "
            + "r.draftArguments, "
            + "case when r.draftArguments is not null then r.arguments end, "
            + "r.subject "
            + "from ToolApprovalRequest r where r.rootConversationId in :roots "
            + "and r.status in ('APPROVED', 'DENIED', 'WITHDRAWN') and r.decidedAt >= :since "
            + "order by r.decidedAt desc, r.id")
    List<Object[]> decidedSince(@Param("roots") Collection<String> roots, @Param("since") Instant since, Pageable page);
}
