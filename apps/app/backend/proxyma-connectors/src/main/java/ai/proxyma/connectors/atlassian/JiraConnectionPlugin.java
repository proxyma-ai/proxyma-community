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
 * The Jira service, Cloud or Data Center - its descriptor (two ways to sign in, the server address)
 * is the one every module reads from CoreDescriptors, so a catalog with no plugins describes it the
 * same way. Test is "who am I": {@code /rest/api/3/myself} on Cloud, {@code /rest/api/2/myself} on
 * Data Center and Server.
 */
@Component
public class JiraConnectionPlugin implements ConnectionPlugin {

    @Override
    public ProtocolDescriptor descriptor() {
        return CoreDescriptors.of(ConnectionProtocol.JIRA);
    }

    @Override
    public ConnectionTestResult test(ResolvedCredentials connection, ConnectorHttp http) {
        try {
            String base = AtlassianApi.productBase(connection);
            JsonNode me = AtlassianApi.client(http, connection, "Jira", base)
                    .getJson(JiraSession.apiPath(connection) + "/myself");
            // Cloud answers a credential it did not accept as nobody rather than always with a 401.
            if (AtlassianApi.isCloud(connection) && me.path("accountId").asText("").isBlank()) {
                return ConnectionTestResult.failed("Jira did not accept the email address and API token.");
            }
            String name = me.path("displayName").asText("");
            if (name.isBlank()) name = me.path("name").asText("");
            return name.isBlank() ? ConnectionTestResult.ok("Connected") : ConnectionTestResult.ok("Signed in as {user}", "user", name);
        } catch (ConnectorHttpException e) {
            if (e.status() == 401) {
                return ConnectionTestResult.failed(AtlassianApi.isCloud(connection)
                        ? "Jira did not accept the email address and API token."
                        : "Jira did not accept the token.");
            }
            if (e.status() == 403) return ConnectionTestResult.failed("Jira refused this account (403).");
            return ConnectionTestResult.failed(AtlassianApi.testFailure(e));
        } catch (ConnectorAuthException e) {
            return ConnectionTestResult.failed(e.getMessage());
        }
    }
}
