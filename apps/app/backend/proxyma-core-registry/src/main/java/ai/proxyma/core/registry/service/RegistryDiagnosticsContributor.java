package ai.proxyma.core.registry.service;

import ai.proxyma.core.common.diagnostics.DiagLabels;
import ai.proxyma.core.common.diagnostics.DiagnosticsContributor;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.model.TrackedDocument;
import ai.proxyma.core.registry.repository.RegisteredConnectorRepository;
import ai.proxyma.core.registry.repository.TrackedDocumentRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;

/**
 * The index's contribution to the daily diagnostics snapshot: how many sources of each TYPE, and
 * how many documents as a RANGE ("1k-10k"), never an exact count, a name, a path or a URL.
 *
 * <p>Answers which connectors are worth the maintenance they cost and how large real indexes get -
 * the second decides what "fast enough" has to mean for search and sync.
 */
@Component
class RegistryDiagnosticsContributor implements DiagnosticsContributor {

    private final RegisteredConnectorRepository connectors;
    private final TrackedDocumentRepository documents;

    RegistryDiagnosticsContributor(RegisteredConnectorRepository connectors, TrackedDocumentRepository documents) {
        this.connectors = connectors;
        this.documents = documents;
    }

    @Override
    public void contribute(BiConsumer<String, Object> put) {
        Map<String, Long> byType = new TreeMap<>();
        for (RegisteredConnector r : connectors.findAll()) {
            if (r.getConnectorType() == null) continue;
            byType.merge(r.getConnectorType().name(), 1L, Long::sum);
        }
        put.accept("sources", byType.values().stream().mapToLong(Long::longValue).sum());
        byType.forEach((type, n) -> put.accept("sources." + type, n));
        put.accept("documents", DiagLabels.countBucket(
                documents.countByStatusIn(List.of(TrackedDocument.Status.COMPLETED))));
        put.accept("documentsFailed", DiagLabels.countBucket(
                documents.countByStatusIn(List.of(TrackedDocument.Status.FAILED))));
    }
}
