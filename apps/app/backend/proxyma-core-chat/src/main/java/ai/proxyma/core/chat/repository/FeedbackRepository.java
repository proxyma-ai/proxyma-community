package ai.proxyma.core.chat.repository;

import ai.proxyma.core.chat.domain.FeedbackMood;
import ai.proxyma.core.chat.domain.FeedbackSource;
import ai.proxyma.core.chat.model.Feedback;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Repository
public interface FeedbackRepository extends JpaRepository<Feedback, Long> {

    // BOTH DELETES ARE ONE STATEMENT IN A TRANSACTION OF THEIR OWN. They were derived deletes with
    // neither: Spring Data gives a query method declared on the interface no transaction, a derived
    // delete removes each matching row through the entity manager, and the shared entity manager
    // refuses that outside a transaction. So deleting a conversation anybody had rated, and taking
    // a rating back, failed whenever there was a row to delete - and an empty match, which never
    // reaches the entity manager, is all any test exercised. Unqualified, because on a repository
    // method that names the manager this repository is wired to, in both editions
    // (recurring-failure-classes section 13).

    // Cascade cleanup on conversation/account deletion - see ChatService.deleteConversation()
    // and ChatService.deleteAllByUserId().
    @Modifying
    @Transactional
    @Query("DELETE FROM Feedback f WHERE f.conversationId = :conversationId")
    int deleteByConversationId(@Param("conversationId") String conversationId);

    // Retracts the thumbs-up/down auto-rating on one message when the user takes it back or flips
    // it (see ChatService.retractRating). `comment IS NULL` is what keeps this to auto-ratings: a
    // feedback someone actually wrote always carries a comment, and must never be deleted by
    // somebody idly toggling a thumb.
    @Modifying
    @Transactional
    @Query("DELETE FROM Feedback f WHERE f.conversationId = :conversationId AND f.messageId = :messageId "
            + "AND f.source = :source AND f.comment IS NULL")
    int deleteRating(@Param("conversationId") String conversationId, @Param("messageId") Long messageId,
                     @Param("source") FeedbackSource source);

    Page<Feedback> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Feedback> findByMoodOrderByCreatedAtDesc(FeedbackMood mood, Pageable pageable);

    // Retention (FeedbackRetentionSweep). One statement, in a transaction of its own on the manager
    // this repository is bound to; the attachments go with their rows by the FK's ON DELETE CASCADE.
    @Modifying(clearAutomatically = true)
    @Transactional("chatTransactionManager")
    @Query("delete from Feedback f where f.createdAt < :cutoff")
    int deleteSubmittedBefore(@Param("cutoff") Instant cutoff);
}
