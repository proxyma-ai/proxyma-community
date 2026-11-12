package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.repository.RegisteredConnectorRepository;
import ai.proxyma.core.settings.connection.ConnectionUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The sources reading through a connection: how many and which, for the warning before it is deleted,
 * and - once it is - each of them marked as needing another connection (ConnectionUsage).
 *
 * <p><b>Except the source types whose data the service's own terms tie to the person's consent</b>
 * ({@link #DELETED_WITH_CONNECTION}, 2026-11-08): YouTube's API Services policies require an app to delete
 * the data it holds from a channel within seven days of the person removing its access in the app, and
 * deleting the connection is that removal (its grant is revoked at Google - OAuth2ConnectionService). A
 * YouTube source reading through it is removed, with everything it indexed, as "Remove" on its card would.
 */
@Component
class RegistryConnectionUsage implements ConnectionUsage {

    private static final Logger log = LoggerFactory.getLogger(RegistryConnectionUsage.class);

    /** Source types removed, not kept, when their connection is deleted. */
    static final Set<ConnectorType> DELETED_WITH_CONNECTION = Set.of(ConnectorType.YOUTUBE_CHANNEL);

    private final RegisteredConnectorRepository repository;
    // ConnectorService.removeConnector, the full teardown; looked up late - ConnectorService reaches the
    // connection service, which reaches this.
    private Consumer<UUID> remover = id -> { };

    RegistryConnectionUsage(RegisteredConnectorRepository repository) {
        this.repository = repository;
    }

    @Autowired(required = false)
    void setConnectors(ObjectProvider<ConnectorService> connectors) {
        this.remover = id -> {
            ConnectorService service = connectors.getIfAvailable();
            if (service != null) service.removeConnector(id, null);
        };
    }

    /** Tests: how a source is removed. */
    void setRemoverForTest(Consumer<UUID> remover) {
        this.remover = remover;
    }

    @Override
    public long sourcesUsing(UUID connectionId) {
        return repository.countByConnectionId(connectionId);
    }

    @Override
    public List<String> sourceNamesUsing(UUID connectionId, int limit) {
        if (limit <= 0) return List.of();
        return repository.findNamesByConnectionId(connectionId, PageRequest.of(0, limit));
    }

    // Their items stay where they are; only the card's status and note change, as the next sync would
    // have changed them (SyncOrchestratorService.recordNoConnection) - said now rather than at the next
    // pass, so the Sources page agrees with the deletion the moment it is made. A YouTube source is then
    // removed whole.
    @Override
    public void connectionDeleted(UUID connectionId) {
        repository.markConnectionGone(connectionId, RegisteredConnector.Status.ERROR,
                RegisteredConnector.boundedSyncError(SyncOrchestratorService.CONNECTION_DELETED_MESSAGE));
        for (ConnectorType type : DELETED_WITH_CONNECTION) {
            for (RegisteredConnector source : repository.findByConnectionIdAndConnectorType(connectionId, type)) {
                try {
                    remover.accept(source.getId());
                    log.info("[Registry] '{}' removed with its deleted {} connection", source.getName(), type);
                } catch (RuntimeException e) {
                    log.warn("[Registry] '{}' could not be removed with its connection: {}", source.getName(), e.getMessage());
                }
            }
        }
    }
}
