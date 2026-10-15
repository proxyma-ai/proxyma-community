package ai.proxyma.core.chat.repository;

import ai.proxyma.core.chat.model.FeedbackAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FeedbackAttachmentRepository extends JpaRepository<FeedbackAttachment, Long> {

    /** One attachment as a list shows it - everything but its bytes. */
    record Meta(Long id, Long feedbackId, String fileName, String contentType, long sizeBytes) {}

    // Metadata only, for AdminFeedbackController's list and its resolve/unresolve answers. Loading
    // the entities read every attachment's bytes as well - up to 20 MB a feedback, a hundred
    // feedbacks a page - only to show names and sizes (chat-api-data-07). The bytes are read by the
    // download, one attachment at a time.
    @Query("SELECT new ai.proxyma.core.chat.repository.FeedbackAttachmentRepository$Meta("
            + "a.id, a.feedback.id, a.fileName, a.contentType, a.sizeBytes) "
            + "FROM FeedbackAttachment a WHERE a.feedback.id IN :feedbackIds ORDER BY a.id")
    List<Meta> findMetaByFeedbackIdIn(@Param("feedbackIds") List<Long> feedbackIds);

    // Whole attachments, bytes included - for shipping ONE feedback's files (FeedbackController).
    List<FeedbackAttachment> findByFeedbackIdIn(List<Long> feedbackIds);
}
