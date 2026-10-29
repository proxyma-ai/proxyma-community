package ai.proxyma.core.chat.repository;

import ai.proxyma.core.chat.model.AgentInboxEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AgentInboxRepository extends JpaRepository<AgentInboxEntry, UUID> {

    List<AgentInboxEntry> findByConversationIdAndDeliveredAtIsNullOrderByCreatedAtAsc(String conversationId);

    @Query("select distinct e.conversationId from AgentInboxEntry e where e.deliveredAt is null")
    List<String> conversationsWithPending();

    @Modifying
    @Transactional("chatTransactionManager")
    @Query("update AgentInboxEntry e set e.deliveredAt = :at where e.id in :ids and e.deliveredAt is null")
    int markDelivered(@Param("ids") Collection<UUID> ids, @Param("at") Instant at);

    @Modifying
    @Transactional("chatTransactionManager")
    int deleteByConversationIdIn(Collection<String> conversationIds);
}
