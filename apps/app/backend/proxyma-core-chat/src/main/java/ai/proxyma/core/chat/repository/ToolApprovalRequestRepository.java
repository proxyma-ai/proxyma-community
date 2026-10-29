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
import java.util.UUID;

public interface ToolApprovalRequestRepository extends JpaRepository<ToolApprovalRequest, UUID> {

    @Query("select distinct r.conversationId from ToolApprovalRequest r where r.status = 'PENDING'")
    List<String> conversationsWithPending();

    long countByConversationIdAndStatus(String conversationId, String status);

    Page<ToolApprovalRequest> findByUserIdAndStatusInOrderByRequestedAtDesc(String userId, Collection<String> statuses,
                                                                           Pageable page);

    Page<ToolApprovalRequest> findByUserIdIsNullAndStatusInOrderByRequestedAtDesc(Collection<String> statuses, Pageable page);

    Page<ToolApprovalRequest> findByRootConversationIdInAndStatusInOrderByRequestedAtDesc(Collection<String> roots,
                                                                                         Collection<String> statuses,
                                                                                         Pageable page);

    long countByRootConversationIdInAndStatus(Collection<String> roots, String status);

    List<ToolApprovalRequest> findByStatusAndRequestedAtBefore(String status, Instant before);

    /** Claims a pending request for one decision: 1 when this call took it, 0 when another did. */
    @Modifying
    @Transactional("chatTransactionManager")
    @Query("update ToolApprovalRequest r set r.status = :status, r.decidedAt = :at, r.decidedBy = :by, r.note = :note "
            + "where r.id = :id and r.status = 'PENDING'")
    int decide(@Param("id") UUID id, @Param("status") String status, @Param("at") Instant at,
               @Param("by") String by, @Param("note") String note);

    @Modifying
    @Transactional("chatTransactionManager")
    int deleteByConversationIdIn(Collection<String> conversationIds);
}
