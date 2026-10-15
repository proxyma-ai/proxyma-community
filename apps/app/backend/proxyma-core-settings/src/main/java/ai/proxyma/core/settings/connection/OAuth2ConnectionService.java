package ai.proxyma.core.settings.connection;

import ai.proxyma.core.common.audit.AuditCategory;
import ai.proxyma.core.common.audit.AuditEntry;
import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.spi.ApiClient;
import ai.proxyma.core.connector.spi.ApiResponse;
import ai.proxyma.core.connector.spi.AuthKind;
import ai.proxyma.core.connector.spi.ConfigJson;
import ai.proxyma.core.connector.spi.ConnectionPlugin;
import ai.proxyma.core.connector.spi.ConnectionTokenProvider;
import ai.proxyma.core.connector.spi.ConnectorAuthException;
import ai.proxyma.core.connector.spi.ConnectorCatalog;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.ConnectorHttpException;
import ai.proxyma.core.connector.spi.OAuth2Spec;
import ai.proxyma.core.connector.spi.TokenSupplier;
import ai.proxyma.core.settings.service.CredentialEncryptionService;
import ai.proxyma.core.settings.service.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * OAuth2 for every connection whose service signs in that way - the generic flow a new service gets
 * by declaring an {@link OAuth2Spec} on its ConnectionPlugin. Microsoft 365 keeps its own flow
 * (MicrosoftAccountService, with its tenant checks) and plugs into the same token interface.
 *
 * <p><b>Authorization code with PKCE (S256).</b> {@link #start} creates a single-use flow keyed by a
 * random {@code state}, bound to the browser that started it by a cookie, and sends the browser to
 * the service; {@link #complete} accepts the callback only with that state, that cookie, and - on
 * Enterprise Server - the same person signed in, then exchanges the code with the verifier.
 *
 * <p><b>Tokens live encrypted on the connection row</b> (its secret, a JSON object), and never leave
 * this class except as an access token handed to a session (ResolvedCredentials), which ConnectorHttp
 * sends to the connection's own server and no other host. A refresh runs under a per-connection
 * lock; a ROTATED refresh token is persisted before the new access token is used, so a crash after
 * the exchange cannot leave the row holding a token the service has already retired. A refresh the
 * service refuses marks the connection NEEDS_RECONNECT, said once, instead of retried every sync.
 *
 * <p><b>Whose OAuth client.</b> On the single-user editions the person registers their own app with
 * the service and types its client id and secret on the connection. On Enterprise Server the client
 * is the deployment's, set once by a Super admin ({@code oauth.<protocol>.client-id} and
 * {@code .client-secret}), so nobody adding a connection is asked for one.
 *
 * <p>Client credentials and a service account's signed assertion are token suppliers on the same
 * interface ({@link #tokensFor}): no browser, a token cached until shortly before it expires.
 */
@Service
public class OAuth2ConnectionService implements ConnectionTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(OAuth2ConnectionService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String CALLBACK_PATH = "/api/v1/connections/oauth/callback";
    public static final String FLOW_COOKIE = "pxm_connection_oauth";
    public static final Duration FLOW_LIFETIME = Duration.ofMinutes(10);
    static final int MAX_PENDING_FLOWS = 200;
    // A token this close to its expiry is refreshed rather than sent.
    static final Duration EXPIRY_MARGIN = Duration.ofSeconds(60);
    // The deployment-wide OAuth clients, Enterprise Server: oauth.<protocol>.client-id / .client-secret.
    public static final String CLIENTS_PREFIX = "oauth";

    // Keys of the stored secret JSON. clientSecret is the person's own client (single-user editions);
    // the rest are the grant, which no session ever sees.
    static final String CLIENT_ID = "clientId";
    static final String CLIENT_SECRET = "clientSecret";
    static final String ACCESS_TOKEN = "accessToken";
    static final String REFRESH_TOKEN = "refreshToken";
    static final String EXPIRES_AT = "expiresAt";
    static final String SCOPE = "scope";
    static final List<String> GRANT_KEYS = List.of(ACCESS_TOKEN, REFRESH_TOKEN, EXPIRES_AT, SCOPE);

    /** How a callback ended - a code the SPA turns into a sentence; never text of its own. */
    public enum Outcome {
        CONNECTED("connected"), DENIED("denied"), EXPIRED("expired"), FAILED("failed"),
        NOT_CONFIGURED("not_configured");

        private final String code;

        Outcome(String code) { this.code = code; }

        public String code() { return code; }
    }

    public record FlowStart(String authorizeUrl, String browserBinding) {}

    record Flow(UUID connectionId, String ownerKey, String verifier, String binding, String redirectUri,
                Instant expires) {}

    private record Client(String id, String secret) {}

    private record CachedToken(String token, Instant expires) {}

    private final ConnectorConnectionRepository repository;
    private final CredentialEncryptionService encryption;
    private final ConnectorCatalog catalog;
    private final ConnectorHttp http;
    private final SettingsService settings;
    private final ApplicationEventPublisher events;
    private final boolean multiUser;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Flow> pending = new ConcurrentHashMap<>();
    private final Map<UUID, ReentrantLock> refreshLocks = new ConcurrentHashMap<>();
    private final Map<UUID, CachedToken> appTokens = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public OAuth2ConnectionService(ConnectorConnectionRepository repository, CredentialEncryptionService encryption,
                                   ConnectorCatalog catalog, ConnectorHttp http, SettingsService settings,
                                   ApplicationEventPublisher events,
                                   @Value("${proxyma.multi-user:false}") boolean multiUser) {
        this(repository, encryption, catalog, http, settings, events, multiUser, Clock.systemUTC());
    }

    OAuth2ConnectionService(ConnectorConnectionRepository repository, CredentialEncryptionService encryption,
                            ConnectorCatalog catalog, ConnectorHttp http, SettingsService settings,
                            ApplicationEventPublisher events, boolean multiUser, Clock clock) {
        this.repository = repository;
        this.encryption = encryption;
        this.catalog = catalog;
        this.http = http;
        this.settings = settings;
        this.events = events;
        this.multiUser = multiUser;
        this.clock = clock;
    }

    // -- the sign-in ---------------------------------------------------------------------------

    /**
     * A sign-in for one of the caller's own connections: the authorize URL to send the browser to,
     * and the value of the cookie that binds the flow to this browser.
     *
     * @throws IllegalArgumentException for a connection that is not the caller's, or whose service
     *                                  does not sign in with OAuth2
     * @throws IllegalStateException    when no OAuth client is set up for the service
     */
    public FlowStart start(String userId, UUID connectionId, String redirectUri) {
        String owner = ConnectorConnectionService.ownerKey(userId);
        ConnectorConnection c = repository.findByIdAndOwnerId(connectionId, owner)
                .orElseThrow(() -> new IllegalArgumentException("No such connection"));
        OAuth2Spec spec = spec(c)
                .orElseThrow(() -> new IllegalArgumentException("This connection does not sign in."));
        Client client = client(c).orElseThrow(() -> new IllegalStateException(
                "No OAuth client is set up for " + catalog.displayName(c.getProtocol()) + " yet."));

        purgeExpired();
        if (pending.size() >= MAX_PENDING_FLOWS) {
            throw new IllegalStateException("Too many sign-ins are in progress. Try again in a few minutes.");
        }
        String state = randomValue();
        String verifier = randomValue();
        String binding = randomValue();
        pending.put(state, new Flow(c.getId(), owner, verifier, binding, redirectUri,
                clock.instant().plus(FLOW_LIFETIME)));

        Map<String, String> query = new LinkedHashMap<>();
        query.put("response_type", "code");
        query.put("client_id", client.id());
        query.put("redirect_uri", redirectUri);
        query.put("state", state);
        query.put("code_challenge", challenge(verifier));
        query.put("code_challenge_method", "S256");
        List<String> scopes = new ArrayList<>(spec.scopes());
        // Write scopes only while the connection's Write switch is on - incremental consent.
        if (c.isAllowWrite()) scopes.addAll(spec.writeScopes());
        if (!scopes.isEmpty()) query.put("scope", String.join(" ", scopes));
        query.putAll(spec.extraAuthorizeParams());
        return new FlowStart(spec.authorizeUrl() + (spec.authorizeUrl().contains("?") ? "&" : "?") + form(query), binding);
    }

    /** The callback as the browser brought it back. */
    public record CallbackRequest(String state, String code, String error, String cookie, String sessionUser) {}

    /**
     * Finishes a sign-in. Single use: the flow is removed whatever happens next. Refused unless the
     * browser carries the cookie that flow was started with and, where there are accounts, the
     * person signed in is the one who started it.
     */
    public Outcome complete(CallbackRequest request) {
        Flow flow = request.state() == null ? null : pending.remove(request.state());
        if (flow == null || clock.instant().isAfter(flow.expires())) return Outcome.EXPIRED;
        if (request.cookie() == null || !MessageDigest.isEqual(
                request.cookie().getBytes(StandardCharsets.UTF_8), flow.binding().getBytes(StandardCharsets.UTF_8))) {
            log.warn("[OAuth] A sign-in callback came back without its browser's cookie - refused");
            return Outcome.FAILED;
        }
        if (multiUser && request.sessionUser() != null
                && !ConnectorConnectionService.ownerKey(request.sessionUser()).equals(flow.ownerKey())) {
            log.warn("[OAuth] A sign-in callback came back under another account's session - refused");
            return Outcome.FAILED;
        }
        if (request.error() != null) return Outcome.DENIED;
        if (request.code() == null || request.code().isBlank()) return Outcome.FAILED;

        ConnectorConnection c = repository.findById(flow.connectionId()).orElse(null);
        if (c == null || !c.getOwnerId().equals(flow.ownerKey())) return Outcome.FAILED;
        OAuth2Spec spec = spec(c).orElse(null);
        Client client = client(c).orElse(null);
        if (spec == null || client == null) return Outcome.NOT_CONFIGURED;

        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", request.code());
        form.put("redirect_uri", flow.redirectUri());
        form.put("code_verifier", flow.verifier());
        Map<String, String> secret = storedSecret(c);
        try {
            JsonNode token = tokenRequest(spec, client, form);
            // Inside the try: an answer with no token in it - Slack's 200 with ok:false, a gateway's
            // empty object - is a failed sign-in like any refusal, never an exception out of the callback.
            applyGrant(secret, token);
            keepGrantFields(c, spec, token);
        } catch (RuntimeException e) {
            log.warn("[OAuth] Exchanging the code for {} failed: {}", c.getProtocol(), e.getMessage());
            return Outcome.FAILED;
        }
        c.setSecret(encryption.encrypt(ConfigJson.write(secret)));
        Instant now = clock.instant();
        c.setStatus(ConnectionStatus.CONNECTED);
        c.setStatusDetail(null);
        c.setConnectedAt(now);
        c.setLastRefreshedAt(now);
        repository.save(c);
        events.publishEvent(new ConnectionChangedEvent(c.getId(), false));
        AuditEntry.of(AuditCategory.SOURCES, "connection.signed-in")
                .user(ConnectorConnection.LOCAL_OWNER.equals(c.getOwnerId()) ? null : c.getOwnerId())
                .target(c.getName())
                .summary("Signed in to " + c.getName())
                .detail("service", catalog.displayName(c.getProtocol()))
                .record();
        return Outcome.CONNECTED;
    }

    /** Forgets a connection's grant - the connection and its sources stay, signed out. */
    public void signOut(String userId, UUID connectionId) {
        ConnectorConnection c = repository.findByIdAndOwnerId(connectionId, ConnectorConnectionService.ownerKey(userId))
                .orElseThrow(() -> new IllegalArgumentException("No such connection"));
        Map<String, String> secret = storedSecret(c);
        GRANT_KEYS.forEach(secret::remove);
        c.setSecret(secret.isEmpty() ? null : encryption.encrypt(ConfigJson.write(secret)));
        c.setStatus(null);
        c.setStatusDetail(null);
        c.setConnectedAt(null);
        // What the grant said about the account goes with it: a sign-in to another org must not
        // inherit the last one's address.
        c.setAccountJson(null);
        repository.save(c);
        appTokens.remove(connectionId);
        events.publishEvent(new ConnectionChangedEvent(c.getId(), false));
    }

    // -- tokens --------------------------------------------------------------------------------

    @Override
    public boolean supports(ConnectionProtocol protocol, AuthKind kind) {
        if (kind != AuthKind.OAUTH2_CODE && kind != AuthKind.OAUTH2_CLIENT && kind != AuthKind.SERVICE_ACCOUNT) {
            return false;
        }
        return spec(protocol).isPresent();
    }

    @Override
    public TokenSupplier tokensFor(UUID connectionId) {
        return new TokenSupplier() {
            @Override
            public String accessToken() {
                return OAuth2ConnectionService.this.accessToken(connectionId, false);
            }

            @Override
            public void invalidate() {
                appTokens.remove(connectionId);
                // The token the service just refused - the stored one now. Remembered by VALUE, so a
                // refresh another thread has already made is used rather than repeated.
                repository.findById(connectionId).map(c -> storedSecret(c).get(ACCESS_TOKEN))
                        .ifPresent(token -> refused.put(connectionId, token));
            }
        };
    }

    // The last access token a service refused, per connection: never handed out again.
    private final Map<UUID, String> refused = new ConcurrentHashMap<>();

    /** A current access token for the connection - refreshed first if it has expired or was refused. */
    String accessToken(UUID connectionId, boolean force) {
        ConnectorConnection c = repository.findById(connectionId)
                .orElseThrow(() -> new ConnectorAuthException("This source's connection no longer exists."));
        AuthKind kind = kindOf(c);
        if (kind == AuthKind.OAUTH2_CLIENT || kind == AuthKind.SERVICE_ACCOUNT) return appToken(c, kind);

        Map<String, String> secret = storedSecret(c);
        if (!force && usable(connectionId, secret)) return secret.get(ACCESS_TOKEN);

        ReentrantLock lock = refreshLocks.computeIfAbsent(connectionId, k -> new ReentrantLock());
        lock.lock();
        try {
            // Another thread may have refreshed while this one waited: its token is the one to use,
            // and refreshing again would spend a rotated refresh token twice.
            c = repository.findById(connectionId).orElseThrow(
                    () -> new ConnectorAuthException("This source's connection no longer exists."));
            secret = storedSecret(c);
            if (!force && usable(connectionId, secret)) return secret.get(ACCESS_TOKEN);
            String token = refresh(c, secret);
            refused.remove(connectionId);
            return token;
        } finally {
            lock.unlock();
        }
    }

    // A stored access token that has not expired and is not the one the service last refused.
    private boolean usable(UUID connectionId, Map<String, String> secret) {
        return fresh(secret) && !secret.get(ACCESS_TOKEN).equals(refused.get(connectionId));
    }

    private String refresh(ConnectorConnection c, Map<String, String> secret) {
        String service = catalog.displayName(c.getProtocol());
        String refreshToken = secret.get(REFRESH_TOKEN);
        if (refreshToken == null || refreshToken.isBlank()) {
            // Nothing to refresh with: a grant with no refresh token is good until it expires or is
            // refused, and then only a new sign-in helps.
            markNeedsReconnect(c, "Signed out - sign in again.");
            throw new ConnectorAuthException("Sign in to " + c.getName() + " again in Settings > Connections.");
        }
        OAuth2Spec spec = spec(c).orElseThrow(
                () -> new ConnectorAuthException(service + " no longer signs in this way."));
        Client client = client(c).orElseThrow(
                () -> new ConnectorAuthException("No OAuth client is set up for " + service + " any more."));
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        JsonNode token;
        try {
            token = tokenRequest(spec, client, form);
        } catch (ConnectorHttpException e) {
            if (e.status() == 400 || e.status() == 401) {
                // The service refused the grant itself - revoked, expired, consent withdrawn. Said
                // once on the connection rather than retried on every sync.
                markNeedsReconnect(c, service + " refused the sign-in. Sign in again.");
                throw new ConnectorAuthException(service + " refused the sign-in of " + c.getName()
                        + ". Sign in again in Settings > Connections.", e);
            }
            throw e;
        }
        applyGrant(secret, token);
        keepGrantFields(c, spec, token);
        // Persisted BEFORE the access token is used: a rotated refresh token the row does not hold
        // is a sign-in lost at the next restart.
        c.setSecret(encryption.encrypt(ConfigJson.write(secret)));
        c.setStatus(ConnectionStatus.CONNECTED);
        c.setStatusDetail(null);
        c.setLastRefreshedAt(clock.instant());
        repository.save(c);
        return secret.get(ACCESS_TOKEN);
    }

    // A client-credentials or service-account token, cached in memory until shortly before it expires.
    private String appToken(ConnectorConnection c, AuthKind kind) {
        CachedToken cached = appTokens.get(c.getId());
        if (cached != null && clock.instant().isBefore(cached.expires().minus(EXPIRY_MARGIN))) return cached.token();
        ReentrantLock lock = refreshLocks.computeIfAbsent(c.getId(), k -> new ReentrantLock());
        lock.lock();
        try {
            cached = appTokens.get(c.getId());
            if (cached != null && clock.instant().isBefore(cached.expires().minus(EXPIRY_MARGIN))) return cached.token();
            OAuth2Spec spec = spec(c).orElseThrow(
                    () -> new ConnectorAuthException(catalog.displayName(c.getProtocol()) + " does not sign in this way."));
            Map<String, String> form = new LinkedHashMap<>();
            JsonNode token;
            if (kind == AuthKind.SERVICE_ACCOUNT) {
                form.put("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
                form.put("assertion", serviceAccountAssertion(c, spec));
                token = tokenRequest(spec, null, form);
            } else {
                Client client = ownClient(c).or(() -> client(c)).orElseThrow(() -> new ConnectorAuthException(
                        "This connection has no client id and secret. Add them in Settings > Connections."));
                form.put("grant_type", "client_credentials");
                if (spec.clientGrantScopes() && !spec.scopes().isEmpty()) form.put("scope", String.join(" ", spec.scopes()));
                form.putAll(clientGrantParams(c, spec));
                token = tokenRequest(spec, client, form);
            }
            String access = token.path("access_token").asText(null);
            if (access == null || access.isBlank()) {
                throw new ConnectorAuthException(catalog.displayName(c.getProtocol()) + " answered without a token.");
            }
            long expiresIn = token.path("expires_in").asLong(3600);
            appTokens.put(c.getId(), new CachedToken(access, clock.instant().plusSeconds(expiresIn)));
            return access;
        } finally {
            lock.unlock();
        }
    }

    /**
     * A service account's signed assertion (RFC 7523): the account's JSON key holds its email, its
     * private key and its token endpoint; the connection's settings may name a user to act as
     * ({@code subject}, Google's domain-wide delegation).
     */
    String serviceAccountAssertion(ConnectorConnection c, OAuth2Spec spec) {
        String keyJson = storedSecret(c).get("serviceAccountJson");
        if (keyJson == null || keyJson.isBlank()) {
            throw new ConnectorAuthException("This connection has no service account key. Add it in Settings > Connections.");
        }
        try {
            JsonNode key = JSON.readTree(keyJson);
            String email = key.path("client_email").asText(null);
            String pem = key.path("private_key").asText(null);
            String audience = key.path("token_uri").asText(spec.tokenUrl());
            if (email == null || pem == null) throw new IllegalArgumentException("incomplete key");
            Instant now = clock.instant();
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("iss", email);
            claims.put("aud", audience);
            claims.put("scope", String.join(" ", spec.scopes()));
            claims.put("iat", now.getEpochSecond());
            claims.put("exp", now.plusSeconds(3600).getEpochSecond());
            String subject = ConfigJson.parse(c.getSettingsJson()).get("subject");
            if (subject != null && !subject.isBlank()) claims.put("sub", subject);
            String header = b64(JSON.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT")));
            String body = b64(JSON.writeValueAsBytes(claims));
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey(pem));
            signer.update((header + "." + body).getBytes(StandardCharsets.US_ASCII));
            return header + "." + body + "." + b64(signer.sign());
        } catch (ConnectorAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorAuthException("The service account key could not be read. Paste the whole JSON key again.", e);
        }
    }

    private static PrivateKey privateKey(String pem) throws Exception {
        String base64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
    }

    // -- helpers -------------------------------------------------------------------------------

    private JsonNode tokenRequest(OAuth2Spec spec, Client client, Map<String, String> form) {
        URI tokenUri = URI.create(spec.tokenUrl());
        String origin = tokenUri.getScheme() + "://" + tokenUri.getRawAuthority();
        ApiClient.Builder builder = http.client(origin).service("The sign-in service").maxAttempts(2);
        Map<String, String> body = new LinkedHashMap<>(form);
        if (client != null) {
            if (spec.clientAuth() == OAuth2Spec.ClientAuth.BASIC) {
                builder.auth(ai.proxyma.core.connector.spi.ApiAuth.basic(urlEncode(client.id()),
                        client.secret() == null ? "" : urlEncode(client.secret())));
            } else {
                body.put("client_id", client.id());
                if (client.secret() != null && !client.secret().isBlank()) body.put("client_secret", client.secret());
            }
        }
        ApiClient tokenClient = builder.build();
        ApiResponse response = spec.tokenBody() == OAuth2Spec.TokenBody.JSON
                ? tokenClient.send(HttpMethod.POST, spec.tokenUrl(), Map.of(), body, spec.tokenHeaders())
                : tokenClient.postForm(spec.tokenUrl(), body, spec.tokenHeaders());
        JsonNode token = tokenClient.requireOk(response).json();
        // Slack answers every refusal with a 200 and "ok": false. Read as the 400 RFC 6749 would have
        // sent, so a refused refresh asks for a new sign-in here as it does for every other service.
        if (token.path("ok").isBoolean() && !token.path("ok").asBoolean()) {
            String reason = token.path("error").asText("");
            if (reason.isBlank()) {
                throw new ConnectorHttpException("The sign-in service refused the request.", 400, tokenUri.getRawPath());
            }
            throw new ConnectorHttpException("The sign-in service refused the request (" + reason + ").", 400,
                    tokenUri.getRawPath());
        }
        return token;
    }

    // The client-credentials parameters a service takes from the connection's settings (Box's
    // box_subject_type and box_subject_id). One that is missing is said here, before the service
    // refuses the grant with a reason nobody can act on.
    private Map<String, String> clientGrantParams(ConnectorConnection c, OAuth2Spec spec) {
        Map<String, String> settings = ConfigJson.parse(c.getSettingsJson());
        Map<String, String> out = new LinkedHashMap<>();
        spec.clientGrantParams().forEach((param, key) -> {
            String value = settings.get(key);
            if (value == null || value.isBlank()) {
                throw new ConnectorAuthException("This connection is missing a setting its sign-in needs ("
                        + key + "). Add it in Settings > Connections.");
            }
            out.put(param, value.strip());
        });
        return out;
    }

    // The token answer's fields the service's connector needs later (OAuth2Spec.grantFields) -
    // Salesforce's instance_url. Kept in the connection's accountJson, NOT in its encrypted secret
    // and NOT in its settings: they are not secret (an org's address); sessions are handed them,
    // while they are never handed the secret object; and only a sign-in writes accountJson - the
    // connection form rewrites settings, and a person must not be able to type the address a token
    // is sent to under the name of what the service said.
    private void keepGrantFields(ConnectorConnection c, OAuth2Spec spec, JsonNode token) {
        if (spec.grantFields().isEmpty()) return;
        Map<String, String> kept = new LinkedHashMap<>();
        for (String field : spec.grantFields()) {
            JsonNode v = token.path(field);
            if (v.isValueNode() && !v.isNull() && !v.asText().isBlank()) kept.put(field, v.asText());
        }
        // A refresh that names nothing keeps what the sign-in said; one that names a field replaces
        // the set whole, so nothing an older grant said outlives a newer one.
        if (kept.isEmpty() && c.getAccountJson() != null) return;
        c.setAccountJson(ConfigJson.write(kept));
    }

    // The grant a token endpoint answered with, into the stored secret. A refresh that hands back
    // no new refresh token keeps the old one (most services do not rotate).
    private void applyGrant(Map<String, String> secret, JsonNode token) {
        String access = token.path("access_token").asText(null);
        if (access == null || access.isBlank()) {
            throw new ConnectorAuthException("The service answered the sign-in without a token.");
        }
        secret.put(ACCESS_TOKEN, access);
        String refresh = token.path("refresh_token").asText(null);
        if (refresh != null && !refresh.isBlank()) secret.put(REFRESH_TOKEN, refresh);
        if (token.has("expires_in")) {
            secret.put(EXPIRES_AT, String.valueOf(clock.instant().plusSeconds(token.path("expires_in").asLong()).getEpochSecond()));
        } else {
            secret.remove(EXPIRES_AT);
        }
        if (token.hasNonNull("scope")) secret.put(SCOPE, token.path("scope").asText());
    }

    private boolean fresh(Map<String, String> secret) {
        return secret.get(ACCESS_TOKEN) != null && !expired(secret);
    }

    private boolean expired(Map<String, String> secret) {
        String at = secret.get(EXPIRES_AT);
        if (at == null) return false; // a token with no stated lifetime (GitHub's) lasts until refused
        try {
            return !clock.instant().plus(EXPIRY_MARGIN).isBefore(Instant.ofEpochSecond(Long.parseLong(at)));
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private void markNeedsReconnect(ConnectorConnection c, String detail) {
        c.setStatus(ConnectionStatus.NEEDS_RECONNECT);
        c.setStatusDetail(detail);
        repository.save(c);
        events.publishEvent(new ConnectionChangedEvent(c.getId(), false));
    }

    private Optional<OAuth2Spec> spec(ConnectionProtocol protocol) {
        if (protocol == null) return Optional.empty();
        return catalog.connectionPlugin(protocol).map(ConnectionPlugin::oauth2);
    }

    // The sign-in of one connection: the service's, unless its addresses are the account's own
    // (ConnectionPlugin.oauth2(settings, baseUrl)) - a Zendesk subdomain.
    private Optional<OAuth2Spec> spec(ConnectorConnection c) {
        if (c.getProtocol() == null) return Optional.empty();
        return catalog.connectionPlugin(c.getProtocol())
                .map(p -> p.oauth2(ConfigJson.parse(c.getSettingsJson()), c.getBaseUrl()));
    }

    private AuthKind kindOf(ConnectorConnection c) {
        if (c.getAuthMethod() != null) {
            try {
                return AuthKind.valueOf(c.getAuthMethod());
            } catch (IllegalArgumentException ignored) {
                // a method this build does not know reads as the service's first
            }
        }
        var d = catalog.protocol(c.getProtocol());
        return d == null ? AuthKind.TOKEN : d.primaryAuth().kind();
    }

    // The OAuth client a connection signs in through: the deployment's on Enterprise Server, else the
    // one its owner typed on it.
    private Optional<Client> client(ConnectorConnection c) {
        if (multiUser) return deploymentClient(c.getProtocol());
        return ownClient(c);
    }

    // The client typed on the connection. A client-credentials grant uses it on every edition: that
    // app is an application credential for one account (a Box app an enterprise's admin authorized),
    // typed like a token, and is not the browser sign-in's app that a deployment sets once.
    private Optional<Client> ownClient(ConnectorConnection c) {
        String id = ConfigJson.parse(c.getSettingsJson()).get(CLIENT_ID);
        if (id == null || id.isBlank()) return Optional.empty();
        return Optional.of(new Client(id, storedSecret(c).get(CLIENT_SECRET)));
    }

    /** The deployment-wide client for a service, Enterprise Server - set by a Super admin. */
    public Optional<String> deploymentClientId(ConnectionProtocol protocol) {
        return deploymentClient(protocol).map(Client::id);
    }

    private Optional<Client> deploymentClient(ConnectionProtocol protocol) {
        if (settings == null) return Optional.empty();
        Map<String, Object> fields = settings.getSettingsFields(CLIENTS_PREFIX);
        String base = protocol.name().toLowerCase(Locale.ROOT);
        Object id = fields.get(base + ".client-id");
        if (id == null || id.toString().isBlank()) return Optional.empty();
        Object secret = fields.get(base + ".client-secret");
        return Optional.of(new Client(id.toString().trim(), secret == null ? null : secret.toString()));
    }

    /** Sets the deployment-wide client for a service (a Super admin's, Enterprise Server). The secret is kept encrypted. */
    public void saveDeploymentClient(ConnectionProtocol protocol, String clientId, String clientSecret) {
        if (settings == null) throw new IllegalStateException("Settings are not available.");
        if (spec(protocol).isEmpty()) throw new IllegalArgumentException("That service does not sign in with OAuth.");
        String base = protocol.name().toLowerCase(Locale.ROOT);
        Map<String, String> values = new LinkedHashMap<>();
        values.put(base + ".client-id", clientId == null ? "" : clientId.trim());
        if (clientSecret != null && !clientSecret.isBlank()) {
            values.put(base + ".client-secret", encryption.encrypt(clientSecret.trim()));
        }
        settings.saveFields(CLIENTS_PREFIX, values);
    }

    // The stored secret as its map of fields; one that will not decrypt reads as empty.
    private Map<String, String> storedSecret(ConnectorConnection c) {
        String plain = CredentialEncryptionService.decryptOrNull(encryption, c.getSecret(), "a connection's sign-in");
        return new LinkedHashMap<>(ConfigJson.parse(plain));
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        pending.entrySet().removeIf(e -> now.isAfter(e.getValue().expires()));
    }

    private String randomValue() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return b64(bytes);
    }

    static String challenge(String verifier) {
        try {
            return b64(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String form(Map<String, String> values) {
        StringBuilder out = new StringBuilder();
        values.forEach((k, v) -> {
            if (v == null) return;
            if (!out.isEmpty()) out.append('&');
            out.append(urlEncode(k)).append('=').append(urlEncode(v));
        });
        return out.toString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
