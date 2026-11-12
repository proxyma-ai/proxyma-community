package ai.proxyma.core.settings.connection;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.spi.ConnectionPlugin;
import ai.proxyma.core.connector.spi.ConnectionTestResult;
import ai.proxyma.core.connector.spi.ConnectorCatalog;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A YouTube connection still called "YouTube" is named after its channel with nobody pressing Test (2026-11-11, the
 * maintainer: "What if the user don't hit the connection test when quota is come back?").
 */
class ConnectionNamingTest {

    @Test
    @DisplayName("each placeholder connection is tested and named after the channel it says; none while the built-in share is spent")
    @SuppressWarnings("unchecked")
    void namedWithoutATest() {
        ConnectorConnection waiting = new ConnectorConnection();
        waiting.setId(UUID.randomUUID());
        ConnectorConnection spent = new ConnectorConnection();
        spent.setId(UUID.randomUUID());
        ConnectorConnectionRepository repository = mock(ConnectorConnectionRepository.class);
        when(repository.findByProtocolAndName(ConnectionProtocol.YOUTUBE, ConnectionNaming.YOUTUBE_PLACEHOLDER))
                .thenReturn(List.of(waiting, spent));
        ConnectorConnectionService service = mock(ConnectorConnectionService.class);
        ResolvedConnection resolved = mock(ResolvedConnection.class);
        when(resolved.id()).thenReturn(waiting.getId());
        when(resolved.protocol()).thenReturn(ConnectionProtocol.YOUTUBE);
        when(service.resolve(waiting.getId())).thenReturn(Optional.of(resolved));
        when(service.nameAfterAccount(waiting.getId(), "YouTube", "Cảm âm Tiêu Dao")).thenReturn(true);
        ConnectionPlugin plugin = mock(ConnectionPlugin.class);
        when(plugin.test(any(), any())).thenReturn(new ConnectionTestResult(true, "Signed in as Cảm âm Tiêu Dao.",
                "Signed in as {user}.", Map.of("user", "Cảm âm Tiêu Dao")));
        ConnectorCatalog catalog = mock(ConnectorCatalog.class);
        when(catalog.connectionPlugin(ConnectionProtocol.YOUTUBE)).thenReturn(Optional.of(plugin));
        OAuth2ConnectionService oauth = mock(OAuth2ConnectionService.class);
        when(oauth.builtInSpentUntil(waiting.getId())).thenReturn(Optional.empty());
        when(oauth.builtInSpentUntil(spent.getId())).thenReturn(Optional.of(Instant.parse("2026-11-12T08:00:00Z")));
        ObjectProvider<OAuth2ConnectionService> oauthProvider = mock(ObjectProvider.class);
        when(oauthProvider.getIfAvailable()).thenReturn(oauth);

        ConnectionNaming naming = new ConnectionNaming(repository, service, catalog, ConnectorHttp.direct(), null, oauthProvider);

        assertThat(naming.nameWaiting()).isEqualTo(1);
        verify(service).nameAfterAccount(waiting.getId(), "YouTube", "Cảm âm Tiêu Dao");
        verify(service, never()).resolve(spent.getId());
    }
}
