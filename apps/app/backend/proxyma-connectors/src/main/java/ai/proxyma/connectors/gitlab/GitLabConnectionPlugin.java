package ai.proxyma.connectors.gitlab;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ApiResponse;
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
import ai.proxyma.core.connector.spi.ServiceAddress;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The GitLab service: gitlab.com or a self-managed instance, whose address is the connection's base
 * URL. Two ways in:
 * <ul>
 *   <li>an access token - personal, group or project - with the {@code read_api} scope, sent in
 *       GitLab's {@code PRIVATE-TOKEN} header. Works on any instance;</li>
 *   <li>a sign-in through an OAuth application registered on gitlab.com (the person's own on the
 *       single-user editions, the deployment's on Enterprise Server), asking for {@code read_api}.
 *       GitLab's access tokens last two hours and its refresh tokens rotate; the framework's
 *       refresh keeps both. The sign-in goes to gitlab.com: an OAuth2Spec names fixed addresses, so
 *       a self-managed instance is reached with a token.</li>
 * </ul>
 * Test is "who am I" - {@code GET /user}.
 */
@Component
public class GitLabConnectionPlugin implements ConnectionPlugin {

    static final String AUTHORIZE_URL = "https://gitlab.com/oauth/authorize";
    static final String TOKEN_URL = "https://gitlab.com/oauth/token";
    static final String READ_SCOPE = "read_api";
    // How many shorter paths the Test asks before giving up on finding the instance.
    static final int MAX_PROBES = 6;

    static final ProtocolDescriptor DESCRIPTOR = ProtocolDescriptor.builder(ConnectionProtocol.GITLAB)
            .displayName("GitLab")
            .mark("gitlab")
            .auth(AuthMethod.of(AuthKind.TOKEN, "Access token",
                            FieldSpec.secret("token", "Access token").asRequired()
                                    .help("A personal, group or project access token with the read_api scope.")),
                    AuthMethod.of(AuthKind.OAUTH2_CODE, "Sign in with GitLab",
                            FieldSpec.text("clientId", "Application ID")
                                    .help("An application registered on gitlab.com with the read_api scope. "
                                            + "For a self-managed GitLab, use an access token."),
                            FieldSpec.secret("clientSecret", "Secret")))
            .baseUrl(FieldSpec.url("baseUrl", "GitLab URL").defaultValue(GitLabApi.DEFAULT_BASE_URL)
                    .help("For a self-managed GitLab: https://your-host"))
            .sourceTypes(ConnectorType.GITLAB)
            .docsAnchor("gitlab")
            .build();

    static final OAuth2Spec OAUTH2 = OAuth2Spec.of(AUTHORIZE_URL, TOKEN_URL, List.of(READ_SCOPE));

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
            ApiClient api = GitLabApi.client(http, connection);
            ApiResponse answer = api.get("user");
            if (answer.status() == 404 || answer.ok() && answer.isWebPage()) {
                // Nothing at the address as typed - often a project or group page, whose path cannot
                // be told from an instance's own subpath by its text. Shorter paths of it are asked;
                // the stored address is not changed behind the person's back.
                String found = instanceAbove(connection, http);
                if (found != null) {
                    return ConnectionTestResult.failed("GitLab answers at {address}. Use that address as the GitLab URL.",
                            "address", found);
                }
            }
            JsonNode me = api.requireOk(answer).json();
            String username = me.path("username").asText("");
            return ConnectionTestResult.ok("Signed in as {user}", "user", username);
        } catch (ConnectorHttpException e) {
            if (e.status() == 401) return ConnectionTestResult.failed("GitLab did not accept the token.");
            return ConnectionTestResult.failed(e.getMessage());
        } catch (ConnectorAuthException e) {
            return ConnectionTestResult.failed(e.getMessage());
        }
    }

    /**
     * The nearest shorter path of the connection's address that GitLab's API answers at, down to the
     * host's root, or null - "answers" being JSON, a refused token included, since that too is GitLab
     * speaking. At most {@link #MAX_PROBES} are asked, all on the connection's own host.
     */
    static String instanceAbove(ResolvedCredentials connection, ConnectorHttp http) {
        String typed = GitLabApi.webBase(connection.hasBaseUrl() ? connection.baseUrl() : GitLabApi.DEFAULT_BASE_URL);
        int segments = ServiceAddress.segments(typed).size();
        for (int keep = segments - 1, asked = 0; keep >= 0 && asked < MAX_PROBES; keep--, asked++) {
            String candidate = ServiceAddress.prefix(typed, keep);
            try {
                if (speaksGitLab(GitLabApi.client(http, connection, candidate).get("user"))) return candidate;
            } catch (ConnectorHttpException | ConnectorAuthException e) {
                return null; // the host itself failed; a shorter path of it will not do better
            }
        }
        return null;
    }

    private static boolean speaksGitLab(ApiResponse answer) {
        if (!(answer.ok() || answer.status() == 401 || answer.status() == 403) || answer.isWebPage()) return false;
        try {
            return answer.json().isObject();
        } catch (ConnectorHttpException e) {
            return false;
        }
    }
}
