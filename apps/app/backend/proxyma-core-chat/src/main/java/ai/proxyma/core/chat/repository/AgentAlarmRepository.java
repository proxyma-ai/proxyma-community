package ai.proxyma.core.chat.repository;

import ai.proxyma.core.chat.model.AgentAlarm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AgentAlarmRepository extends JpaRepository<AgentAlarm, UUID> {

    List<AgentAlarm> findByFiredAtIsNullAndCanceledAtIsNullAndDueAtLessThanEqualOrderByDueAtAsc(Instant now);

    List<AgentAlarm> findByConversationIdAndFiredAtIsNullAndCanceledAtIsNullOrderByDueAtAsc(String conversationId);

    long countByConversationIdAndFiredAtIsNullAndCanceledAtIsNull(String conversationId);

    @Modifying
    @Transactional("chatTransactionManager")
    int deleteByConversationIdIn(Collection<String> conversationIds);
}
