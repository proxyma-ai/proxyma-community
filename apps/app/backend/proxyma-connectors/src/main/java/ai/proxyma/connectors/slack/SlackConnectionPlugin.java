package ai.proxyma.connectors.slack;

import ai.proxyma.connectors.slack.SlackApi.SlackApiException;
import ai.proxyma.connectors.slack.SlackApi.Tier;
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
 * The Slack service, through the customer's OWN internal Slack app - the maintainer's decision: a
 * self-hosted Proxyma is a distributed app as far as Slack is concerned, and since 2025-05-29 a
 * distributed app outside the Marketplace may read one page of 15 messages a minute, while an app a
 * workspace builds for itself keeps the normal limits. proxyma.ai/docs/manual/#slack has the manifest.
 *
 * <p>Two ways to sign in: the app's bot token pasted (with, optionally, a user token for private
 * channels the person is in but the app is not), or "Sign in with Slack" through the app's own OAuth
 * client - which Slack only allows to return to an https:// address. Test is {@code auth.test}.
 */
@Component
public class SlackConnectionPlugin implements ConnectionPlugin {

    static final String USER_TOKEN = "userToken";

    /** The bot scopes indexing needs, and no other: read channels and their history, and people's names. */
    static final List<String> SCOPES = List.of("channels:history", "channels:read", "groups:history", "groups:read",
            "users:read");

    static final ProtocolDescriptor DESCRIPTOR = ProtocolDescriptor.builder(ConnectionProtocol.SLACK)
            .displayName("Slack")
            .mark("slack")
            .auth(AuthMethod.of(AuthKind.TOKEN, "Bot token",
                            FieldSpec.secret("token", "Bot token").asRequired().placeholder("xoxb-...")
                                    .help("From your own Slack app's OAuth & Permissions page, once it is installed "
                                            + "to your workspace."),
                            FieldSpec.secret(USER_TOKEN, "User token").placeholder("xoxp-...")
                                    .help("Optional. Lets private channels you are in be indexed without inviting "
                                            + "the app to them.")),
                    AuthMethod.of(AuthKind.OAUTH2_CODE, "Sign in with Slack",
                            FieldSpec.text("clientId", "Client ID").asRequired(),
                            FieldSpec.secret("clientSecret", "Client secret").asRequired()
                                    .help("From your own Slack app's Basic Information page. Slack returns only to "
                                            + "an https:// address, so paste the bot token where Proxyma is not "
                                            + "served over https.")))
            .baseUrl(FieldSpec.url("baseUrl", "API URL").defaultValue(SlackApi.DEFAULT_BASE_URL).asAdvanced()
                    .help("Leave as it is unless you reach Slack through a relay."))
            .sourceTypes(ConnectorType.SLACK)
            .docsAnchor("slack")
            .build();

    // Slack takes its scopes comma-separated, so they go as the authorize URL's own scope parameter
    // rather than through OAuth2Spec.scopes, which the framework joins with spaces. Slack's token
    // endpoint takes the client in the form body.
    static final OAuth2Spec OAUTH2 = OAuth2Spec.builder("https://slack.com/oauth/v2/authorize",
            "https://slack.com/api/oauth.v2.access").authorizeParam("scope", String.join(",", SCOPES)).build();

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
            JsonNode me = SlackApi.bot(http, connection).call(Tier.TIER_4, "auth.test", Map.of());
            SlackApi user = SlackApi.user(http, connection);
            if (user != null) {
                try {
                    user.call(Tier.TIER_4, "auth.test", Map.of());
                } catch (SlackApiException e) {
                    if (e.status() != 401) throw e;
                    return ConnectionTestResult.failed("{service} did not accept the user token.", "service", "Slack");
                }
            }
            return ConnectionTestResult.ok("Signed in to {workspace} as {user}",
                    "workspace", me.path("team").asText(""), "user", me.path("user").asText(""));
        } catch (ConnectorHttpException e) {
            // The page's own wording, so it is said in the reader's language (connectionTestMessage.ts).
            if (e.status() == 401) return ConnectionTestResult.failed("{service} did not accept the token.", "service", "Slack");
            return ConnectionTestResult.failed(e.getMessage());
        } catch (ConnectorAuthException e) {
            return ConnectionTestResult.failed(e.getMessage());
        }
    }
}
