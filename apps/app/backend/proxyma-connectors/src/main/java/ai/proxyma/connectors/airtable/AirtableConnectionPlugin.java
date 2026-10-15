package ai.proxyma.connectors.airtable;

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
import ai.proxyma.core.connector.spi.OAuth2Spec;
import ai.proxyma.core.connector.spi.ProtocolDescriptor;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * The Airtable service. Two ways to sign in: a personal access token pasted (with the read scopes
 * below, and access to the bases to index), or "Sign in with Airtable" through an OAuth integration
 * the operator registered at airtable.com/create/oauth - PKCE always, the client as HTTP Basic, and
 * the bases picked on Airtable's own consent screen. Test is {@code GET /meta/whoami}.
 */
@Component
public class AirtableConnectionPlugin implements ConnectionPlugin {

    /** Read the records, read the bases' tables and fields, and - for Test - the account's email. */
    static final List<String> SCOPES = List.of("data.records:read", "schema.bases:read", "user.email:read");

    static final ProtocolDescriptor DESCRIPTOR = ProtocolDescriptor.builder(ConnectionProtocol.AIRTABLE)
            .displayName("Airtable")
            .mark("airtable")
            .auth(AuthMethod.of(AuthKind.TOKEN, "Personal access token",
                            FieldSpec.secret("token", "Personal access token").asRequired().placeholder("pat...")
                                    .help("Create one at airtable.com/create/tokens with the data.records:read and "
                                            + "schema.bases:read scopes, and add the bases to index.")),
                    AuthMethod.of(AuthKind.OAUTH2_CODE, "Sign in with Airtable",
                            FieldSpec.text("clientId", "Client ID").asRequired(),
                            FieldSpec.secret("clientSecret", "Client secret").asRequired()
                                    .help("From your OAuth integration at airtable.com/create/oauth. You choose the "
                                            + "bases to share when you sign in.")))
            .baseUrl(FieldSpec.url("baseUrl", "API URL").defaultValue(AirtableApi.DEFAULT_BASE_URL).asAdvanced()
                    .help("Leave as it is unless you reach Airtable through a relay."))
            .sourceTypes(ConnectorType.AIRTABLE)
            .docsAnchor("airtable")
            .build();

    static final OAuth2Spec OAUTH2 = OAuth2Spec.builder("https://airtable.com/oauth2/v1/authorize",
            "https://airtable.com/oauth2/v1/token").scopes(SCOPES).clientAuth(OAuth2Spec.ClientAuth.BASIC).build();

    @Override
    public ProtocolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public OAuth2Spec oauth2() {
        return OAUTH2;
    }

    @Override
    public ConnectionTestResult test(ResolvedCredentials connection, ConnectorHttp http) {
        try {
            JsonNode me = AirtableApi.client(http, connection).getJson("meta/whoami");
            String user = me.path("email").asText("");
            if (user.isBlank()) user = me.path("id").asText("");
            return ConnectionTestResult.ok("Signed in as {user}", "user", user);
        } catch (ConnectorHttpException e) {
            // The page's own wording, so it is said in the reader's language (connectionTestMessage.ts).
            if (e.status() == 401) return ConnectionTestResult.failed("{service} did not accept the token.", "service", "Airtable");
            return ConnectionTestResult.failed(e.getMessage());
        } catch (ConnectorAuthException e) {
            return ConnectionTestResult.failed(e.getMessage());
        }
    }
}
