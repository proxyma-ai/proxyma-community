package ai.proxyma.core.graph.repository;

import ai.proxyma.core.graph.model.KgRelation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface KgRelationRepository extends JpaRepository<KgRelation, UUID> {

    // Lifecycle, mirroring KgMentionRepository. Edges belong to the document they were extracted
    // from and go with it.
    @Modifying
    @Query("delete from KgRelation r where r.documentId = :documentId")
    int deleteByDocumentId(@Param("documentId") UUID documentId);

    @Modifying
    @Query("delete from KgRelation r where r.documentId = :documentId and r.chunkRef = :chunkRef")
    int deleteByDocumentIdAndChunkRef(@Param("documentId") UUID documentId, @Param("chunkRef") String chunkRef);

    @Modifying
    @Query("delete from KgRelation r where r.connectorId = :connectorId")
    int deleteByConnectorId(@Param("connectorId") UUID connectorId);

    long countByConnectorId(UUID connectorId);

    /**
     * One hop out from several entities at once, in both directions, FROM THESE SOURCES ONLY, and
     * only what holds NOW and is not an event (KnowledgeGraphService.current) - newest first.
     *
     * <p>The source filter is the SEC-51 fix. An edge's endpoints can each be visible through some
     * other document while the edge itself - its predicate, its quoted sentence, its document - was
     * extracted from a source the asker cannot read; this had no source filter, and the traversal
     * kept such an edge whenever both ends were visible somewhere. It is also what makes the page
     * size mean something: the cap now applies AFTER visibility, not before it.
     *
     * <p>The time filter is here for the same reason. It was applied after the page, oldest rows
     * first, so for an entity metadata made a hub - a person with hundreds of past assignments, a
     * status thousands of issues passed through - the page could be all ended spells and events,
     * and the traversal found no current edge at all.
     */
    @Query("select r from KgRelation r where (r.subjectId in :entityIds or r.objectId in :entityIds) "
            + "and r.connectorId in :connectorIds and r.occurredAt is null "
            + "and (r.validTo is null or r.validTo > :now) order by r.createdAt desc, r.id")
    List<KgRelation> findCurrentTouchingAnyIn(@Param("entityIds") java.util.Collection<UUID> entityIds,
                                              @Param("connectorIds") java.util.Collection<UUID> connectorIds,
                                              @Param("now") java.time.Instant now,
                                              org.springframework.data.domain.Pageable page);

    /** The dated rows touching these entities in these sources - what a timeline is made of - newest first. */
    @Query("select r from KgRelation r where (r.subjectId in :entityIds or r.objectId in :entityIds) "
            + "and r.connectorId in :connectorIds "
            + "and (r.occurredAt is not null or r.validFrom is not null or r.validTo is not null) "
            + "order by r.createdAt desc, r.id")
    List<KgRelation> findDatedTouchingAnyIn(@Param("entityIds") java.util.Collection<UUID> entityIds,
                                            @Param("connectorIds") java.util.Collection<UUID> connectorIds,
                                            org.springframework.data.domain.Pageable page);

    /** The newest CURRENT edges of these sources - what the corpus themes are grouped from. */
    @Query("select r from KgRelation r where r.connectorId in :connectorIds and r.occurredAt is null "
            + "and (r.validTo is null or r.validTo > :now) order by r.createdAt desc, r.id")
    List<KgRelation> findCurrentInConnectors(@Param("connectorIds") java.util.Collection<UUID> connectorIds,
                                             @Param("now") java.time.Instant now,
                                             org.springframework.data.domain.Pageable page);

    /** Every edge row of one document - what a write dedupes against. */
    @Query("select r from KgRelation r where r.documentId = :documentId order by r.createdAt, r.id")
    List<KgRelation> findByDocumentId(@Param("documentId") UUID documentId,
                                      org.springframework.data.domain.Pageable page);

    // ---------------------------------------------------------------- the graph VIEW
    // ONE ROW PER TRIPLE, grouped here: subject, object, predicate, how many distinct documents state
    // it, a description and a document. The view read raw rows "dearest-corroborated first" by a
    // weight that has been 1 on every row since corroboration moved to counting documents, so the
    // cap kept an arbitrary slice, and a triple twenty documents state was twenty parallel edges and
    // a degree of twenty. Most-stated first, then by id, so the same graph caps the same way. Current
    // edges only, as the traversal walks them.

    /** One document's edges, grouped - see above. */
    @Query("select r.subjectId, r.objectId, r.predicate, count(distinct r.documentId), max(r.description), "
            + "max(cast(r.documentId as String)) from KgRelation r where r.documentId = :documentId "
            + "and r.occurredAt is null and (r.validTo is null or r.validTo > :now) "
            + "group by r.subjectId, r.objectId, r.predicate "
            + "order by count(distinct r.documentId) desc, r.subjectId, r.objectId, r.predicate")
    List<Object[]> viewEdgesOfDocument(@Param("documentId") UUID documentId, @Param("now") java.time.Instant now,
                                       org.springframework.data.domain.Pageable page);

    /**
     * A whole source's edges, grouped - see above.
     *
     * <p>Scoped by CONNECTOR rather than by the entities in view, deliberately: an edge belongs to
     * the document it was extracted from, and this view answers "what did this source assert",
     * which is the same set {@code deleteByConnectorId} removes. Edges asserted by some other
     * source about the same entities are that source's to show.
     */
    @Query("select r.subjectId, r.objectId, r.predicate, count(distinct r.documentId), max(r.description), "
            + "max(cast(r.documentId as String)) from KgRelation r where r.connectorId = :connectorId "
            + "and r.occurredAt is null and (r.validTo is null or r.validTo > :now) "
            + "group by r.subjectId, r.objectId, r.predicate "
            + "order by count(distinct r.documentId) desc, r.subjectId, r.objectId, r.predicate")
    List<Object[]> viewEdgesOfConnector(@Param("connectorId") UUID connectorId, @Param("now") java.time.Instant now,
                                        org.springframework.data.domain.Pageable page);

    long countByDocumentId(UUID documentId);
}
