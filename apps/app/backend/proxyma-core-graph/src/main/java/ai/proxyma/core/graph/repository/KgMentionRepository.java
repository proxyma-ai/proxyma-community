package ai.proxyma.core.graph.repository;

import ai.proxyma.core.graph.model.KgMention;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface KgMentionRepository extends JpaRepository<KgMention, UUID> {

    // The lifecycle hook. A document being re-indexed or deleted drops its mentions first; whatever
    // that orphans is then purged via KgEntityRepository.findOrphanedAmong(). Returns the count so a
    // caller can log what a re-extraction actually replaced rather than assuming it did something.
    @Modifying
    @Query("delete from KgMention m where m.documentId = :documentId")
    int deleteByDocumentId(@Param("documentId") UUID documentId);

    // One chunk reference of one document - the metadata rows (KnowledgeGraphService.METADATA_REF),
    // replaced on their own without touching what a model read.
    @Query("select distinct m.entityId from KgMention m where m.documentId = :documentId and m.chunkRef = :chunkRef")
    List<UUID> entityIdsForDocumentChunk(@Param("documentId") UUID documentId, @Param("chunkRef") String chunkRef);

    @Modifying
    @Query("delete from KgMention m where m.documentId = :documentId and m.chunkRef = :chunkRef")
    int deleteByDocumentIdAndChunkRef(@Param("documentId") UUID documentId, @Param("chunkRef") String chunkRef);

    @Modifying
    @Query("delete from KgMention m where m.connectorId = :connectorId")
    int deleteByConnectorId(@Param("connectorId") UUID connectorId);

    long countByConnectorId(UUID connectorId);

    // How many DISTINCT things this connector mentions, which is the number the per-connector
    // corpus view reports. countByConnectorId counts mentions, and one entity mentioned on every
    // page of a long document would make a corpus of one thing look like a corpus of hundreds.
    @Query("select count(distinct m.entityId) from KgMention m where m.connectorId = :connectorId")
    long countDistinctEntitiesByConnectorId(@Param("connectorId") UUID connectorId);

    // The visibility filter, asked of a whole neighbourhood at once. An entity is visible to a
    // user if AT LEAST ONE of its mentions belongs to a connector they can see - so this is a
    // set intersection, not a per-entity join, and it is why connectorId is denormalised onto
    // the mention in the first place (see KgMention).
    @Query("select distinct m.entityId from KgMention m "
            + "where m.entityId in :entityIds and m.connectorId in :connectorIds")
    List<UUID> visibleEntityIds(@Param("entityIds") List<UUID> entityIds,
                                @Param("connectorIds") List<UUID> connectorIds);

    // Provenance for a citation: which documents mention these entities, within what the user
    // can see. Returns pairs rather than whole rows - the tool needs an entity id and a document
    // id, and a mention carries a surface form and a chunk ref it has no use for. UNBOUNDED, so
    // asked only for entities documentCountsFor has shown to be mentioned by few documents.
    @Query("select distinct m.entityId, m.documentId from KgMention m "
            + "where m.entityId in :entityIds and m.connectorId in :connectorIds")
    List<Object[]> documentIdsFor(@Param("entityIds") List<UUID> entityIds,
                                  @Param("connectorIds") List<UUID> connectorIds);

    /** How many distinct documents of these sources mention each of these entities. */
    @Query("select m.entityId, count(distinct m.documentId) from KgMention m "
            + "where m.entityId in :entityIds and m.connectorId in :connectorIds group by m.entityId")
    List<Object[]> documentCountsFor(@Param("entityIds") List<UUID> entityIds,
                                     @Param("connectorIds") List<UUID> connectorIds);

    /**
     * The documents of these sources that mention ONE entity, those mentioning it most first - a
     * page of them. For a hub (a person assigned two thousand issues, a component every issue is
     * in), where {@link #documentIdsFor} would return every one.
     */
    @Query("select m.documentId from KgMention m where m.entityId = :entityId and m.connectorId in :connectorIds "
            + "group by m.documentId order by count(m) desc, m.documentId")
    List<UUID> topDocumentsFor(@Param("entityId") UUID entityId,
                               @Param("connectorIds") List<UUID> connectorIds,
                               org.springframework.data.domain.Pageable page);

    // Whether any of these sources has put anything into the graph - whether the traversal tool is
    // worth offering to someone who can read exactly these sources.
    boolean existsByConnectorIdIn(java.util.Collection<UUID> connectorIds);

    /**
     * What these entities are CALLED, and of what type, in the given sources: each surface form and
     * type with how often it was written. The name a reader is shown comes from here - never the
     * entity's own canonicalName, which is whatever the first document anywhere called it, and on
     * Enterprise Server that can be a document the reader cannot open.
     */
    @Query("select m.entityId, m.surfaceForm, m.entityType, count(m) from KgMention m "
            + "where m.entityId in :entityIds and m.connectorId in :connectorIds "
            + "group by m.entityId, m.surfaceForm, m.entityType")
    List<Object[]> namesIn(@Param("entityIds") java.util.Collection<UUID> entityIds,
                           @Param("connectorIds") java.util.Collection<UUID> connectorIds);

    /**
     * Which entities the passages of these documents mention, in these sources: entity, document
     * and chunk. What links a question to the graph through the passages the search found relevant
     * to it (KnowledgeGraphService.neighbourhood).
     */
    @Query("select m.entityId, m.documentId, m.chunkRef from KgMention m "
            + "where m.documentId in :documentIds and m.connectorId in :connectorIds")
    List<Object[]> mentionsInDocuments(@Param("documentIds") java.util.Collection<UUID> documentIds,
                                       @Param("connectorIds") java.util.Collection<UUID> connectorIds);

    /** Where these entities are mentioned, in these sources: entity, document and chunk. */
    @Query("select m.entityId, m.documentId, m.chunkRef from KgMention m "
            + "where m.entityId in :entityIds and m.connectorId in :connectorIds")
    List<Object[]> passagesOf(@Param("entityIds") java.util.Collection<UUID> entityIds,
                              @Param("connectorIds") java.util.Collection<UUID> connectorIds);

    /**
     * The NEWEST description passages in these sources gave each of these entities - one per
     * entity (two only when two were written in the same instant).
     *
     * <p>Per entity, not a page across all of them: a page of the newest descriptions overall was
     * the whole page for one entity described in every document, and every other entity in the
     * answer had none.
     */
    @Query("select m.entityId, m.description from KgMention m "
            + "where m.entityId in :entityIds and m.connectorId in :connectorIds and m.description is not null "
            + "and m.createdAt = (select max(n.createdAt) from KgMention n where n.entityId = m.entityId "
            + "and n.connectorId in :connectorIds and n.description is not null)")
    List<Object[]> latestDescriptionsIn(@Param("entityIds") java.util.Collection<UUID> entityIds,
                                        @Param("connectorIds") java.util.Collection<UUID> connectorIds);

    // ---------------------------------------------------------------- the graph VIEW
    // Three queries behind the picture a person looks at, as opposed to the neighbourhood a model
    // walks. The difference that shapes them: a traversal starts from named seeds and expands, so
    // it is bounded by hops; a view starts from a SCOPE - one document, or one whole source - and
    // is bounded only by how much was extracted. So each of these is capped by the caller and
    // ordered so that what survives the cap is the part worth looking at.

    /** Every entity this one document mentions. The node set of its own graph. */
    @Query("select distinct m.entityId from KgMention m where m.documentId = :documentId")
    List<UUID> entityIdsForDocument(@Param("documentId") UUID documentId);

    /**
     * Every entity anywhere in this source.
     *
     * <p>THIS IS THE QUERY THAT MAKES A SOURCE'S FILES ONE GRAPH. Entities resolve globally by
     * normalised name (see EntityResolver), so two files in the same folder that both name the
     * same thing already point at one entity row - and this returns that row once. The joining is
     * not something extraction has to be asked to do across documents; it falls out of resolution
     * having been global all along.
     */
    @Query("select distinct m.entityId from KgMention m where m.connectorId = :connectorId")
    List<UUID> entityIdsForConnector(@Param("connectorId") UUID connectorId);

    /**
     * Every entity this source mentions, and how many times - the node set of the source's view
     * and the size of each node. GROUPED, with no list of entity ids bound: the view asked about
     * every entity of the source as one IN list, which a large Jira project, where every issue key
     * is an entity, took past the PostgreSQL driver's limit of bind parameters.
     *
     * <p>Sized by mentions, not by degree: an entity named on every page of one long document and
     * an entity named once in each of forty is the same degree and very different news.
     */
    @Query("select m.entityId, count(m) from KgMention m where m.connectorId = :connectorId group by m.entityId")
    List<Object[]> mentionCountsByConnector(@Param("connectorId") UUID connectorId);

    /** The same within one document - a document's own view sizes its nodes by what IT says. */
    @Query("select m.entityId, count(m) from KgMention m where m.documentId = :documentId group by m.entityId")
    List<Object[]> mentionCountsByDocument(@Param("documentId") UUID documentId);
}
