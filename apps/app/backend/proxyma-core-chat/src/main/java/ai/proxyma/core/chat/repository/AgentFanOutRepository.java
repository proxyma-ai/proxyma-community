package ai.proxyma.core.chat.repository;

import ai.proxyma.core.chat.model.AgentFanOut;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AgentFanOutRepository extends JpaRepository<AgentFanOut, UUID> {

    List<AgentFanOut> findByParentConversationIdOrderByCreatedAtAsc(String parentConversationId);

    List<AgentFanOut> findByStatus(String status);

    @Modifying
    @Transactional("chatTransactionManager")
    int deleteByRootConversationIdIn(Collection<String> roots);

    @Modifying
    @Transactional("chatTransactionManager")
    int deleteByParentConversationIdIn(Collection<String> parents);
}
