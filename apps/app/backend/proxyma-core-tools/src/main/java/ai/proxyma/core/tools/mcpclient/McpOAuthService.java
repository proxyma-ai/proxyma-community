package ai.proxyma.core.tools.mcpclient;

import ai.proxyma.core.common.net.OutboundAddressPolicy;
import ai.proxyma.core.settings.connection.ConnectionStatus;
import ai.proxyma.core.settings.connection.ConnectorConnectionService;
import ai.proxyma.core.settings.connection.McpServerConfig;
import ai.proxyma.core.settings.connection.ResolvedMcpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Signs an MCP server connection in with OAuth, as the MCP authorization specification lays it out,
 * and keeps its access token fresh.
 *
 * <p>THE FLOW, each step a published standard:
 * <ol>
 *   <li><b>Discovery.</b> The server answers an unauthorized request with 401 and a
 *       {@code WWW-Authenticate} header naming its protected-resource metadata (RFC 9728); without
 *       one, the metadata is looked for at the server's well-known address. That names the
 *       authorization server, whose own metadata (RFC 8414, or OpenID discovery) gives the
 *       authorize, token and registration endpoints. A server with neither falls back to the
 *       2025-03-26 defaults on its own origin.</li>
 *   <li><b>A client.</b> Registered dynamically (RFC 7591) when the server offers registration -
 *       afresh at every sign-in, since a registration is bound to the redirect address and that
 *       follows the address Proxyma is reached at. A server without registration needs a client id
 *       typed on the connection.</li>
 *   <li><b>The sign-in</b> in the person's own browser: authorization code with PKCE (S256), the
 *       {@code resource} parameter (RFC 8707) naming the MCP server, and a single-use state that
 *       expires in ten minutes.</li>
 *   <li><b>The callback</b> exchanges the code. The refresh token is stored encrypted on the
 *       connection; the access token is kept in memory only and refreshed when it expires or a
 *       request is refused.</li>
 * </ol>
 *
 * <p>EVERY ADDRESS DISCOVERY TURNS UP IS CHECKED against the outbound policy before it is
 * requested. A server's metadata is the server's own claim: on the public sandbox it could
 * otherwise send Proxyma to a private address.
 *
 * <p>THE CALLBACK IS AUTHENTICATED BY THE STATE ALONE, not a session or cookie: on Desktop the
 * sign-in happens in the system browser, which shares no cookie with the app window. The state is
 * 256 random bits handed only to the person who started the sign-in, used once, bound to their
 * connection, and the code it carries is useless without the PKCE verifier that never left here.
 */
@Service
public class McpOAuthService {

    private static final Logger log = LoggerFactory.getLogger(McpOAuthService.class);

    public static final String CALLBACK_PATH = "/api/v1/mcp-oauth/callback";
    static final Duration FLOW_TTL = Duration.ofMinutes(10);
    static final int MAX_PENDING = 50;
    /** Sign-ins one person may have in progress at once, so nobody can use up {@link #MAX_PENDING}. */
    static final int MAX_PENDING_PER_OWNER = 5;
    /** The most of a metadata document read from an address the server chose. */
    static final int MAX_METADATA_BYTES = 1024 * 1024;
    /** An access token this close to expiry is refreshed before use. */
    static final Duration EARLY = Duration.ofSeconds(60);

    private static final Pattern RESOURCE_METADATA = Pattern.compile("resource_metadata=\"([^\"]+)\"");
    private static final Pattern SCOPE = Pattern.compile("scope=\"([^\"]+)\"");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ConnectorConnectionService connections;
    private final RestClient http;

    /** A sign-in in progress, by its state. */
    record Flow(UUID connectionId, String ownerId, String verifier, String redirectUri, String tokenEndpoint,
                String clientId, String clientSecret, String resource, String mcpUrl, Instant createdAt) {}

    // Kept with the URL it was issued for: a connection edited to point elsewhere must never send
    // the old server's token to the new one, even for the minutes before the edit is noticed.
    record Token(String value, Instant expiresAt, String url) {}

    private final Map<String, Flow> flows = new ConcurrentHashMap<>();
    private final Map<UUID, Token> tokens = new ConcurrentHashMap<>();
    // One refresh at a time PER CONNECTION. It was one lock for all of them, held across the token
    // endpoint's request: one slow server stalled every connection's refresh, everyone's.
    private final Map<UUID, Object> refreshLocks = new ConcurrentHashMap<>();

    public McpOAuthService(ConnectorConnectionService connections,
                           @Qualifier("externalRestClientBuilder") RestClient.Builder http) {
        this.connections = connections;
        this.http = http.clone().build();
    }

    /** What a sign-in needs from its endpoints - discovered, or typed. */
    record Endpoints(String authorize, String token, String registration, String resource, List<String> scopes) {}

    public static class McpSignInException extends RuntimeException {
        public McpSignInException(String message) {
            super(message);
        }
    }

    /**
     * Starts a sign-in for the caller's own MCP server and returns the address to open.
     *
     * @param redirectUri where the server sends the browser back: this installation's callback
     */
    public String start(String userId, UUID connectionId, String redirectUri) {
        ResolvedMcpServer server = connections.resolveOwnedMcp(userId, connectionId)
                .orElseThrow(() -> new McpSignInException("No such MCP server connection."));
        if (server.config().auth() != McpServerConfig.Auth.OAUTH || server.config().isStdio()) {
            throw new McpSignInException("This MCP server connection does not sign in with OAuth.");
        }
        Endpoints endpoints = discover(server.url(), server.config().oauth());
        McpServerConfig.OAuthClient prior = server.config().oauth();
        boolean typed = prior != null && prior.clientId() != null && !Boolean.TRUE.equals(prior.registered());
        String clientId;
        String clientSecret;
        if (typed) {
            clientId = prior.clientId();
            clientSecret = server.secrets().clientSecret();
        } else if (endpoints.registration() != null) {
            Map<String, String> registered = register(endpoints.registration(), redirectUri);
            clientId = registered.get("client_id");
            clientSecret = registered.get("client_secret");
        } else {
            throw new McpSignInException("This MCP server does not register clients itself. "
                    + "Enter the client ID its administrator gave you on the connection, then sign in.");
        }
        String scope = prior != null && prior.scope() != null ? prior.scope()
                : endpoints.scopes().isEmpty() ? null : String.join(" ", endpoints.scopes());
        connections.recordMcpOAuthClient(connectionId, new McpServerConfig.OAuthClient(clientId,
                endpoints.authorize(), endpoints.token(), endpoints.resource(), scope, !typed), typed ? null : clientSecret);

        purgeExpired();
        if (flows.size() >= MAX_PENDING) throw new McpSignInException("Too many sign-ins are in progress. Try again in a few minutes.");
        long mine = flows.values().stream().filter(f -> java.util.Objects.equals(f.ownerId(), server.ownerId())).count();
        if (mine >= MAX_PENDING_PER_OWNER) {
            throw new McpSignInException("You have several sign-ins in progress already. Finish one, or try again in a few minutes.");
        }
        String state = random(32);
        String verifier = random(48);
        flows.put(state, new Flow(connectionId, server.ownerId(), verifier, redirectUri, endpoints.token(),
                clientId, clientSecret, endpoints.resource(), server.url(), Instant.now()));

        Map<String, String> q = new LinkedHashMap<>();
        q.put("response_type", "code");
        q.put("client_id", clientId);
        q.put("redirect_uri", redirectUri);
        q.put("state", state);
        q.put("code_challenge", challenge(verifier));
        q.put("code_challenge_method", "S256");
        if (scope != null) q.put("scope", scope);
        if (endpoints.resource() != null) q.put("resource", endpoints.resource());
        return endpoints.authorize() + (endpoints.authorize().contains("?") ? "&" : "?") + query(q);
    }

    /** The outcome of a callback, for the page the browser is shown. Never carries a message from the request. */
    public enum Outcome { SIGNED_IN, DENIED, EXPIRED, FAILED }

    /** Completes a sign-in from the authorization server's redirect. Never throws. */
    public Outcome complete(String state, String code, String error) {
        Flow flow = state == null ? null : flows.remove(state);
        if (flow == null) return Outcome.EXPIRED;
        if (Instant.now().isAfter(flow.createdAt().plus(FLOW_TTL))) return Outcome.EXPIRED;
        if (error != null && !error.isBlank()) return Outcome.DENIED;
        if (code == null || code.isBlank()) return Outcome.FAILED;
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "authorization_code");
            form.add("code", code);
            form.add("redirect_uri", flow.redirectUri());
            form.add("client_id", flow.clientId());
            form.add("code_verifier", flow.verifier());
            if (flow.resource() != null) form.add("resource", flow.resource());
            JsonNode body = tokenRequest(flow.tokenEndpoint(), form, flow.clientId(), flow.clientSecret());
            store(flow.connectionId(), flow.mcpUrl(), body, true);
            return Outcome.SIGNED_IN;
        } catch (RuntimeException e) {
            log.warn("[MCP] sign-in to connection {} could not be completed: {}", flow.connectionId(), e.getMessage());
            return Outcome.FAILED;
        }
    }

    /**
     * A current access token for the connection, refreshed if it is about to expire or the server
     * just refused it ({@code refused}). Throws a readable sentence when there is no grant, or the
     * grant was refused - and marks the connection so the page says to sign in again.
     */
    public String accessToken(UUID connectionId, String url, boolean refused) {
        Token cached = tokens.get(connectionId);
        if (usable(cached, url, refused)) return cached.value();
        synchronized (refreshLocks.computeIfAbsent(connectionId, k -> new Object())) {
            cached = tokens.get(connectionId);
            if (usable(cached, url, refused)) return cached.value();
            ResolvedMcpServer server = connections.resolveMcp(connectionId)
                    .orElseThrow(() -> new McpSignInException("No such MCP server connection."));
            McpServerConfig.OAuthClient client = server.config().oauth();
            String refresh = server.secrets().refreshToken();
            if (client == null || client.tokenEndpoint() == null || refresh == null) {
                // A grant with no refresh token ends with its access token (or with a restart): the row
                // stops saying signed in, so the connection list offers Sign in again.
                if (server.status() == ConnectionStatus.CONNECTED) {
                    connections.markNeedsReconnect(connectionId, "The sign-in has ended. Sign in again.");
                }
                throw new McpSignInException("The MCP server '" + server.name()
                        + "' needs you to sign in. Use Sign in on it in Settings > Connections.");
            }
            try {
                MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
                form.add("grant_type", "refresh_token");
                form.add("refresh_token", refresh);
                form.add("client_id", client.clientId());
                if (client.resource() != null) form.add("resource", client.resource());
                JsonNode body = tokenRequest(client.tokenEndpoint(), form, client.clientId(), server.secrets().clientSecret());
                return store(connectionId, server.url(), body, false);
            } catch (RestClientResponseException e) {
                tokens.remove(connectionId);
                if (e.getStatusCode().is4xxClientError()) {
                    connections.markNeedsReconnect(connectionId, "The server refused the sign-in. Sign in again.");
                }
                throw new McpSignInException("The MCP server '" + server.name()
                        + "' needs you to sign in again. Use Sign in on it in Settings > Connections.");
            }
        }
    }

    private static boolean usable(Token t, String url, boolean refused) {
        return !refused && t != null && java.util.Objects.equals(t.url(), url)
                && Instant.now().plus(EARLY).isBefore(t.expiresAt());
    }

    /** Forgets a connection's access token - after an edit, a sign-out, or a delete. */
    public void forget(UUID connectionId) {
        tokens.remove(connectionId);
        refreshLocks.remove(connectionId);
    }

    // -- discovery ------------------------------------------------------------------------------------

    Endpoints discover(String mcpUrl, McpServerConfig.OAuthClient prior) {
        URI mcp = URI.create(mcpUrl);
        String origin = mcp.getScheme() + "://" + mcp.getRawAuthority();

        // 1. The protected-resource metadata: named by the 401, or at its well-known address.
        String metadataUrl = null;
        List<String> scopes = new ArrayList<>();
        try {
            checked(mcpUrl);
            http.post().uri(mcpUrl).contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                    .body("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"ping\"}")
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            String header = e.getResponseHeaders() == null ? null
                    : e.getResponseHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE);
            if (header != null) {
                Matcher m = RESOURCE_METADATA.matcher(header);
                if (m.find()) metadataUrl = m.group(1);
                Matcher s = SCOPE.matcher(header);
                if (s.find()) scopes.addAll(List.of(s.group(1).trim().split("\\s+")));
            }
        } catch (RuntimeException e) {
            log.debug("[MCP] discovery probe of {} failed: {}", mcpUrl, e.getMessage());
        }
        List<String> prmCandidates = new ArrayList<>();
        if (metadataUrl != null) prmCandidates.add(metadataUrl);
        String path = mcp.getRawPath() == null || mcp.getRawPath().equals("/") ? "" : mcp.getRawPath();
        if (!path.isEmpty()) prmCandidates.add(origin + "/.well-known/oauth-protected-resource" + path);
        prmCandidates.add(origin + "/.well-known/oauth-protected-resource");

        String authServer = null;
        String resource = mcpUrl;
        for (String candidate : prmCandidates) {
            Optional<JsonNode> prm = getJson(candidate);
            if (prm.isEmpty()) continue;
            JsonNode servers = prm.get().path("authorization_servers");
            if (servers.isArray() && !servers.isEmpty()) authServer = servers.get(0).asText();
            if (prm.get().hasNonNull("resource")) resource = prm.get().get("resource").asText();
            if (scopes.isEmpty() && prm.get().path("scopes_supported").isArray()) {
                prm.get().path("scopes_supported").forEach(n -> scopes.add(n.asText()));
            }
            break;
        }
        if (authServer == null) authServer = origin;

        // 2. The authorization server's metadata (RFC 8414 with the path inserted, then OpenID).
        URI as = URI.create(authServer);
        String asOrigin = as.getScheme() + "://" + as.getRawAuthority();
        String asPath = as.getRawPath() == null || as.getRawPath().equals("/") ? "" : as.getRawPath();
        List<String> asCandidates = new ArrayList<>();
        asCandidates.add(asOrigin + "/.well-known/oauth-authorization-server" + asPath);
        if (!asPath.isEmpty()) asCandidates.add(asOrigin + "/.well-known/openid-configuration" + asPath);
        asCandidates.add(authServer.replaceAll("/+$", "") + "/.well-known/openid-configuration");
        for (String candidate : asCandidates) {
            Optional<JsonNode> meta = getJson(candidate);
            if (meta.isEmpty() || !meta.get().hasNonNull("authorization_endpoint")) continue;
            JsonNode m = meta.get();
            List<String> asScopes = new ArrayList<>(scopes);
            if (asScopes.isEmpty() && m.path("scopes_supported").isArray()) {
                // Only the ones an MCP client plausibly needs: offline access for a refresh token.
                m.path("scopes_supported").forEach(n -> { if ("offline_access".equals(n.asText())) asScopes.add("offline_access"); });
            }
            return new Endpoints(checked(m.get("authorization_endpoint").asText()), checked(m.path("token_endpoint").asText()),
                    m.hasNonNull("registration_endpoint") ? checked(m.get("registration_endpoint").asText()) : null,
                    resource, asScopes);
        }
        // 3. The 2025-03-26 defaults, on the authorization server's own origin.
        log.info("[MCP] no authorization-server metadata for {}; using the default endpoints", authServer);
        return new Endpoints(checked(asOrigin + "/authorize"), checked(asOrigin + "/token"),
                checked(asOrigin + "/register"), resource, scopes);
    }

    private Map<String, String> register(String endpoint, String redirectUri) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("client_name", "Proxyma");
        request.put("redirect_uris", List.of(redirectUri));
        request.put("grant_types", List.of("authorization_code", "refresh_token"));
        request.put("response_types", List.of("code"));
        request.put("token_endpoint_auth_method", "none");
        try {
            String body = http.post().uri(checked(endpoint)).contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON).body(request).retrieve().body(String.class);
            JsonNode json = JSON.readTree(body == null ? "{}" : body);
            if (!json.hasNonNull("client_id")) throw new McpSignInException("The MCP server did not register Proxyma as a client.");
            Map<String, String> out = new LinkedHashMap<>();
            out.put("client_id", json.get("client_id").asText());
            if (json.hasNonNull("client_secret")) out.put("client_secret", json.get("client_secret").asText());
            return out;
        } catch (RestClientResponseException e) {
            throw new McpSignInException("The MCP server refused to register Proxyma as a client (HTTP "
                    + e.getStatusCode().value() + ").");
        } catch (McpSignInException e) {
            throw e;
        } catch (Exception e) {
            throw new McpSignInException("The MCP server's client registration could not be read.");
        }
    }

    private JsonNode tokenRequest(String endpoint, MultiValueMap<String, String> form, String clientId, String clientSecret) {
        RestClient.RequestBodySpec spec = http.post().uri(checked(endpoint))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).accept(MediaType.APPLICATION_JSON);
        if (clientSecret != null && !clientSecret.isBlank()) spec = spec.headers(h -> h.setBasicAuth(
                URLEncoder.encode(clientId, StandardCharsets.UTF_8), URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)));
        String body = spec.body(form).retrieve().body(String.class);
        try {
            JsonNode json = JSON.readTree(body == null ? "{}" : body);
            if (!json.hasNonNull("access_token")) throw new McpSignInException("The token response carried no access token.");
            return json;
        } catch (McpSignInException e) {
            throw e;
        } catch (Exception e) {
            throw new McpSignInException("The token response could not be read.");
        }
    }

    // Keeps the access token in memory and the refresh token (when one came) on the connection.
    private String store(UUID connectionId, String url, JsonNode body, boolean signIn) {
        String access = body.get("access_token").asText();
        long seconds = body.path("expires_in").asLong(3600);
        tokens.put(connectionId, new Token(access, Instant.now().plusSeconds(Math.max(60, seconds)), url));
        String refresh = body.hasNonNull("refresh_token") ? body.get("refresh_token").asText() : null;
        connections.recordMcpGrant(connectionId, refresh, Instant.now(), signIn);
        return access;
    }

    // Read up to MAX_METADATA_BYTES and no further: the address is the server's choice, and a
    // metadata document is a few kilobytes.
    private Optional<JsonNode> getJson(String url) {
        try {
            byte[] body = http.get().uri(checked(url)).accept(MediaType.APPLICATION_JSON)
                    .exchange((req, res) -> {
                        if (!res.getStatusCode().is2xxSuccessful()) return null;
                        try (java.io.InputStream in = res.getBody()) {
                            byte[] read = in.readNBytes(MAX_METADATA_BYTES + 1);
                            return read.length > MAX_METADATA_BYTES ? null : read;
                        }
                    });
            return body == null ? Optional.empty() : Optional.of(JSON.readTree(body));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // http(s) only, and allowed by the outbound policy - see the class note.
    private static String checked(String url) {
        if (url == null || url.isBlank()) throw new McpSignInException("The MCP server's sign-in metadata is incomplete.");
        URI uri = URI.create(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new McpSignInException("The MCP server's sign-in uses an address that is not http or https.");
        }
        try {
            OutboundAddressPolicy.check(url);
        } catch (OutboundAddressPolicy.BlockedDestinationException e) {
            throw new McpSignInException(e.getMessage());
        }
        return url;
    }

    private void purgeExpired() {
        Instant cutoff = Instant.now().minus(FLOW_TTL);
        flows.entrySet().removeIf(e -> e.getValue().createdAt().isBefore(cutoff));
    }

    static String random(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String challenge(String verifier) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String query(Map<String, String> q) {
        StringBuilder sb = new StringBuilder();
        q.forEach((k, v) -> {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return sb.toString();
    }
}
