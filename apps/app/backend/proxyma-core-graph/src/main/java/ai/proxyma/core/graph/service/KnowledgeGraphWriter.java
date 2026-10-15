package ai.proxyma.core.graph.service;

import ai.proxyma.core.graph.model.KgEntity;
import ai.proxyma.core.graph.model.KgMention;
import ai.proxyma.core.graph.model.KgRelation;
import ai.proxyma.core.graph.repository.KgAliasRepository;
import ai.proxyma.core.graph.repository.KgDocumentStateRepository;
import ai.proxyma.core.graph.repository.KgEntityRepository;
import ai.proxyma.core.graph.repository.KgMentionRepository;
import ai.proxyma.core.graph.repository.KgRelationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Persists a document's extraction, and owns every delete path into the graph.
 *
 * <p><b>A separate bean from {@link KnowledgeGraphService} for one reason: transaction
 * boundaries.</b> Extraction is an LLM call per chunk taking seconds; writing is milliseconds. The
 * service extracts outside any transaction and hands the finished document in here.
 *
 * <p><b>A document is REPLACED in one transaction</b> (since 2026-10-13). It used to be deleted
 * when its re-read began and written back a chunk at a time, so for as long as the re-read took -
 * hours, for a large document on a local model - its graph was empty or partial, and a re-read that
 * stopped half way left it partial for good. Now the old contribution stays until the new one is
 * complete, and the swap is atomic.
 */
@Service
public class KnowledgeGraphWriter {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphWriter.class);

    /** Ids per IN list. A connector's delete touches every entity it mentions, which is unbounded. */
    static final int BATCH = 500;

    private final KgEntityRepository entities;
    private final KgAliasRepository aliases;
    private final KgMentionRepository mentions;
    private final KgRelationRepository relations;
    private final KgDocumentStateRepository documentStates;
    private final EntityResolver resolver;
    private org.springframework.context.ApplicationEventPublisher events;

    // Optional, so the writer can be built by hand in a test with no context around it.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEvents(org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
    }

    public KnowledgeGraphWriter(KgEntityRepository entities, KgAliasRepository aliases,
                                KgMentionRepository mentions, KgRelationRepository relations,
                                KgDocumentStateRepository documentStates, EntityResolver resolver) {
        this.entities = entities;
        this.aliases = aliases;
        this.mentions = mentions;
        this.relations = relations;
        this.documentStates = documentStates;
        this.resolver = resolver;
    }

    /** One chunk's extraction, with the reference of the chunk it came from (its index). */
    public record ChunkGraph(String chunkRef, ExtractedGraph graph) {}

    /**
     * Replaces everything one document contributed with what its chunks produced - nothing, when
     * the model found nothing.
     *
     * <p>Relations are resolved through the SAME name-to-entity map the chunk's mentions were
     * written from, not by resolving their subject and object independently. The extractor is
     * instructed that both ends must appear in its own entity list, so a relation naming something
     * that is not in that map is one the model invented mid-answer - dropping it is cheaper and
     * safer than creating an entity with no mention and therefore no provenance.
     *
     * <p><b>One row per triple per document</b>, carrying the first passage that stated it. Weight
     * counted passages, and chunks overlap, so a sentence across a chunk boundary was "two
     * sources"; corroboration is now counted across documents (KgRelation.weight).
     */
    @Transactional
    public void replaceDocument(UUID connectorId, UUID documentId, List<ChunkGraph> chunks) {
        Instant start = Instant.now();
        List<UUID> before = mentions.entityIdsForDocument(documentId);
        int removedMentions = mentions.deleteByDocumentId(documentId);
        int removedRelations = relations.deleteByDocumentId(documentId);

        Set<String> triples = new HashSet<>();
        int written = 0;
        for (ChunkGraph chunk : chunks == null ? List.<ChunkGraph>of() : chunks) {
            if (chunk.graph() == null || chunk.graph().isEmpty()) continue;
            written += writeChunk(connectorId, documentId, chunk.chunkRef(), chunk.graph(), triples);
        }

        int orphans = purgeOrphans(before, start);
        log.debug("[Graph] document {} replaced: {} mention(s) and {} relation(s) out, {} relation(s) in, "
                + "{} orphaned entity(ies)", documentId, removedMentions, removedRelations, written, orphans);
    }

    /**
     * Replaces ONLY what the document's source metadata states (chunk reference
     * KnowledgeGraphService.METADATA_REF), leaving what a model read alone - the metadata pass,
     * which needs no model. A triple the model already drew from the text is not written again.
     */
    @Transactional
    public void replaceMetadata(UUID connectorId, UUID documentId, ExtractedGraph facts) {
        Instant start = Instant.now();
        String ref = KnowledgeGraphService.METADATA_REF;
        List<UUID> before = mentions.entityIdsForDocumentChunk(documentId, ref);
        mentions.deleteByDocumentIdAndChunkRef(documentId, ref);
        relations.deleteByDocumentIdAndChunkRef(documentId, ref);
        if (facts != null && !facts.isEmpty()) {
            Set<String> triples = new HashSet<>();
            for (KgRelation r : relations.findByDocumentId(documentId, org.springframework.data.domain.Pageable.unpaged())) {
                triples.add(tripleKey(r));
            }
            writeChunk(connectorId, documentId, ref, facts, triples);
        }
        purgeOrphans(before, start);
    }

    /**
     * Adds chunks to a document's graph without touching the rest of it - the chunks a partial read
     * could not get answered, read on a later pass. A triple the document already states is not
     * written again, so a resumed read keeps one row per triple per document.
     *
     * <p>Whatever those very chunks wrote before is replaced, not added to. A resumed write that
     * committed while its progress row did not still listed the same chunks as missing, and the next
     * offer wrote their mentions a second time - doubling the counts the view sizes nodes by and the
     * tallies that choose an entity's display name.
     */
    @Transactional
    public void addChunks(UUID connectorId, UUID documentId, List<ChunkGraph> chunks) {
        Instant start = Instant.now();
        List<UUID> before = new ArrayList<>();
        for (ChunkGraph chunk : chunks == null ? List.<ChunkGraph>of() : chunks) {
            before.addAll(mentions.entityIdsForDocumentChunk(documentId, chunk.chunkRef()));
            mentions.deleteByDocumentIdAndChunkRef(documentId, chunk.chunkRef());
            relations.deleteByDocumentIdAndChunkRef(documentId, chunk.chunkRef());
        }
        Set<String> triples = new HashSet<>();
        for (KgRelation r : relations.findByDocumentId(documentId, org.springframework.data.domain.Pageable.unpaged())) {
            triples.add(tripleKey(r));
        }
        for (ChunkGraph chunk : chunks == null ? List.<ChunkGraph>of() : chunks) {
            if (chunk.graph() == null || chunk.graph().isEmpty()) continue;
            writeChunk(connectorId, documentId, chunk.chunkRef(), chunk.graph(), triples);
        }
        purgeOrphans(before, start);
    }

    private int writeChunk(UUID connectorId, UUID documentId, String chunkRef, ExtractedGraph extracted,
                           Set<String> triples) {
        Map<String, KgEntity> byName = new HashMap<>();

        for (ExtractedGraph.Entity e : extracted.entities()) {
            KgEntity entity = resolver.resolve(e.name(), e.storedType(), e.aliasesOrEmpty(), connectorId);
            if (entity == null) continue;
            byName.put(GraphNames.normalize(e.name()), entity);
            for (String alias : e.aliasesOrEmpty()) {
                if (GraphNames.isUsable(alias)) byName.putIfAbsent(GraphNames.normalize(alias), entity);
            }

            KgMention mention = new KgMention();
            mention.setEntityId(entity.getId());
            mention.setConnectorId(connectorId);
            mention.setDocumentId(documentId);
            mention.setChunkRef(chunkRef);
            mention.setSurfaceForm(GraphNames.display(e.name()));
            String type = e.storedType();
            mention.setEntityType(type.isEmpty() ? null : type);
            mention.setDescription(truncate(e.description(), KgMention.MAX_DESCRIPTION_LENGTH));
            mentions.save(mention);
        }

        int written = 0;
        for (ExtractedGraph.Relation r : extracted.relations()) {
            if (!r.isUsable()) continue;
            KgEntity subject = byName.get(GraphNames.normalize(r.subject()));
            KgEntity object = byName.get(GraphNames.normalize(r.object()));
            if (subject == null || object == null) continue;
            if (subject.getId().equals(object.getId())) continue;   // resolved to one thing

            String predicate = GraphNames.predicate(r.predicate());
            if (predicate.isEmpty()) continue;
            if (!triples.add(tripleKey(subject.getId(), predicate, object.getId(), r.validFrom(), r.occurredAt()))) continue;

            KgRelation row = new KgRelation();
            row.setSubjectId(subject.getId());
            row.setObjectId(object.getId());
            row.setPredicate(predicate);
            row.setDescription(truncate(r.description(), KgRelation.MAX_DESCRIPTION_LENGTH));
            row.setConnectorId(connectorId);
            row.setDocumentId(documentId);
            row.setChunkRef(chunkRef);
            row.setWeight(1);
            row.setValidFrom(r.validFrom());
            row.setValidTo(r.validTo());
            row.setOccurredAt(r.occurredAt());
            relations.save(row);
            written++;
        }
        return written;
    }

    /**
     * Removes everything one document contributed, then whatever that orphaned. Not called before
     * a re-read any more - {@link #replaceDocument} does its own - but by {@link #forgetDocument}.
     */
    @Transactional
    public void deleteDocument(UUID documentId) {
        Instant start = Instant.now();
        List<UUID> before = mentions.entityIdsForDocument(documentId);
        int removedMentions = mentions.deleteByDocumentId(documentId);
        int removedRelations = relations.deleteByDocumentId(documentId);
        int orphans = purgeOrphans(before, start);
        if (removedMentions > 0 || removedRelations > 0) {
            log.debug("[Graph] document {} removed: {} mention(s), {} relation(s), {} orphaned entity(ies)",
                    documentId, removedMentions, removedRelations, orphans);
        }
    }

    /**
     * The same, for every document of a connector - a connector being deleted or its graph opt-in
     * being switched off - and its extraction progress and its aliases with it.
     *
     * <p>The progress rows go in the same transaction, because a source switched back on has to
     * start again from nothing: a DONE row left behind would tell the extraction worker a
     * document's graph exists when this method has just deleted it.
     */
    @Transactional
    public void deleteConnector(UUID connectorId) {
        Instant start = Instant.now();
        List<UUID> before = mentions.entityIdsForConnector(connectorId);
        int removedMentions = mentions.deleteByConnectorId(connectorId);
        int removedRelations = relations.deleteByConnectorId(connectorId);
        aliases.deleteByConnectorId(connectorId);
        documentStates.deleteByConnectorId(connectorId);
        int orphans = purgeOrphans(before, start);
        log.info("[Graph] connector {} removed from the graph: {} mention(s), {} relation(s), "
                + "{} orphaned entity(ies)", connectorId, removedMentions, removedRelations, orphans);
    }

    /**
     * A document gone for good: its contribution AND its progress row. Distinct from
     * {@link #deleteDocument}, which leaves the progress row alone.
     */
    @Transactional
    public void forgetDocument(UUID documentId) {
        deleteDocument(documentId);
        documentStates.deleteByDocumentId(documentId);
    }

    /**
     * Of the entities a delete touched, the ones no mention points at any more - removed with their
     * aliases, in batches.
     *
     * <p>Runs AFTER the mentions are gone, never before. Only over {@code touched}, never the whole
     * table, and never an entity resolved since {@code start} - see
     * KgEntityRepository.findOrphanedAmong for both.
     */
    int purgeOrphans(Collection<UUID> touched, Instant start) {
        if (touched == null || touched.isEmpty()) return 0;
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(touched));
        int removed = 0;
        List<UUID> gone = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += BATCH) {
            List<UUID> batch = ids.subList(from, Math.min(ids.size(), from + BATCH));
            List<UUID> orphans = entities.findOrphanedAmong(batch, start);
            removed += entities.deleteOrphansAmong(batch, start);
            aliases.deleteOfMissingEntities(batch);
            gone.addAll(orphans);
        }
        if (events != null && !gone.isEmpty()) {
            events.publishEvent(new ai.proxyma.core.graph.event.GraphEntitiesRemovedEvent(List.copyOf(gone)));
        }
        return removed;
    }

    /** One row per triple per document: what "already stated" means when a write dedupes. */
    static String tripleKey(KgRelation r) {
        return tripleKey(r.getSubjectId(), r.getPredicate(), r.getObjectId(), r.getValidFrom(), r.getOccurredAt());
    }

    // The start and the moment are part of it: "assigned to Ana" in March and again in June are two
    // spells, and two status changes to the same status are two events.
    static String tripleKey(UUID subject, String predicate, UUID object, Instant validFrom, Instant occurredAt) {
        return subject + "|" + predicate + "|" + object + "|" + validFrom + "|" + occurredAt;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() <= max ? t : t.substring(0, max);
    }
}
