package ai.proxyma.connectors.freshdesk;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.AuthKind;
import ai.proxyma.core.connector.spi.AuthMethod;
import ai.proxyma.core.connector.spi.ConnectionPlugin;
import ai.proxyma.core.connector.spi.ConnectionTestResult;
import ai.proxyma.core.connector.spi.ConnectorAuthException;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.ConnectorHttpException;
import ai.proxyma.core.connector.spi.FieldSpec;
import ai.proxyma.core.connector.spi.ProtocolDescriptor;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * The Freshdesk service, one account (one domain) per connection, reached with an agent's API key
 * (Profile settings > View API key in Freshdesk). Freshservice is another product with keys of its
 * own, and is not served here.
 *
 * <p>Test is "who am I" - {@code GET /agents/me} - and names the agent the key belongs to.
 */
@Component
public class FreshdeskConnectionPlugin implements ConnectionPlugin {

    static final ProtocolDescriptor DESCRIPTOR = ProtocolDescriptor.builder(ConnectionProtocol.FRESHDESK)
            .displayName("Freshdesk")
            .mark("freshdesk")
            .auth(AuthMethod.of(AuthKind.TOKEN, "API key",
                    FieldSpec.text(FreshdeskApi.DOMAIN, "Domain").asRequired().placeholder("yourcompany")
                            .pattern("[A-Za-z0-9][A-Za-z0-9-]*")
                            .help("The first part of your Freshdesk address - yourcompany in yourcompany.freshdesk.com."),
                    FieldSpec.secret(FreshdeskApi.TOKEN, "API key").asRequired()
                            .help("In Freshdesk, open your profile picture > Profile settings > View API key.")))
            .baseUrl(FieldSpec.url("baseUrl", "Freshdesk address").placeholder("https://yourcompany.freshdesk.com").asAdvanced()
                    .help("Only for an address other than your domain's."))
            .sourceTypes(ConnectorType.FRESHDESK)
            .docsAnchor("freshdesk")
            .build();

    @Override
    public ProtocolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ConnectionTestResult test(ResolvedCredentials connection, ConnectorHttp http) {
        try {
            JsonNode me = FreshdeskApi.client(http, connection).getJson("agents/me");
            String name = me.path("contact").path("name").asText("");
            if (name.isBlank()) name = me.path("contact").path("email").asText("");
            return ConnectionTestResult.ok("Signed in as {user}", "user", name);
        } catch (ConnectorHttpException e) {
            if (e.status() == 401) return ConnectionTestResult.failed("Freshdesk did not accept the API key.");
            if (e.status() == 404) {
                // The domain sentence only when the domain is what was used.
                if (connection.hasBaseUrl()) {
                    return ConnectionTestResult.failed("Freshdesk has nothing at {address}. Check the connection's address.",
                            "address", FreshdeskApi.accountUrl(connection));
                }
                return ConnectionTestResult.failed("Freshdesk has no account at that domain. Check the domain.");
            }
            return ConnectionTestResult.failed(e.getMessage());
        } catch (ConnectorAuthException e) {
            return ConnectionTestResult.failed(e.getMessage());
        }
    }
}
