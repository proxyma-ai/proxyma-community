package ai.proxyma.core.registry.service;

import ai.proxyma.core.registry.repository.RegisteredConnectorRepository;
import ai.proxyma.core.settings.connection.ConnectionUsage;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Counts the sources reading through a connection, so one still in use cannot be deleted. */
@Component
class RegistryConnectionUsage implements ConnectionUsage {

    private final RegisteredConnectorRepository repository;

    RegistryConnectionUsage(RegisteredConnectorRepository repository) {
        this.repository = repository;
    }

    @Override
    public long sourcesUsing(UUID connectionId) {
        return repository.countByConnectionId(connectionId);
    }
}
