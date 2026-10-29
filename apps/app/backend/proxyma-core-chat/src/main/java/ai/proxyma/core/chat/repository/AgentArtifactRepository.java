package ai.proxyma.core.chat.repository;

import ai.proxyma.core.chat.model.AgentArtifact;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AgentArtifactRepository extends JpaRepository<AgentArtifact, UUID> {

    List<AgentArtifact> findByRootConversationIdInOrderByCreatedAtDesc(Collection<String> roots, Pageable page);

    @Modifying
    @Transactional("chatTransactionManager")
    int deleteByRootConversationIdIn(Collection<String> roots);
}
