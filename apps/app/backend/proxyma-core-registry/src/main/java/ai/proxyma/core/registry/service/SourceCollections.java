package ai.proxyma.core.registry.service;

import java.util.Optional;
import java.util.UUID;

/**
 * How a source (a RegisteredConnector) names its collections in the vector store and the keyword
 * index, and how a collection is read back as its source.
 *
 * <p>A source's collection is its id without dashes; a model-changed reindex builds a SHADOW beside
 * it under the same name plus {@link #SHADOW_SUFFIX}, which a swap later renames over the live one
 * (RegisteredConnector.reindexShadowCollectionId). A shadow holds the same source's documents, so a
 * hit found in one belongs to the base source. Any other collection - guardrail policies, agent
 * memory, entity names - names no source.
 */
public final class SourceCollections {

    public static final String SHADOW_SUFFIX = "_reindex";

    private SourceCollections() {
    }

    /** The live collection of the source with this id. */
    public static String of(UUID sourceId) {
        return sourceId.toString().replace("-", "");
    }

    /** The shadow a model-changed reindex builds beside {@code collectionId}. */
    public static String shadowOf(String collectionId) {
        return collectionId + SHADOW_SUFFIX;
    }

    /** {@code collectionId} with any shadow suffix taken off - the live collection it stands for. */
    public static String baseOf(String collectionId) {
        if (collectionId == null) return null;
        return collectionId.endsWith(SHADOW_SUFFIX)
                ? collectionId.substring(0, collectionId.length() - SHADOW_SUFFIX.length())
                : collectionId;
    }

    /** The source whose collection (live or shadow) this is; empty for one that names no source. */
    public static Optional<UUID> sourceOf(String collectionId) {
        String hex = baseOf(collectionId);
        if (hex == null || !hex.matches("[0-9a-fA-F]{32}")) return Optional.empty();
        return Optional.of(UUID.fromString(hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-"
                + hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" + hex.substring(20)));
    }
}
