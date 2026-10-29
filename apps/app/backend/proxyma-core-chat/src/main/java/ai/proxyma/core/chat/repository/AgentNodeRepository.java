package ai.proxyma.core.chat.repository;

import ai.proxyma.core.chat.model.AgentNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentNodeRepository extends JpaRepository<AgentNode, UUID> {

    Optional<AgentNode> findByConversationId(String conversationId);

    List<AgentNode> findByParentConversationIdOrderByCreatedAtAsc(String parentConversationId);

    List<AgentNode> findByRootConversationIdOrderByCreatedAtAsc(String rootConversationId);

    List<AgentNode> findByRootConversationIdIn(Collection<String> rootConversationIds);

    long countByRootConversationIdAndStatusIn(String rootConversationId, Collection<String> statuses);

    long countByParentConversationIdAndStatusIn(String parentConversationId, Collection<String> statuses);

    List<AgentNode> findByStatusIn(Collection<String> statuses);

    List<AgentNode> findByFanOutIdOrderByItemIndexAsc(UUID fanOutId);

    @Modifying
    @Transactional("chatTransactionManager")
    int deleteByConversationIdIn(Collection<String> conversationIds);
}
