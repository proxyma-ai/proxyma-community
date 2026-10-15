package ai.proxyma.core.tools.bitbucket;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.spi.ConnectorCatalog;
import ai.proxyma.core.connector.spi.ConnectorDescriptor;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.ConnectorPlugin;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.Listing;
import ai.proxyma.core.connector.spi.SourceContext;
import ai.proxyma.core.registry.service.ConnectorEndpointResolver.Endpoint;
import ai.proxyma.core.registry.service.SourceContextFactory;
import ai.proxyma.core.settings.connection.ConnectorConnectionService;
import ai.proxyma.core.settings.connection.ResolvedConnection;
import ai.proxyma.core.tools.ToolConnectorResolver;
import ai.proxyma.core.tools.ToolConnectorResolver.Access;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Bitbucket Cloud read live: each call opens the connector's own session over the caller's
 * connection, checked for Read, and fetches one item - a pull request ({@code pr:N}) or a file
 * ({@code file:path}) at a branch. A stand-in plugin records what it was opened with.
 */
class BitbucketCloudToolsTest {

    private final List<SourceContext> opened = new ArrayList<>();
    private final List<String> fetched = new ArrayList<>();

    private BitbucketCloudTools tools(boolean readAllowed) {
        ToolConnectorResolver resolver = mock(ToolConnectorResolver.class);
        UUID id = UUID.randomUUID();
        ResolvedConnection connection = new ResolvedConnection(id, "local", "Bitbucket", ConnectionProtocol.BITBUCKET_CLOUD,
                "https://api.bitbucket.org/2.0", null, "tok", true, readAllowed, false, null, null, null);
        if (readAllowed) {
            when(resolver.resolve(eq(ConnectorType.BITBUCKET_CLOUD), any(), any(), eq(Access.READ)))
                    .thenReturn(Endpoint.of(connection));
        } else {
            when(resolver.resolve(eq(ConnectorType.BITBUCKET_CLOUD), any(), any(), eq(Access.READ)))
                    .thenThrow(new IllegalStateException("Bitbucket Cloud read is turned off for the connection \"Bitbucket\"."));
        }
        ConnectorConnectionService connections = mock(ConnectorConnectionService.class);
        when(connections.resolveOwned(any(), eq(id))).thenReturn(Optional.of(connection));
        ConnectorPlugin plugin = new ConnectorPlugin() {
            @Override public ConnectorDescriptor descriptor() {
                return ConnectorDescriptor.builder(ConnectorType.BITBUCKET_CLOUD)
                        .protocol(ConnectionProtocol.BITBUCKET_CLOUD).displayName("Bitbucket Cloud").build();
            }
            @Override public ConnectorSession open(SourceContext context) {
                opened.add(context);
                return new ConnectorSession() {
                    @Override public ai.proxyma.core.connector.AccessCheck verifyAccess() { return ai.proxyma.core.connector.AccessCheck.verified(); }
                    @Override public Listing list(ListRequest request) { throw new UnsupportedOperationException(); }
                    @Override public InputStream fetch(ItemMetadata item) {
                        fetched.add(item.getId());
                        return new ByteArrayInputStream(("# " + item.getId()).getBytes(StandardCharsets.UTF_8));
                    }
                    @Override public void close() { }
                };
            }
        };
        SourceContextFactory contexts = new SourceContextFactory(null, null, null, null);
        return new BitbucketCloudTools(resolver, connections, contexts, ConnectorCatalog.of(List.of(plugin), List.of()),
                ConnectorHttp.direct());
    }

    @Test
    @DisplayName("a pull request and a file at a branch are fetched through the connector's session, over the caller's connection")
    void readsThroughTheSession() {
        BitbucketCloudTools tools = tools(true);

        Map<String, Object> pr = tools.getBitbucketCloudPr("acme-team/field-guide", 2);
        assertThat(pr).containsEntry("success", true).containsEntry("content", "# pr:2");
        assertThat(opened.get(0).config()).containsEntry("repository", "acme-team/field-guide").doesNotContainKey("branch");
        assertThat(opened.get(0).connection().secret("token")).isEqualTo("tok");

        Map<String, Object> file = tools.readBitbucketCloudFile("acme-team/field-guide", "/docs/safety.md", "release/1.0");
        assertThat(file).containsEntry("content", "# file:docs/safety.md");
        assertThat(opened.get(1).config()).containsEntry("branch", "release/1.0");
        assertThat(fetched).containsExactly("pr:2", "file:docs/safety.md");
    }

    @Test
    @DisplayName("a malformed repository, path or branch, and a connection whose Read is off, open nothing")
    void refusals() {
        BitbucketCloudTools tools = tools(true);
        assertThat(tools.getBitbucketCloudPr("field-guide", 2)).containsEntry("success", false);
        assertThat(tools.getBitbucketCloudPr("acme/../x", 2)).containsEntry("success", false);
        assertThat(tools.getBitbucketCloudPr("acme/x", 0)).containsEntry("success", false);
        assertThat(tools.readBitbucketCloudFile("acme/x", "docs/", null)).containsEntry("success", false);
        assertThat(tools.readBitbucketCloudFile("acme/x", "a.md", "bad branch")).containsEntry("success", false);
        assertThat(opened).isEmpty();

        assertThat(tools(false).getBitbucketCloudPr("acme/x", 1)).containsEntry("success", false)
                .extractingByKey("error").asString().contains("read is turned off");
        assertThat(opened).isEmpty();
    }
}
