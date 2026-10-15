package ai.proxyma.core.registry.service;

import ai.proxyma.core.graph.event.GraphEntitiesRemovedEvent;
import ai.proxyma.core.graph.service.EntityText;
import ai.proxyma.core.graph.service.KnowledgeGraphService;
import ai.proxyma.core.graph.service.SimilarEntity;
import ai.proxyma.core.indexstore.VectorStoreService;
import ai.proxyma.core.settings.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A vector per knowledge-graph entity NAME, so a query can find an entity by what its name means -
 * "authentication service" for "Auth Service", "payout" for "disbursement" - and the traversal can
 * treat a name very close in meaning as a synonym (KnowledgeGraphService.seedsFor).
 *
 * <h2>One collection per embedding model</h2>
 *
 * Vectors from two models cannot be compared, and a Chroma collection has one dimension. So the
 * collection is named for the model ({@link #collectionFor}), every entity records which model
 * embedded it (KgEntity.vectorKey), and a model change simply leaves every entity due again: the
 * graph worker embeds a batch per pass ({@link #embedPending}) into the new collection, and the old
 * one is deleted once no entity is left on it.
 *
 * <h2>What a vector gives away</h2>
 *
 * Nothing on its own. A search answers entity ids; the traversal shows an entity only if a source
 * the asker can read mentions it, and names it only as such a source writes it. The vector is made
 * from the normalized name and type, never a description, which is per document.
 */
@Service
public class EntityVectorService {

    private static final Logger log = LoggerFactory.getLogger(EntityVectorService.class);

    private final VectorStoreService vectorStore;
    private final KnowledgeGraphService knowledgeGraph;
    private final SettingsService settings;

    /** Embedding models whose collection is waiting to be emptied of entities, then deleted. */
    private final Set<String> retiring = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Deleted entities whose vectors the store would not remove - tried again on the next pass. */
    private final Set<UUID> undeleted = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public EntityVectorService(VectorStoreService vectorStore, KnowledgeGraphService knowledgeGraph,
                               SettingsService settings) {
        this.vectorStore = vectorStore;
        this.knowledgeGraph = knowledgeGraph;
        this.settings = settings;
    }

    /** The current embedding model as a key, or null when none is configured. */
    String currentKey() {
        String provider = settings.getEmbeddingProvider();
        String model = settings.getEmbeddingModel();
        if (provider == null || provider.isBlank() || model == null || model.isBlank()) return null;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest((provider + "|" + model).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception e) {
            return null;
        }
    }

    /** The collection one model's entity vectors live in. Short enough for both stores' names. */
    static String collectionFor(String key) {
        return "kgentities" + key;
    }

    /**
     * Embeds up to {@code batch} entities whose vector is missing or from another model, and deletes
     * a previous model's collection once nothing is left on it. Returns how many it embedded - 0
     * with no embedding model, or when the store or the model refuses, which ends the run until the
     * next pass rather than retrying in a loop.
     */
    public int embedPending(int batch) {
        String key = currentKey();
        if (key == null) return 0;
        try {
            if (!undeleted.isEmpty()) deleteVectors(key, List.copyOf(undeleted));
            List<String> others = knowledgeGraph.otherVectorKeys(key);
            retiring.addAll(others);
            List<EntityText> pending = knowledgeGraph.entitiesNeedingVectors(key, batch);
            if (!pending.isEmpty()) {
                List<Document> docs = new ArrayList<>(pending.size());
                for (EntityText e : pending) {
                    // A UUID id, and the same value as "source": pgvector requires the first, and
                    // the two stores delete by one or the other.
                    docs.add(Document.builder().id(e.id().toString()).text(e.text())
                            .metadata(Map.of("source", e.id().toString())).build());
                }
                vectorStore.upsert(collectionFor(key), docs);
                knowledgeGraph.markVectorized(pending.stream().map(EntityText::id).toList(), key);
            }
            retireEmptied(key);
            return pending.size();
        } catch (RuntimeException e) {
            log.debug("[GraphVectors] entity names not embedded this pass: {}", e.getMessage());
            return 0;
        }
    }

    // A previous model's collection goes once no entity is left on it.
    private void retireEmptied(String key) {
        if (retiring.isEmpty()) return;
        Set<String> still = new LinkedHashSet<>(knowledgeGraph.otherVectorKeys(key));
        for (String old : List.copyOf(retiring)) {
            if (old.equals(key) || still.contains(old)) continue;
            try {
                vectorStore.deleteCollection(collectionFor(old));
            } catch (RuntimeException e) {
                log.debug("[GraphVectors] could not delete the entity vectors of a previous model: {}", e.getMessage());
            }
            retiring.remove(old);
        }
    }

    /**
     * Entities whose name means something close to {@code text}, best first. Empty with no
     * embedding model or vectors yet - never an error: the caller still has every lexical tier.
     */
    public List<SimilarEntity> similar(String text, int topK, double minScore) {
        String key = currentKey();
        if (key == null || text == null || text.isBlank()) return List.of();
        try {
            List<SimilarEntity> out = new ArrayList<>();
            for (Document d : vectorStore.search(List.of(collectionFor(key)), text, topK, minScore)) {
                Object source = d.getMetadata().get("source");
                String id = source != null ? source.toString() : d.getId();
                try {
                    out.add(new SimilarEntity(UUID.fromString(id), d.getScore() == null ? minScore : d.getScore()));
                } catch (IllegalArgumentException ignored) {
                    // Not one of ours.
                }
            }
            return out;
        } catch (RuntimeException e) {
            log.debug("[GraphVectors] name similarity unavailable: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Entities the graph deleted take their vectors with them - AFTER the graph write commits, and
     * in batches.
     *
     * <p>It ran inside the write's transaction, one store call per entity: switching off a source
     * with fifty thousand orphaned entities held that transaction open across fifty thousand
     * ChromaDB requests, and a write that then rolled back - the entity race the graph retries on -
     * had already deleted the vectors of entities that survived, which nothing re-embeds because
     * their vector key still matches. A batch the store refuses is kept and tried again by the next
     * {@link #embedPending} pass, rather than leaving vectors of deleted entities to fill the
     * similarity search's few slots.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onEntitiesRemoved(GraphEntitiesRemovedEvent event) {
        String key = currentKey();
        if (key == null || event.entityIds().isEmpty()) return;
        deleteVectors(key, event.entityIds());
    }

    private void deleteVectors(String key, Collection<UUID> ids) {
        try {
            vectorStore.deleteBySourceMetadata(collectionFor(key), ids.stream().map(UUID::toString).toList());
            undeleted.removeAll(ids);
        } catch (RuntimeException e) {
            undeleted.addAll(ids);
            log.debug("[GraphVectors] vectors of {} deleted entit(ies) left for the next pass: {}",
                    ids.size(), e.getMessage());
        }
    }
}
