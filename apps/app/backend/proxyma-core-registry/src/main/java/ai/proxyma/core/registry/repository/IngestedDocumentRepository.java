package ai.proxyma.core.registry.repository;

import ai.proxyma.core.registry.model.IngestedDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IngestedDocumentRepository extends JpaRepository<IngestedDocument, UUID> {

    Optional<IngestedDocument> findByConnectorIdAndExternalId(UUID connectorId, String externalId);

    /** A source's documents, newest first - the ingestion API's GET and nothing else. */
    Page<IngestedDocument> findByConnectorIdOrderByReceivedAtDescIdAsc(UUID connectorId, Pageable pageable);

    // What a source's session lists, a page at a time, by KEYSET on the id: a row pushed or deleted
    // while a pass reads the table moves no other row past the cursor, where an offset would skip
    // one - and a full pass that skipped a row would remove its document as gone.
    List<IngestedDocument> findTop200ByConnectorIdOrderByIdAsc(UUID connectorId);

    List<IngestedDocument> findTop200ByConnectorIdAndIdGreaterThanOrderByIdAsc(UUID connectorId, UUID after);

    List<IngestedDocument> findTop200ByConnectorIdAndReceivedAtAfterOrderByIdAsc(UUID connectorId, Instant since);

    List<IngestedDocument> findTop200ByConnectorIdAndReceivedAtAfterAndIdGreaterThanOrderByIdAsc(
            UUID connectorId, Instant since, UUID after);

    long countByConnectorId(UUID connectorId);

    List<IngestedDocument> findByConnectorId(UUID connectorId);

    // @Transactional for the same reason as RegisteredConnectorRepository's statements: a @Modifying
    // query needs one, and on Desktop it is this repository's own transaction manager.
    @Modifying
    @Transactional
    @Query("delete from IngestedDocument d where d.connectorId = :connectorId")
    int deleteByConnectorId(@Param("connectorId") UUID connectorId);
}
