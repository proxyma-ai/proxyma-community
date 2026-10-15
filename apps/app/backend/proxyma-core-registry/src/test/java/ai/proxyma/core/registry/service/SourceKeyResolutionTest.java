package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.registry.model.RegisteredConnector;
import ai.proxyma.core.registry.repository.RegisteredConnectorRepository;
import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.settings.connection.ConnectorConnectionService;
import ai.proxyma.core.settings.connection.ResolvedConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which connection a live tool call naming a project key reads through.
 *
 * <p>One user may register the same key on two servers - registration allows it - and the lookup of
 * their OWN sources was an Optional finder, so every tool call naming that key threw "query did not
 * return a unique result: 2" on Enterprise Server. An ambiguous name falls back to the caller's
 * default connection, as an ambiguous shared one always did; a unique one is still followed.
 */
class SourceKeyResolutionTest {

    private static final String ANA = "ana";
    private static final ResolvedConnection WORK = connection("Work", "https://jira.work.example", true);
    private static final ResolvedConnection VENDOR = connection("Vendor", "https://jira.vendor.example", false);

    private RegisteredConnectorRepository connectors;
    private ConnectorEndpointResolver resolver;

    private static ResolvedConnection connection(String name, String url, boolean isDefault) {
        return new ResolvedConnection(UUID.randomUUID(), ANA, name, ConnectionProtocol.JIRA, url, null, "pat",
                isDefault, true, false, null, null, null);
    }

    private static RegisteredConnector source(String key, ResolvedConnection through) {
        RegisteredConnector r = new RegisteredConnector();
        r.setId(UUID.randomUUID());
        r.setUserId(ANA);
        r.setConnectorType(ConnectorType.JIRA);
        r.setName(key);
        r.setConnectionId(through.id());
        return r;
    }

    @BeforeEach
    void setUp() {
        connectors = mock(RegisteredConnectorRepository.class);
        ConnectorConnectionService connections = mock(ConnectorConnectionService.class);
        for (ResolvedConnection c : List.of(WORK, VENDOR)) {
            when(connections.resolve(c.id())).thenReturn(Optional.of(c));
        }
        when(connections.defaultFor(ANA, ConnectionProtocol.JIRA)).thenReturn(Optional.of(WORK));
        when(connectors.findAllByConnectorTypeAndNameIgnoreCase(any(), any())).thenReturn(List.of());
        resolver = new ConnectorEndpointResolver(connectors, connections);
    }

    @Test
    @DisplayName("a key registered on two of the caller's servers resolves to their default, without throwing")
    void keyOnTwoServersFallsBackToTheDefault() {
        when(connectors.findAllByUserIdAndConnectorTypeAndNameIgnoreCase(ANA, ConnectorType.JIRA, "PROJ"))
                .thenReturn(List.of(source("PROJ", VENDOR), source("PROJ", WORK)));

        assertThat(resolver.forSource(ConnectorType.JIRA, "PROJ", ANA).connectionId()).isEqualTo(WORK.id());
    }

    @Test
    @DisplayName("a key registered once is read through that source's own connection")
    void uniqueKeyFollowsItsSource() {
        when(connectors.findAllByUserIdAndConnectorTypeAndNameIgnoreCase(ANA, ConnectorType.JIRA, "OPS"))
                .thenReturn(List.of(source("OPS", VENDOR)));

        assertThat(resolver.forSource(ConnectorType.JIRA, "OPS", ANA).connectionId()).isEqualTo(VENDOR.id());
    }
}
