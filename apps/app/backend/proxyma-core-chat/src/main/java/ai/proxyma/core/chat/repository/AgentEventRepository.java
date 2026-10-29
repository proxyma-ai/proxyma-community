package ai.proxyma.core.chat.repository;

import ai.proxyma.core.chat.model.AgentEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AgentEventRepository extends JpaRepository<AgentEvent, UUID> {

    List<AgentEvent> findByRootConversationIdInAndAtGreaterThanEqualOrderByAtAsc(Collection<String> roots, Instant from,
                                                                               Pageable page);

    @Modifying
    @Transactional("chatTransactionManager")
    int deleteByRootConversationIdIn(Collection<String> roots);

    @Modifying
    @Transactional("chatTransactionManager")
    int deleteByConversationIdIn(Collection<String> conversationIds);
}
