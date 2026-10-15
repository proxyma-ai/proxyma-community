package ai.proxyma.core.graph.repository;

import ai.proxyma.core.graph.model.KgEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KgEntityRepository extends JpaRepository<KgEntity, UUID> {

    // Resolution step 1, against the entity's own name. The unique constraint on
    // (normalizedName, type) is what makes this return at most one row.
    Optional<KgEntity> findByNormalizedNameAndType(String normalizedName, String type);

    // The same lookup for an entity whose type could not be classified - GraphNames.UNTYPED, or NULL
    // on a row written before that sentinel existed. A LIST, not an Optional: two NULL-typed rows
    // could exist side by side (NULLs are distinct to a unique index), and an Optional over two rows
    // threw on every chunk and every query naming that entity from then on.
    @Query("select e from KgEntity e where e.normalizedName = :name and (e.type is null or e.type = '') "
            + "order by e.firstSeenAt asc")
    List<KgEntity> findUnclassifiedByNormalizedName(@Param("name") String name);

    // Every type a name exists under - "payments" the team and "payments" the service. Resolution
    // asks it for an untyped mention (one typed row is the thing meant), and a query takes them all.
    @Query("select e from KgEntity e where e.normalizedName = :name order by e.firstSeenAt asc")
    List<KgEntity> findByNormalizedName(@Param("name") String name);

    // THE SEED TIERS BELOW TAKE THE READER'S SOURCES, and keep only entities one of them mentions,
    // BEFORE their page is applied. kg_entities is global across users, and the tiers read their five
    // rows first and asked about visibility afterwards - so five private names that matched a term
    // (or five Vietnamese words folding to one ASCII form) hid every match the reader could see, and
    // the answer was "nothing in the graph matches". The same cap now applies after visibility, as
    // the edge query's does (SEC-51).
    String VISIBLE = " and exists (select 1 from KgMention m where m.entityId = e.id and m.connectorId in :connectorIds)";

    // A query typed without diacritics, matched against the folded form (GraphNames.fold).
    @Query("select e from KgEntity e where e.foldedName = :folded" + VISIBLE + " order by e.firstSeenAt asc, e.id")
    List<KgEntity> findByFoldedName(@Param("folded") String folded,
                                    @Param("connectorIds") Collection<UUID> connectorIds, Pageable page);

    // Entities whose name vector is missing or was made with another embedding model.
    @Query("select e from KgEntity e where e.vectorKey is null or e.vectorKey <> :key order by e.firstSeenAt asc")
    List<KgEntity> findNeedingVectors(@Param("key") String key, Pageable page);

    // Embedding models some entity's vector was made with, other than the current one.
    @Query("select distinct e.vectorKey from KgEntity e where e.vectorKey is not null and e.vectorKey <> :key")
    List<String> findOtherVectorKeys(@Param("key") String key);

    @Modifying
    @Query("update KgEntity e set e.vectorKey = :key where e.id in :ids")
    int markVectorized(@Param("ids") Collection<UUID> ids, @Param("key") String key);

    // Entities written before their derived keys existed - backfilled a batch at a time.
    @Query("select e from KgEntity e where e.squashedName is null")
    List<KgEntity> findWithoutDerivedKeys(Pageable page);

    // Spelling variants of a name - see GraphNames.squash.
    @Query("select e from KgEntity e where e.squashedName = :squashed" + VISIBLE + " order by e.firstSeenAt asc, e.id")
    List<KgEntity> findBySquashedName(@Param("squashed") String squashed,
                                      @Param("connectorIds") Collection<UUID> connectorIds, Pageable page);

    // Names whose initials a query typed - see GraphNames.initials.
    @Query("select e from KgEntity e where e.initials = :initials" + VISIBLE + " order by e.firstSeenAt asc, e.id")
    List<KgEntity> findByInitials(@Param("initials") String initials,
                                  @Param("connectorIds") Collection<UUID> connectorIds, Pageable page);

    // Candidates for a typo-tolerant match: names starting as the term does (escaped with '!').
    // Closest in length first, then by name: it had no order at all, so the page of candidates was
    // an arbitrary few hundred of what might be thousands.
    @Query("select e from KgEntity e where e.squashedName like concat(:prefix, '%') escape '!'" + VISIBLE
            + " order by abs(length(e.squashedName) - :length) asc, e.squashedName asc, e.id")
    List<KgEntity> findBySquashedNamePrefix(@Param("prefix") String prefix, @Param("length") int length,
                                            @Param("connectorIds") Collection<UUID> connectorIds, Pageable page);

    /**
     * Seed lookup for retrieval: entities whose normalised or folded name CONTAINS the term.
     *
     * <p>Deliberately separate from the exact lookups above, which are resolution and must stay
     * exact - a contains-match there would merge "Auth" into "Auth Service" at write time. Here the
     * cost of a loose match is a neighbourhood with an irrelevant entity in it.
     *
     * <p>The term arrives ESCAPED (KnowledgeGraphService.likePattern), with '!' as the escape
     * character: a name holding '%' or '_' matched everything, or anything, in its place.
     * Unanchored LIKE, so no index helps it; bounded by the caller's Pageable.
     */
    @Query("select e from KgEntity e where (e.normalizedName like concat('%', :term, '%') escape '!' "
            + "or e.foldedName like concat('%', :folded, '%') escape '!')" + VISIBLE
            + " order by length(e.normalizedName) asc, e.normalizedName asc, e.id")
    List<KgEntity> searchByNameContaining(@Param("term") String term, @Param("folded") String folded,
                                          @Param("connectorIds") Collection<UUID> connectorIds, Pageable page);

    /**
     * Of these entities, the ones no mention points at any more and nobody has touched since
     * {@code before} - what a document delete leaves behind.
     *
     * <p>ONLY THE ENTITIES THE DELETE TOUCHED, never the whole table: this ran as a NOT EXISTS over
     * every entity, at the start of every document's extraction, four documents at a time. And the
     * {@code lastSeenAt} guard is what keeps it from taking an entity another extraction resolved a
     * moment ago and is about to mention: resolving stamps lastSeenAt, so such a row is younger
     * than {@code before} and is left alone.
     */
    @Query("select e.id from KgEntity e where e.id in :ids and e.lastSeenAt < :before and not exists "
            + "(select 1 from KgMention m where m.entityId = e.id)")
    List<UUID> findOrphanedAmong(@Param("ids") Collection<UUID> ids, @Param("before") Instant before);

    /** Deletes the orphans {@link #findOrphanedAmong} names, with the same guard repeated in the
     *  statement itself - so a row stamped between the read and the delete is not taken. */
    @Modifying
    @Query("delete from KgEntity e where e.id in :ids and e.lastSeenAt < :before and not exists "
            + "(select 1 from KgMention m where m.entityId = e.id)")
    int deleteOrphansAmong(@Param("ids") Collection<UUID> ids, @Param("before") Instant before);
}
