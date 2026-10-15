package ai.proxyma.connectors.trello;

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

import java.util.Map;

/**
 * The Trello service: an API key (from a Power-Up's admin page) and a user token generated for it,
 * both kept encrypted. Test is "who am I" - {@code GET /members/me}. Trello counts requests to
 * {@code /members} against a budget of its own (100 per 15 minutes), so nothing but Test and a
 * source's first look asks it.
 */
@Component
public class TrelloConnectionPlugin implements ConnectionPlugin {

    static final ProtocolDescriptor DESCRIPTOR = ProtocolDescriptor.builder(ConnectionProtocol.TRELLO)
            .displayName("Trello")
            .mark("trello")
            .auth(AuthMethod.of(AuthKind.TOKEN, "API key and token",
                    FieldSpec.secret(TrelloApi.API_KEY, "API key").asRequired()
                            .help("On your Power-Up's page in the Trello Power-Up admin portal, under API key."),
                    FieldSpec.secret(TrelloApi.TOKEN, "Token").asRequired()
                            .help("Generated from the Token link beside the API key, with read access.")))
            .baseUrl(FieldSpec.url("baseUrl", "API URL").defaultValue(TrelloApi.DEFAULT_BASE_URL).asAdvanced())
            .sourceTypes(ConnectorType.TRELLO)
            .docsAnchor("trello")
            .build();

    @Override
    public ProtocolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ConnectionTestResult test(ResolvedCredentials connection, ConnectorHttp http) {
        try {
            JsonNode me = TrelloApi.client(http, connection).getJson("members/me", Map.of("fields", "fullName,username"));
            String name = me.path("fullName").asText("");
            if (name.isBlank()) name = me.path("username").asText("");
            return ConnectionTestResult.ok("Signed in as {user}", "user", name);
        } catch (ConnectorHttpException e) {
            if (e.status() == 401) return ConnectionTestResult.failed("Trello did not accept the API key and token.");
            return ConnectionTestResult.failed(e.getMessage());
        } catch (ConnectorAuthException e) {
            return ConnectionTestResult.failed(e.getMessage());
        }
    }
}
