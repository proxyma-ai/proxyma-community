package ai.proxyma.core.chat.repository;

import ai.proxyma.core.chat.model.ConversationShare;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationShareRepository extends JpaRepository<ConversationShare, String> {

    // Re-sharing the same message must hand back the SAME link rather than minting a second token
    // for an identical view - otherwise every click accumulates another live capability URL for the
    // same content, and revoking becomes meaningless.
    Optional<ConversationShare> findByConversationIdAndUptoMessageId(String conversationId, Long uptoMessageId);

    List<ConversationShare> findAllByConversationId(String conversationId);

    // Explicitly transactional: ChatService.deleteConversation() is not itself @Transactional, and a
    // Spring Data DERIVED delete (unlike the inherited CRUD methods) is not covered by
    // SimpleJpaRepository's own write transactions. FeedbackRepository's deletes lacked exactly this
    // and failed whenever there was a row to delete (ConversationDeletionTest).
    @Transactional("chatTransactionManager")
    void deleteByConversationId(String conversationId);
}
