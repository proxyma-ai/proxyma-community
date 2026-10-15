package ai.proxyma.core.tools;

import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.registry.service.ConnectorEndpointResolver;
import ai.proxyma.core.registry.service.ConnectorEndpointResolver.Endpoint;
import ai.proxyma.core.settings.config.ConnectorDefaultsProperties;
import ai.proxyma.core.tools.ToolConnectorResolver.Access;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which connection a Jira/Confluence/Bitbucket/SVN tool reaches, and whether it may.
 *
 * <p>The Read and Write switches are on each connection, and THIS is where they are enforced -
 * on the connection the call actually resolves to. A check made anywhere else (the chat's
 * pre-flight, say) looks at the conversation's connection, which is not the one a call naming a
 * source on a second server uses; the write through a read-only connection would then go through.
 */
class ToolConnectorResolverTest {

    private final ConnectorEndpointResolver endpoints = mock(ConnectorEndpointResolver.class);
    private final ActiveServers active = mock(ActiveServers.class);
    private final ToolConnectorResolver resolver =
            new ToolConnectorResolver(new ConnectorDefaultsProperties(), endpoints, active);

    private static Endpoint endpoint(String name, String url, String token, boolean read, boolean write) {
        return new Endpoint(UUID.randomUUID(), name, url, token, read, write);
    }

    @Test
    @DisplayName("a connection picked for the conversation wins, URL and token, whatever key the call names")
    void pickedConnectionWins() {
        when(active.active(ConnectorType.CONFLUENCE))
                .thenReturn(Optional.of(endpoint("Team", "https://team.example.org/wiki/", "team-token", true, false)));
        when(endpoints.forSource(eq(ConnectorType.CONFLUENCE), any(), any()))
                .thenReturn(endpoint("Default", "https://default.example.com", "default-token", true, true));

        assertThat(resolver.resolveBaseUrl(ConnectorType.CONFLUENCE, "Confluence", "DOCS"))
                .isEqualTo("https://team.example.org/wiki");
        assertThat(resolver.resolveAccessToken("confluence", "Confluence", "DOCS")).isEqualTo("team-token");
    }

    @Test
    @DisplayName("a write through a connection with Write off is refused, and the message names that connection")
    void writeOffIsRefused() {
        when(active.active(any())).thenReturn(Optional.empty());
        Endpoint prod = endpoint("Production", "https://jira.example.org", "tok", true, false);
        when(endpoints.forSource(eq(ConnectorType.JIRA), eq("PROD"), any())).thenReturn(prod);

        assertThat(resolver.resolveAccessToken("jira", "Jira", "PROD")).isEqualTo("tok");
        assertThatThrownBy(() -> resolver.resolveAccessToken("jira", "Jira", "PROD", Access.WRITE))
                .hasMessageContaining("\"Production\"")
                .hasMessageContaining("app://settings/connectors/" + prod.connectionId());
    }

    @Test
    @DisplayName("the switch is read off the connection the SOURCE KEY resolves to, not the conversation's default")
    void sourceKeyDecides() {
        when(active.active(any())).thenReturn(Optional.empty());
        when(endpoints.forSource(eq(ConnectorType.JIRA), eq("TEST"), any()))
                .thenReturn(endpoint("Test", "https://jira-test.example.org", "tok-test", true, true));
        when(endpoints.forSource(eq(ConnectorType.JIRA), eq("PROD"), any()))
                .thenReturn(endpoint("Production", "https://jira.example.org", "tok-prod", true, false));

        assertThat(resolver.resolveAccessToken("jira", "Jira", "TEST", Access.WRITE)).isEqualTo("tok-test");
        assertThatThrownBy(() -> resolver.resolveAccessToken("jira", "Jira", "PROD", Access.WRITE))
                .hasMessageContaining("\"Production\"");
    }

    @Test
    @DisplayName("Read off refuses reads - but not the base URL a write needs")
    void readOff() {
        when(active.active(any())).thenReturn(Optional.empty());
        when(endpoints.forSource(eq(ConnectorType.BITBUCKET), any(), any()))
                .thenReturn(endpoint("Writer", "https://git.example.com/", "tok", false, true));

        assertThatThrownBy(() -> resolver.resolveAccessToken("bitbucket", "Bitbucket", "PROJ/repo"))
                .hasMessageContaining("read is turned off");
        assertThat(resolver.resolveBaseUrl(ConnectorType.BITBUCKET, "Bitbucket", "PROJ/repo")).isEqualTo("https://git.example.com");
        assertThat(resolver.resolveAccessToken("bitbucket", "Bitbucket", "PROJ/repo", Access.WRITE)).isEqualTo("tok");
    }

    @Test
    @DisplayName("with no connection at all, the model is told where the user adds one")
    void noConnection() {
        when(active.active(any())).thenReturn(Optional.empty());
        when(endpoints.forSource(eq(ConnectorType.JIRA), any(), any())).thenReturn(Endpoint.EMPTY);
        assertThatThrownBy(() -> resolver.resolveBaseUrl(ConnectorType.JIRA, "Jira", null))
                .hasMessageContaining("no Jira connection")
                .hasMessageContaining("app://settings/connectors/jira");
    }

    @Test
    @DisplayName("a connection without a token says which one, and links to it")
    void connectionWithoutToken() {
        Endpoint team = endpoint("Team", "https://jira.example.org", null, true, true);
        when(active.active(ConnectorType.JIRA)).thenReturn(Optional.of(team));
        assertThatThrownBy(() -> resolver.resolveAccessToken("jira", "Jira", null))
                .hasMessageContaining("\"Team\"")
                .hasMessageContaining("app://settings/connectors/" + team.connectionId());
    }

    @Test
    @DisplayName("SVN takes user:password, split on the first colon, and refuses half a pair")
    void svnCredential() {
        when(active.active(any())).thenReturn(Optional.empty());
        when(endpoints.forSource(eq(ConnectorType.SVN), any(), any()))
                .thenReturn(endpoint("Repo", "https://svn.example/svn", "me:p:w", true, false));
        assertThat(resolver.resolveSvnCredential("SVN")).containsExactly("me", "p:w");
        assertThat(ToolConnectorResolver.splitSvnCredential("me:")).isNull();
        assertThat(ToolConnectorResolver.splitSvnCredential(":pw")).isNull();
    }

    @Test
    @DisplayName("a call's server and credential come from ONE resolution - a pick landing between cannot split them")
    void oneResolutionPerCall() {
        when(active.active(ConnectorType.JIRA))
                .thenReturn(Optional.of(endpoint("Vendor", "https://vendor.example.org/", "vendor-token", true, true)))
                .thenReturn(Optional.of(endpoint("Prod", "https://prod.example.org", "prod-token", true, true)));

        ToolConnectorResolver.Target target = resolver.target(ConnectorType.JIRA, "Jira", "PROJ", Access.WRITE);

        assertThat(target.baseUrl()).isEqualTo("https://vendor.example.org");
        assertThat(target.credential()).isEqualTo("vendor-token");
        org.mockito.Mockito.verify(active, org.mockito.Mockito.times(1)).active(ConnectorType.JIRA);
    }
}
