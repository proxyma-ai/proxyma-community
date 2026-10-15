package ai.proxyma.core.settings.connection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How Proxyma reaches one MCP server - an "MCP server" connection's settings, apart from its
 * secrets. Stored as JSON in {@code connector_connections.mcp_config}; nothing in it is secret, so
 * it is shown back to the owner as it was saved.
 *
 * <p>THE SECRETS LIVE IN THE CONNECTION'S ENCRYPTED {@code secret}, as {@link McpSecrets}: a bearer
 * token, header VALUES, environment-variable VALUES, an OAuth client secret and refresh token. This
 * record carries only the NAMES of the headers and variables, so the form can list them without a
 * value ever leaving the server.
 *
 * @param transport how the server is reached
 * @param command   the program to start, for {@code STDIO}
 * @param args      its arguments, one per element
 * @param auth      how an HTTP server is authorized
 * @param headerNames the custom headers sent, for {@code HEADERS}
 * @param envNames  the environment variables the program is started with, for {@code STDIO}
 * @param oauth     the OAuth client and endpoints this connection signed in through, once it has
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record McpServerConfig(Transport transport, String command, List<String> args, Auth auth,
                              List<String> headerNames, List<String> envNames, OAuthClient oauth) {

    public enum Transport { STREAMABLE_HTTP, SSE, STDIO }

    public enum Auth { NONE, BEARER, HEADERS, OAUTH }

    /**
     * The authorization server this connection signs in with, found by discovery (or typed, where
     * the server offers no dynamic registration). Public values only; a client secret, if the
     * server issued one, is in {@link McpSecrets}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OAuthClient(String clientId, String authorizationEndpoint, String tokenEndpoint,
                              String resource, String scope, Boolean registered) {}

    public McpServerConfig {
        transport = transport == null ? Transport.STREAMABLE_HTTP : transport;
        auth = auth == null ? Auth.NONE : auth;
        args = args == null ? List.of() : List.copyOf(args);
        headerNames = headerNames == null ? List.of() : List.copyOf(headerNames);
        envNames = envNames == null ? List.of() : List.copyOf(envNames);
    }

    public boolean isStdio() {
        return transport == Transport.STDIO;
    }

    public McpServerConfig withOAuth(OAuthClient client) {
        return new McpServerConfig(transport, command, args, auth, headerNames, envNames, client);
    }

    // -- JSON ---------------------------------------------------------------------------------------

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** The stored form, or a default (streamable HTTP, no auth) for a row that has none. */
    public static McpServerConfig parse(String json) {
        if (json == null || json.isBlank()) return new McpServerConfig(null, null, null, null, null, null, null);
        try {
            return JSON.readValue(json, McpServerConfig.class);
        } catch (Exception e) {
            return new McpServerConfig(null, null, null, null, null, null, null);
        }
    }

    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("MCP server settings could not be written", e);
        }
    }

    /**
     * An MCP connection's secrets, encrypted together as one JSON value in the connection's
     * {@code secret}. A value left blank on an edit keeps the stored one - the client never has the
     * value to send back - so merging is by name: see {@link #merge}.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record McpSecrets(String bearerToken, Map<String, String> headers, Map<String, String> env,
                             String clientSecret, String refreshToken) {

        public McpSecrets {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            env = env == null ? Map.of() : Map.copyOf(env);
        }

        public static McpSecrets empty() {
            return new McpSecrets(null, null, null, null, null);
        }

        public static McpSecrets parse(String json) {
            if (json == null || json.isBlank()) return empty();
            try {
                return JSON.readValue(json, McpSecrets.class);
            } catch (Exception e) {
                return empty();
            }
        }

        public String toJson() {
            try {
                return JSON.writeValueAsString(this);
            } catch (Exception e) {
                throw new IllegalStateException("MCP server secrets could not be written", e);
            }
        }

        /**
         * The stored values updated by an edit. A name the edit sends with a value takes that
         * value; a name it sends blank keeps the stored value; a name it leaves out is gone, value
         * and all.
         */
        static Map<String, String> merge(Map<String, String> stored, Map<String, String> edited) {
            if (edited == null) return stored;
            Map<String, String> out = new LinkedHashMap<>();
            edited.forEach((name, value) -> {
                if (name == null || name.isBlank()) return;
                String key = name.trim();
                if (value != null && !value.isEmpty()) out.put(key, value);
                else if (stored.containsKey(key)) out.put(key, stored.get(key));
            });
            return out;
        }

        public McpSecrets withRefresh(String clientSecretValue, String refresh) {
            return new McpSecrets(bearerToken, headers, env,
                    clientSecretValue != null ? clientSecretValue : clientSecret, refresh);
        }
    }
}
