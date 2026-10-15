package ai.proxyma.core.graph.service;

import ai.proxyma.core.graph.model.KgAlias;
import ai.proxyma.core.graph.model.KgEntity;
import ai.proxyma.core.graph.repository.KgAliasRepository;
import ai.proxyma.core.graph.repository.KgEntityRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Turns an extracted NAME into the entity row it belongs to, creating one when it is genuinely new.
 *
 * <p>proxyma-docs/architecture/knowledge-graph.md calls entity resolution "the quiet killer", and
 * it is right: get it wrong and the graph looks authoritative while answering from a fragment of
 * what it should have connected. That document specifies three steps, cheapest first.
 *
 * <h2>What is implemented, and what is not</h2>
 *
 * <b>Step 1 - exact and normalised match, against both the entity name and the alias table.</b>
 * Implemented here, and it is the whole of resolution in this release.
 *
 * <p><b>Step 2 (embedding similarity within a type) and step 3 (LLM adjudication of the narrow
 * band that survives) are NOT implemented.</b> That is a deliberate stopping point, not an
 * oversight. Steps 2 and 3 both MERGE on a guess, and a wrong merge is the failure that cannot be
 * seen from the outside: two unrelated things become one confident entity and every answer drawn
 * from it is wrong in a way that still cites a real document. Step 1 fails the other way - "Auth
 * Service" and "authsvc" stay separate until some passage names them together and supplies the
 * alias - which yields a graph that is incomplete rather than untrue.
 *
 * <p>The extractor is asked for `aliases` precisely because it makes step 1 do more work: a
 * passage that writes "Auth Service (authsvc)" resolves both strings without anything having to
 * guess. An alias is the SOURCE's own (see KgAlias): it is recorded for, and consulted by, the
 * documents of the source that wrote it.
 */
@Service
public class EntityResolver {

    private final KgEntityRepository entities;
    private final KgAliasRepository aliases;

    public EntityResolver(KgEntityRepository entities, KgAliasRepository aliases) {
        this.entities = entities;
        this.aliases = aliases;
    }

    /**
     * The entity this name refers to. Called inside the writer's transaction, once per extracted
     * entity per chunk.
     *
     * @param type        the stored form (GraphNames.type) - {@link GraphNames#UNTYPED} when none
     * @param connectorId the source whose document is being written: its aliases are the ones
     *                    consulted, and the ones this passage's aliases are recorded under
     * @return null when the name is not worth storing (see {@link GraphNames#isUsable})
     */
    public KgEntity resolve(String rawName, String type, List<String> rawAliases, UUID connectorId) {
        if (!GraphNames.isUsable(rawName)) return null;

        String normalized = GraphNames.normalize(rawName);
        String storedType = GraphNames.type(type);

        KgEntity entity = lookup(normalized, storedType, connectorId);
        if (entity == null) {
            entity = create(GraphNames.display(rawName), normalized, storedType);
        } else {
            // Stamped on every resolve, and that is load-bearing: the orphan purge leaves alone an
            // entity touched after it started, which is what stops it deleting an entity this
            // transaction is about to write a mention for (KgEntityRepository.findOrphanedAmong).
            entity.setLastSeenAt(Instant.now());
            if (entity.getFoldedName() == null || entity.getSquashedName() == null) entity.setDerivedKeys(normalized);
            // An entity first seen without a type, now named with one. The reverse is never done -
            // a typed entity is not demoted by a later passage that failed to classify it.
            //
            // THE SLOT THIS MOVES INTO WAS EMPTY WHEN WE LOOKED, WHICH IS NOT THE SAME AS EMPTY. A
            // second connector syncing in parallel can create that exact (name, type) row between
            // the lookup and the commit. The failure arrives at the transaction boundary, where
            // KnowledgeGraphService retries the document once - by which time the winner has
            // committed and the lookup above finds it.
            if (entity.typeOrNull() == null && !storedType.isEmpty()) {
                entity.setType(storedType);
            }
            entity = entities.save(entity);
        }

        registerAliases(entity, rawAliases, connectorId);
        return entity;
    }

    private KgEntity lookup(String normalized, String type, UUID connectorId) {
        if (!type.isEmpty()) {
            var byNameAndType = entities.findByNormalizedNameAndType(normalized, type);
            if (byNameAndType.isPresent()) return byNameAndType.get();
            // Same name, no type recorded yet - the row this passage is about to classify.
            List<KgEntity> unclassified = entities.findUnclassifiedByNormalizedName(normalized);
            if (!unclassified.isEmpty()) return unclassified.get(0);
        } else {
            List<KgEntity> unclassified = entities.findUnclassifiedByNormalizedName(normalized);
            if (!unclassified.isEmpty()) return unclassified.get(0);
            // A passage that could not tell what kind of thing a name is, where the graph knows
            // exactly ONE kind of thing by that name: that is the thing meant. Creating an untyped
            // node beside it made "X", "X (service)" and "X (product)" three nodes for one X. Two
            // or more typed rows are genuinely ambiguous, and the untyped row stays its own.
            List<KgEntity> typed = entities.findByNormalizedName(normalized);
            if (typed.size() == 1) return typed.get(0);
        }
        // The name may be a known alias of something already stored - in this same source.
        if (connectorId == null) return null;
        return aliases.findByNormalizedAliasAndConnectorId(normalized, connectorId)
                .flatMap(a -> entities.findById(a.getEntityId()))
                .orElse(null);
    }

    // No catch for a lost race here: with JPA the insert is not flushed until the transaction
    // commits, so the constraint fails there - after this method, after the whole document - and
    // KnowledgeGraphService retries the document once.
    private KgEntity create(String display, String normalized, String type) {
        KgEntity entity = new KgEntity();
        entity.setCanonicalName(display);
        entity.setNormalizedName(normalized);
        entity.setDerivedKeys(normalized);
        entity.setType(type);
        return entities.save(entity);
    }

    /**
     * Records the other names this passage used for the same entity, for its own source.
     *
     * <p>An alias the source already has for a DIFFERENT entity is left alone rather than
     * repointed: an alias two of its passages disagree about is exactly the ambiguous case, and
     * claiming it for whichever document was read most recently would make resolution depend on
     * sync order.
     */
    private void registerAliases(KgEntity entity, List<String> rawAliases, UUID connectorId) {
        if (rawAliases == null || rawAliases.isEmpty() || connectorId == null) return;
        for (String raw : rawAliases) {
            if (!GraphNames.isUsable(raw)) continue;
            String normalized = GraphNames.normalize(raw);
            if (normalized.equals(entity.getNormalizedName())) continue;
            if (aliases.existsByNormalizedAliasAndConnectorId(normalized, connectorId)) continue;
            KgAlias alias = new KgAlias();
            alias.setEntityId(entity.getId());
            alias.setConnectorId(connectorId);
            alias.setAlias(GraphNames.display(raw));
            alias.setNormalizedAlias(normalized);
            aliases.save(alias);
        }
    }
}
