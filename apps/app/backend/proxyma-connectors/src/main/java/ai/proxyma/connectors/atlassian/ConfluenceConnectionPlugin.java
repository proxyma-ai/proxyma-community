package ai.proxyma.connectors.atlassian;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.spi.ConnectionPlugin;
import ai.proxyma.core.connector.spi.ConnectionTestResult;
import ai.proxyma.core.connector.spi.ConnectorAuthException;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.ConnectorHttpException;
import ai.proxyma.core.connector.spi.CoreDescriptors;
import ai.proxyma.core.connector.spi.ProtocolDescriptor;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * The Confluence service, Cloud or Data Center - its descriptor is the one every module reads from
 * CoreDescriptors. Test is "who am I": {@code /wiki/rest/api/user/current} on a Cloud site,
 * {@code /rest/api/user/current} on Data Center and Server.
 */
@Component
public class ConfluenceConnectionPlugin implements ConnectionPlugin {

    @Override
    public ProtocolDescriptor descriptor() {
        return CoreDescriptors.of(ConnectionProtocol.CONFLUENCE);
    }

    @Override
    public ConnectionTestResult test(ResolvedCredentials connection, ConnectorHttp http) {
        try {
            boolean cloud = AtlassianApi.isCloud(connection);
            String base = cloud ? ConfluenceSession.site(connection) : AtlassianApi.base(connection);
            JsonNode me = AtlassianApi.client(http, connection, "Confluence", base)
                    .getJson((cloud ? "wiki/" : "") + "rest/api/user/current");
            String name = me.path("displayName").asText("");
            if (name.isBlank()) name = me.path("username").asText("");
            // Cloud answers an unauthenticated call as the anonymous user rather than with a 401.
            if ("anonymous".equalsIgnoreCase(me.path("type").asText(""))) {
                return ConnectionTestResult.failed("Confluence did not accept the email address and API token.");
            }
            return name.isBlank() ? ConnectionTestResult.ok("Connected") : ConnectionTestResult.ok("Signed in as {user}", "user", name);
        } catch (ConnectorHttpException e) {
            if (e.status() == 401) {
                return ConnectionTestResult.failed(AtlassianApi.isCloud(connection)
                        ? "Confluence did not accept the email address and API token."
                        : "Confluence did not accept the token.");
            }
            if (e.status() == 403) return ConnectionTestResult.failed("Confluence refused this account (403).");
            return ConnectionTestResult.failed(AtlassianApi.testFailure(e));
        } catch (ConnectorAuthException e) {
            return ConnectionTestResult.failed(e.getMessage());
        }
    }
}
