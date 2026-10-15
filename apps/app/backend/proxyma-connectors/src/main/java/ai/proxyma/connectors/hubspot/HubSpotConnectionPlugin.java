package ai.proxyma.connectors.hubspot;

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
 * The HubSpot service, one account (portal) per connection. Two ways in:
 * <ul>
 *   <li>a token: a <b>Service Key</b> (Settings > Integrations > Service Keys), which is what HubSpot
 *       issues for an in-account integration since it stopped letting accounts create legacy private
 *       apps (2026-09-28 for accounts created from that day, 2026-10-26 for older ones) - or an
 *       existing legacy private app's access token, which keeps working. Both are bearers;</li>
 *   <li>a sign-in through a HubSpot app the operator registered in a developer account (the
 *       person's own on the single-user editions, the deployment's on Enterprise Server). HubSpot's
 *       access tokens last half an hour; the framework refreshes them.</li>
 * </ul>
 * Either way the credential reads the WHOLE account at its scopes - which is why a HubSpot source is
 * never shareable. Test is the account's details: "Signed in to portal 1234567".
 */
@Component
public class HubSpotConnectionPlugin implements ConnectionPlugin {

    static final String AUTHORIZE_URL = "https://app.hubspot.com/oauth/authorize";
    static final String TOKEN_URL = "https://api.hubapi.com/oauth/v1/token";
    // What the connector reads: the four object types (engagements are read under contacts' scope)
    // and, optionally, the owners' names.
    static final List<String> SCOPES = List.of("oauth", "crm.objects.companies.read", "crm.objects.contacts.read",
            "crm.objects.deals.read", "tickets");
    static final String OPTIONAL_SCOPES = "crm.objects.owners.read";

    static final ProtocolDescriptor DESCRIPTOR = ProtocolDescriptor.builder(ConnectionProtocol.HUBSPOT)
            .displayName("HubSpot")
            .mark("hubspot")
            .auth(AuthMethod.of(AuthKind.TOKEN, "Service Key or private app token",
                            FieldSpec.secret("token", "Service Key").asRequired()
                                    .help("Create one in HubSpot under Settings > Integrations > Service Keys, with "
                                            + "read scopes for the records to index. A legacy private app's access "
                                            + "token works too.")),
                    AuthMethod.of(AuthKind.OAUTH2_CODE, "Sign in with HubSpot",
                            FieldSpec.text("clientId", "Client ID")
                                    .help("A HubSpot app created in a HubSpot developer account, with the CRM read scopes."),
                            FieldSpec.secret("clientSecret", "Client secret")))
            .baseUrl(FieldSpec.url("baseUrl", "API URL").defaultValue(HubSpotApi.DEFAULT_BASE_URL).asAdvanced())
            .sourceTypes(ConnectorType.HUBSPOT)
            .docsAnchor("hubspot")
            .build();

    static final OAuth2Spec OAUTH2 = OAuth2Spec.builder(AUTHORIZE_URL, TOKEN_URL).scopes(SCOPES)
            .authorizeParam("optional_scope", OPTIONAL_SCOPES).build();

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
            JsonNode account = HubSpotApi.client(http, connection).getJson(HubSpotApi.accountDetails(HubSpotApi.DEFAULT_VERSION));
            String portal = account.path("portalId").asText("");
            return ConnectionTestResult.ok("Signed in to portal {portal}", "portal", portal);
        } catch (ConnectorHttpException e) {
            if (e.status() == 401) return ConnectionTestResult.failed("{service} did not accept the token.", "service", "HubSpot");
            return ConnectionTestResult.failed(e.getMessage());
        } catch (ConnectorAuthException e) {
            return ConnectionTestResult.failed(e.getMessage());
        }
    }
}
