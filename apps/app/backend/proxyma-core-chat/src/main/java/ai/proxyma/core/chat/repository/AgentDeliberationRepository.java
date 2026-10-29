package ai.proxyma.core.chat.repository;

import ai.proxyma.core.chat.model.AgentDeliberation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AgentDeliberationRepository extends JpaRepository<AgentDeliberation, UUID> {

    List<AgentDeliberation> findByParentConversationIdOrderByCreatedAtAsc(String parentConversationId);

    Page<AgentDeliberation> findByRootConversationIdInOrderByCreatedAtDesc(Collection<String> roots, Pageable page);

    @Modifying
    @Transactional("chatTransactionManager")
    int deleteByParentConversationIdIn(Collection<String> parents);
}
