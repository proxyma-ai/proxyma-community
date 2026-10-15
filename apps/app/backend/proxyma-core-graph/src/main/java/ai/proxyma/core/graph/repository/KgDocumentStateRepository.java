package ai.proxyma.core.graph.repository;

import ai.proxyma.core.graph.model.KgDocumentState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface KgDocumentStateRepository extends JpaRepository<KgDocumentState, UUID> {

    List<KgDocumentState> findByConnectorId(UUID connectorId);

    /**
     * The documents being read RIGHT NOW, newest progress first.
     *
     * <p>What a source's screen needs to say something is happening: a document of 581 chunks is
     * hours from DONE, so the count of finished documents stands still and the only live signal is
     * how far through the current one the worker is.
     */
    List<KgDocumentState> findByConnectorIdAndStatus(UUID connectorId, KgDocumentState.Status status);

    long countByConnectorId(UUID connectorId);

    long countByConnectorIdAndStatus(UUID connectorId, KgDocumentState.Status status);

    // Failures that have used up their retries - the ones an operator has to look at, as opposed
    // to a failure that will be tried again on its own.
    long countByConnectorIdAndStatusAndAttemptsGreaterThanEqual(UUID connectorId,
                                                                KgDocumentState.Status status,
                                                                int attempts);

    /**
     * Puts back to PENDING every DONE row that is provably not a read: nothing in the graph came
     * from it, AND no chunk answer is on record for it.
     *
     * <p>Both conditions, because each alone is wrong. "No mentions" alone is also a document the
     * model read and found nothing in, and re-queueing those on every start would re-extract them
     * forever. "No answer recorded" alone is every row written before chunksAnswered existed,
     * including all the ones with a perfectly good graph.
     *
     * <p>What it cannot do is tell an outage row from a genuinely empty document written BEFORE the
     * count existed - that difference was never stored. Those are re-read once, and the read
     * records its count, so neither kind is offered again. One extraction per legacy empty document
     * is the price of not leaving an outage's documents empty for good.
     *
     * <p>{@code chunksAnswered = 0} is not reachable today - extraction throws rather than finish a
     * document nothing answered - and is here so that a path which ever slips past that is repaired
     * too, instead of being the next permanent hole.
     */
    @Modifying
    @Query("update KgDocumentState s set s.status = :pending, s.updatedAt = :now "
            + "where s.status = :done "
            + "and (s.chunksAnswered is null or s.chunksAnswered = 0) "
            // A model's mentions, not the metadata pass's: that pass runs first on every pass and
            // writes metadata mentions with no model, which hid an outage row from this repair.
            + "and not exists (select m.id from KgMention m where m.documentId = s.documentId "
            + "and (m.chunkRef is null or m.chunkRef <> 'meta'))")
    int requeueDoneWithoutAnswers(@Param("done") KgDocumentState.Status done,
                                  @Param("pending") KgDocumentState.Status pending,
                                  @Param("now") Instant now);

    @Modifying
    @Query("delete from KgDocumentState s where s.documentId = :documentId")
    int deleteByDocumentId(@Param("documentId") UUID documentId);

    @Modifying
    @Query("delete from KgDocumentState s where s.connectorId = :connectorId")
    int deleteByConnectorId(@Param("connectorId") UUID connectorId);
}
