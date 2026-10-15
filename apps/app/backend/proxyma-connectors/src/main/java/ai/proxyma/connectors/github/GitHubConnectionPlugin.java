package ai.proxyma.connectors.github;

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
 * The GitHub service: a personal access token against github.com or a GitHub Enterprise Server,
 * whose API address is the connection's base URL (under Advanced, defaulting to github.com's).
 * Test is "who am I" - {@code GET /user}.
 */
@Component
public class GitHubConnectionPlugin implements ConnectionPlugin {

    static final ProtocolDescriptor DESCRIPTOR = ProtocolDescriptor.builder(ConnectionProtocol.GITHUB)
            .displayName("GitHub")
            .mark("github")
            .auth(AuthMethod.of(AuthKind.TOKEN, "Personal access token",
                    FieldSpec.secret("token", "Personal access token").asRequired()
                            .help("A classic token with the repo scope, or a fine-grained one that can read "
                                    + "issues, pull requests and contents.")))
            .baseUrl(FieldSpec.url("baseUrl", "API URL").defaultValue(GitHubApi.DEFAULT_BASE_URL).asAdvanced()
                    .help("For GitHub Enterprise Server: https://your-host/api/v3"))
            .sourceTypes(ConnectorType.GITHUB)
            .docsAnchor("github")
            .build();

    @Override
    public ProtocolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ConnectionTestResult test(ResolvedCredentials connection, ConnectorHttp http) {
        try {
            JsonNode me = GitHubApi.client(http, connection).getJson("user");
            String login = me.path("login").asText("");
            return ConnectionTestResult.ok("Signed in as {user}", "user", login);
        } catch (ConnectorHttpException e) {
            if (e.status() == 401) return ConnectionTestResult.failed("GitHub did not accept the token.");
            return ConnectionTestResult.failed(e.getMessage());
        } catch (ConnectorAuthException e) {
            return ConnectionTestResult.failed(e.getMessage());
        }
    }
}
