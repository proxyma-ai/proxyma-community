package ai.proxyma.connectors.atlassian;

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
 * The Bitbucket Cloud service ({@code api.bitbucket.org}) - a service of its own beside Bitbucket
 * Data Center and Server, whose API (REST 1.0, project and repository slug), agent tools and
 * connector are different throughout.
 *
 * <p>Two ways to sign in, since app passwords no longer work: the Atlassian account's email and an
 * API token with Bitbucket scopes, as Basic; or a workspace, project or repository access token, as
 * a bearer. Test is "who am I" ({@code GET /user}) - which an access token, belonging to no person,
 * is refused with a 403; its repository is checked when a source is added instead.
 */
@Component
public class BitbucketCloudConnectionPlugin implements ConnectionPlugin {

    static final String DEFAULT_BASE_URL = "https://api.bitbucket.org/2.0";

    static final ProtocolDescriptor DESCRIPTOR = ProtocolDescriptor.builder(ConnectionProtocol.BITBUCKET_CLOUD)
            .displayName("Bitbucket Cloud")
            .mark("bitbucket")
            .auth(AuthMethod.of(AuthKind.BASIC, "Email and API token",
                            FieldSpec.text(AuthMethod.USERNAME, "Email address").asRequired().placeholder("you@example.com")
                                    .help("The email address you sign in to Atlassian with."),
                            FieldSpec.secret("token", "API token").asRequired()
                                    .help("Create one at id.atlassian.com under Security > API tokens, with "
                                            + "Bitbucket scopes to read repositories and pull requests.")),
                    AuthMethod.of(AuthKind.TOKEN, "Access token",
                            FieldSpec.secret("token", "Access token").asRequired()
                                    .help("A workspace, project or repository access token with read access to "
                                            + "repositories and pull requests.")))
            .baseUrl(FieldSpec.url("baseUrl", "API URL").defaultValue(DEFAULT_BASE_URL).asAdvanced())
            .sourceTypes(ConnectorType.BITBUCKET_CLOUD)
            .docsAnchor("bitbucket-cloud")
            .build();

    @Override
    public ProtocolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ConnectionTestResult test(ResolvedCredentials connection, ConnectorHttp http) {
        try {
            JsonNode me = BitbucketCloudSession.client(http, connection).getJson("user");
            String name = me.path("display_name").asText(me.path("username").asText(""));
            return name.isBlank() ? ConnectionTestResult.ok("Connected") : ConnectionTestResult.ok("Signed in as {user}", "user", name);
        } catch (ConnectorHttpException e) {
            if (e.status() == 401) return ConnectionTestResult.failed("Bitbucket did not accept the credential.");
            if (e.status() == 403 && connection.authKind() == AuthKind.TOKEN) {
                return ConnectionTestResult.ok("The token was accepted. Its repository is checked when you add it.");
            }
            return ConnectionTestResult.failed(AtlassianApi.testFailure(e));
        } catch (ConnectorAuthException e) {
            return ConnectionTestResult.failed(e.getMessage());
        }
    }
}
