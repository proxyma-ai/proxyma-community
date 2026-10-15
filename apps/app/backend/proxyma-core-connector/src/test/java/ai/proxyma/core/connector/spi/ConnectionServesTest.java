package ai.proxyma.core.connector.spi;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which connections a source may read through - ConnectorCatalog.serves, the one test registration,
 * a move, the resolver's default and the page's Connection field all ask. A service serves its own
 * types; another's only where its descriptor lists the type AND the connection's way of signing in
 * reaches it. The case it was made for: an Atlassian Cloud site is Jira and Confluence at once, so
 * one Cloud connection serves both, while a Data Center token reaches only the server it was made on.
 */
class ConnectionServesTest {

    private final ConnectorCatalog catalog = ConnectorCatalog.descriptorsOnly();

    @Test
    @DisplayName("an Atlassian Cloud connection of either service serves Jira and Confluence")
    void cloudServesBoth() {
        for (ConnectionProtocol p : new ConnectionProtocol[]{ConnectionProtocol.JIRA, ConnectionProtocol.CONFLUENCE}) {
            assertThat(catalog.serves(p, AuthKind.BASIC, ConnectorType.JIRA)).as(p.name()).isTrue();
            assertThat(catalog.serves(p, AuthKind.BASIC, ConnectorType.CONFLUENCE)).as(p.name()).isTrue();
        }
        assertThat(catalog.sourceTypes(ConnectionProtocol.JIRA, AuthKind.BASIC))
                .containsExactlyInAnyOrder(ConnectorType.JIRA, ConnectorType.CONFLUENCE);
    }

    @Test
    @DisplayName("a Data Center token - or a connection saved before methods existed - serves its own product only")
    void dataCenterServesItsOwn() {
        assertThat(catalog.serves(ConnectionProtocol.JIRA, AuthKind.TOKEN, ConnectorType.JIRA)).isTrue();
        assertThat(catalog.serves(ConnectionProtocol.JIRA, AuthKind.TOKEN, ConnectorType.CONFLUENCE)).isFalse();
        assertThat(catalog.serves(ConnectionProtocol.CONFLUENCE, AuthKind.TOKEN, ConnectorType.JIRA)).isFalse();
        assertThat(catalog.serves(ConnectionProtocol.JIRA, null, ConnectorType.CONFLUENCE)).isFalse();
        assertThat(catalog.sourceTypes(ConnectionProtocol.CONFLUENCE, AuthKind.TOKEN))
                .containsExactly(ConnectorType.CONFLUENCE);
    }

    @Test
    @DisplayName("a service never serves a type nothing says it reads, and every type its own service's")
    void nothingElse() {
        assertThat(catalog.serves(ConnectionProtocol.BITBUCKET, AuthKind.TOKEN, ConnectorType.JIRA)).isFalse();
        assertThat(catalog.serves(ConnectionProtocol.JIRA, AuthKind.BASIC, ConnectorType.BITBUCKET)).isFalse();
        assertThat(catalog.serves(ConnectionProtocol.SVN, AuthKind.BASIC, ConnectorType.SVN)).isTrue();
        assertThat(catalog.serves(null, AuthKind.BASIC, ConnectorType.JIRA)).isFalse();
        assertThat(catalog.servingProtocols(ConnectorType.CONFLUENCE))
                .containsExactly(ConnectionProtocol.CONFLUENCE, ConnectionProtocol.JIRA);
        assertThat(catalog.servingProtocols(ConnectorType.SVN)).containsExactly(ConnectionProtocol.SVN);
        assertThat(catalog.servingProtocols(ConnectorType.WEB_PAGE)).isEmpty();
    }
}
