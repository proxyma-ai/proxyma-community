package ai.proxyma.core.settings.connection;

import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.AuthKind;
import ai.proxyma.core.connector.spi.AuthMethod;
import ai.proxyma.core.connector.spi.ConfigJson;
import ai.proxyma.core.connector.spi.ConnectionPlugin;
import ai.proxyma.core.connector.spi.ConnectionTestResult;
import ai.proxyma.core.connector.spi.ConnectorAuthException;
import ai.proxyma.core.connector.spi.ConnectorCatalog;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.FieldSpec;
import ai.proxyma.core.connector.spi.OAuth2Spec;
import ai.proxyma.core.connector.spi.ProtocolDescriptor;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import ai.proxyma.core.connector.testing.LoopbackApi;
import ai.proxyma.core.settings.service.CredentialEncryptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The generic OAuth2 sign-in every service on the connector framework gets by declaring a spec -
 * against a loopback authorization server. Security tests wearing behavior clothes: a state used
 * twice, a callback from another browser or another account, a refresh token kept in clear text or
 * lost on rotation, and a refresh token handed to a session would each be a credential leak.
 */
class OAuth2ConnectionServiceTest {

    private LoopbackApi auth;
    private final Map<UUID, ConnectorConnection> rows = new LinkedHashMap<>();
    private final List<String> savedSecrets = new ArrayList<>();
    private ConnectorConnectionRepository repository;
    private CredentialEncryptionService encryption;
    private ConnectorCatalog catalog;
    private Instant now = Instant.parse("2026-10-14T08:00:00Z");
    private OAuth2ConnectionService service;

    @BeforeEach
    void setUp() {
        auth = LoopbackApi.start();
        repository = mock(ConnectorConnectionRepository.class);
        when(repository.save(any(ConnectorConnection.class))).thenAnswer(inv -> {
            ConnectorConnection c = inv.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            rows.put(c.getId(), c);
            savedSecrets.add(c.getSecret());
            return c;
        });
        when(repository.findById(any(UUID.class))).thenAnswer(inv -> Optional.ofNullable(rows.get(inv.<UUID>getArgument(0))));
        when(repository.findByIdAndOwnerId(any(UUID.class), anyString())).thenAnswer(inv ->
                Optional.ofNullable(rows.get(inv.<UUID>getArgument(0))).filter(c -> c.getOwnerId().equals(inv.getArgument(1))));
        encryption = mock(CredentialEncryptionService.class);
        when(encryption.encrypt(anyString())).thenAnswer(inv -> "ENC(" + inv.getArgument(0) + ")");
        when(encryption.decrypt(anyString())).thenAnswer(inv -> {
            String v = inv.getArgument(0);
            return v.substring(4, v.length() - 1);
        });
        catalog = ConnectorCatalog.of(List.of(), List.of(plugin()));
        service = service(false);
    }

    @AfterEach
    void stop() {
        auth.close();
    }

    private OAuth2ConnectionService service(boolean multiUser) {
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now; }
        };
        return new OAuth2ConnectionService(repository, encryption, catalog, ConnectorHttp.direct(d -> { }), null,
                mock(ApplicationEventPublisher.class), multiUser, clock);
    }

    private ConnectionPlugin plugin() {
        return plugin(OAuth2Spec.builder(auth.url("/authorize"), auth.url("/token")).scopes(List.of("read"))
                .writeScopes(List.of("write")).authorizeParam("access_type", "offline").build());
    }

    // The test service, signing in as spec says - and with every method, so a test picks one.
    private ConnectionPlugin plugin(OAuth2Spec spec) {
        ProtocolDescriptor d = ProtocolDescriptor.builder(ConnectionProtocol.CONFLUENCE)
                .displayName("Wiki")
                .auth(AuthMethod.of(AuthKind.OAUTH2_CODE, "Sign in with Wiki",
                                FieldSpec.text("clientId", "Client id"), FieldSpec.secret("clientSecret", "Client secret")),
                        AuthMethod.of(AuthKind.OAUTH2_CLIENT, "App",
                                FieldSpec.text("clientId", "Client id"), FieldSpec.secret("clientSecret", "Client secret")),
                        AuthMethod.of(AuthKind.SERVICE_ACCOUNT, "Service account",
                                FieldSpec.secret("serviceAccountJson", "Key")))
                .sourceTypes(ConnectorType.CONFLUENCE)
                .build();
        return new ConnectionPlugin() {
            @Override public ProtocolDescriptor descriptor() { return d; }
            @Override public ConnectionTestResult test(ResolvedCredentials c, ConnectorHttp http) { return ConnectionTestResult.ok("ok"); }
            @Override public OAuth2Spec oauth2() { return spec; }
        };
    }

    private ConnectorConnection connection(String owner, AuthKind kind, Map<String, String> secret) {
        ConnectorConnection c = new ConnectorConnection();
        c.setOwnerId(owner);
        c.setName("Wiki");
        c.setProtocol(ConnectionProtocol.CONFLUENCE);
        c.setAuthMethod(kind.name());
        c.setSettingsJson(ConfigJson.write(Map.of("clientId", "client-1")));
        c.setSecret(secret.isEmpty() ? null : encryption.encrypt(ConfigJson.write(secret)));
        return repository.save(c);
    }

    private static Map<String, String> query(String url) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            out.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private Map<String, String> stored(UUID id) {
        return ConfigJson.parse(encryption.decrypt(rows.get(id).getSecret()));
    }

    @Test
    @DisplayName("a sign-in sends the browser with PKCE S256, a state and the read scopes, and the code is exchanged with the verifier")
    void signInWithPkce() {
        ConnectorConnection c = connection(ConnectorConnection.LOCAL_OWNER, AuthKind.OAUTH2_CODE, Map.of("clientSecret", "cs"));
        auth.on("POST", "/token").json("{'access_token':'at-1','refresh_token':'rt-1','expires_in':3600}");

        OAuth2ConnectionService.FlowStart flow = service.start(null, c.getId(), "http://localhost/cb");
        Map<String, String> q = query(flow.authorizeUrl());
        assertThat(q).containsEntry("client_id", "client-1").containsEntry("code_challenge_method", "S256")
                .containsEntry("redirect_uri", "http://localhost/cb").containsEntry("scope", "read")
                .containsEntry("access_type", "offline");

        var outcome = service.complete(new OAuth2ConnectionService.CallbackRequest(q.get("state"), "the-code", null,
                flow.browserBinding(), null));

        assertThat(outcome).isEqualTo(OAuth2ConnectionService.Outcome.CONNECTED);
        Map<String, String> form = query("http://x/?" + auth.last().body());
        assertThat(form).containsEntry("grant_type", "authorization_code").containsEntry("code", "the-code")
                .containsEntry("client_id", "client-1").containsEntry("client_secret", "cs");
        assertThat(OAuth2ConnectionService.challenge(form.get("code_verifier"))).isEqualTo(q.get("code_challenge"));
        // Stored encrypted on the row, never in clear text.
        assertThat(rows.get(c.getId()).getSecret()).startsWith("ENC(");
        assertThat(stored(c.getId())).containsEntry("accessToken", "at-1").containsEntry("refreshToken", "rt-1")
                .containsEntry("clientSecret", "cs");
        assertThat(rows.get(c.getId()).getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
    }

    @Test
    @DisplayName("a service whose sign-in is the account's own is asked for each connection's addresses, and may refuse one")
    void perConnectionSignIn() {
        ProtocolDescriptor d = plugin().descriptor();
        ConnectionPlugin perAccount = new ConnectionPlugin() {
            @Override public ProtocolDescriptor descriptor() { return d; }
            @Override public ConnectionTestResult test(ResolvedCredentials c, ConnectorHttp http) { return ConnectionTestResult.ok("ok"); }
            @Override public OAuth2Spec oauth2() { return OAuth2Spec.of("https://unused.example/a", "https://unused.example/t", List.of()); }
            @Override public OAuth2Spec oauth2(Map<String, String> settings, String baseUrl) {
                String account = settings.get("account");
                if (account == null) return null;
                return OAuth2Spec.of(auth.url("/" + account + "/authorize"), auth.url("/" + account + "/token"), List.of("read"));
            }
        };
        catalog = ConnectorCatalog.of(List.of(), List.of(perAccount));
        service = service(false);
        ConnectorConnection c = connection(ConnectorConnection.LOCAL_OWNER, AuthKind.OAUTH2_CODE, Map.of("clientSecret", "cs"));
        c.setSettingsJson(ConfigJson.write(Map.of("clientId", "client-1", "account", "acme")));
        auth.on("POST", "/acme/token").json("{'access_token':'at-1','refresh_token':'rt-1','expires_in':3600}");

        OAuth2ConnectionService.FlowStart flow = service.start(null, c.getId(), "http://localhost/cb");
        assertThat(flow.authorizeUrl()).startsWith(auth.url("/acme/authorize?"));
        var outcome = service.complete(new OAuth2ConnectionService.CallbackRequest(query(flow.authorizeUrl()).get("state"),
                "the-code", null, flow.browserBinding(), null));
        assertThat(outcome).isEqualTo(OAuth2ConnectionService.Outcome.CONNECTED);
        assertThat(auth.requests("/acme/token")).hasSize(1);

        ConnectorConnection other = connection(ConnectorConnection.LOCAL_OWNER, AuthKind.OAUTH2_CODE, Map.of("clientSecret", "cs"));
        assertThatThrownBy(() -> service.start(null, other.getId(), "http://localhost/cb"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a state is good once, only in the browser that started it, and only for the account that started it")
    void stateIsSingleUseAndBound() {
        ConnectorConnection c = connection("ana", AuthKind.OAUTH2_CODE, Map.of());
        auth.on("POST", "/token").json("{'access_token':'at-1'}");
        OAuth2ConnectionService server = service(true);
        // On Enterprise Server the client is the deployment's; with none configured, nothing starts.
        assertThatThrownBy(() -> server.start("ana", c.getId(), "http://h/cb")).isInstanceOf(IllegalStateException.class);

        OAuth2ConnectionService.FlowStart flow = service.start("ana", c.getId(), "http://h/cb");
        String state = query(flow.authorizeUrl()).get("state");
        assertThat(service.complete(new OAuth2ConnectionService.CallbackRequest(state, "code", null, "other-browser", null)))
                .isEqualTo(OAuth2ConnectionService.Outcome.FAILED);
        // Spent by that attempt: the right cookie cannot reuse it.
        assertThat(service.complete(new OAuth2ConnectionService.CallbackRequest(state, "code", null, flow.browserBinding(), null)))
                .isEqualTo(OAuth2ConnectionService.Outcome.EXPIRED);
        assertThat(auth.requests("/token")).isEmpty();

        // Somebody else's connection cannot even be started.
        assertThatThrownBy(() -> service.start("bo", c.getId(), "http://h/cb")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("on a single-user edition a sign-in opened in the system browser completes on its state alone, once")
    void browserSignInOnStateAlone() {
        ConnectorConnection c = connection(ConnectorConnection.LOCAL_OWNER, AuthKind.OAUTH2_CODE, Map.of("clientSecret", "cs"));
        auth.on("POST", "/token").json("{'access_token':'at-1','refresh_token':'rt-1','expires_in':3600}");

        String url = service.begin(null, c.getId(), "http://127.0.0.1:4246/cb");
        Map<String, String> q = query(url);
        assertThat(q).containsEntry("code_challenge_method", "S256").containsEntry("redirect_uri", "http://127.0.0.1:4246/cb");
        String state = q.get("state");
        // The callback learns it may answer with its own page, and for which service.
        assertThat(service.unboundService(state)).contains("Wiki");

        // No cookie: the system browser shares none with the window that began it.
        assertThat(service.complete(new OAuth2ConnectionService.CallbackRequest(state, "the-code", null, null, null)))
                .isEqualTo(OAuth2ConnectionService.Outcome.CONNECTED);
        // The verifier stayed here: the code was exchanged with it.
        assertThat(OAuth2ConnectionService.challenge(query("http://x/?" + auth.last().body()).get("code_verifier")))
                .isEqualTo(q.get("code_challenge"));
        assertThat(rows.get(c.getId()).getStatus()).isEqualTo(ConnectionStatus.CONNECTED);

        // Single use.
        assertThat(service.unboundService(state)).isEmpty();
        assertThat(service.complete(new OAuth2ConnectionService.CallbackRequest(state, "the-code", null, null, null)))
                .isEqualTo(OAuth2ConnectionService.Outcome.EXPIRED);
        assertThat(auth.requests("/token")).hasSize(1);
    }

    @Test
    @DisplayName("on Desktop a connection with no client of its own signs in through the built-in client; a typed one wins; never on a Home Server or Enterprise Server")
    void builtInClient() {
        // 2026-11-07: a creator signs in to YouTube with no Google Cloud project of their own (BuiltInOAuthClients).
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("proxyma.oauth.builtin.confluence.client-id", "builtin-1")
                .withProperty("proxyma.oauth.builtin.confluence.client-secret", "bs");
        service.setBuiltInClients(new BuiltInOAuthClients(env, false, false));
        ConnectorConnection c = connection(ConnectorConnection.LOCAL_OWNER, AuthKind.OAUTH2_CODE, Map.of());
        c.setSettingsJson(ConfigJson.write(Map.of()));
        repository.save(c);
        auth.on("POST", "/token").json("{'access_token':'at-1','refresh_token':'rt-1','expires_in':3600}");

        Map<String, String> q = query(service.begin(null, c.getId(), "http://127.0.0.1:4246/cb"));
        assertThat(q).containsEntry("client_id", "builtin-1");
        assertThat(service.complete(new OAuth2ConnectionService.CallbackRequest(q.get("state"), "the-code", null, null, null)))
                .isEqualTo(OAuth2ConnectionService.Outcome.CONNECTED);
        Map<String, String> form = query("http://x/?" + auth.last().body());
        assertThat(form).containsEntry("client_id", "builtin-1").containsEntry("client_secret", "bs");

        ConnectorConnection own = connection(ConnectorConnection.LOCAL_OWNER, AuthKind.OAUTH2_CODE, Map.of("clientSecret", "cs"));
        assertThat(query(service.begin(null, own.getId(), "http://127.0.0.1:4246/cb"))).as("the person's own client wins")
                .containsEntry("client_id", "client-1");

        assertThat(new BuiltInOAuthClients(env, false, true).available(ConnectionProtocol.CONFLUENCE)).as("Home Server").isFalse();
        assertThat(new BuiltInOAuthClients(env, true, false).available(ConnectionProtocol.CONFLUENCE)).as("Enterprise Server").isFalse();
        assertThat(new BuiltInOAuthClients(new org.springframework.mock.env.MockEnvironment(), false, false)
                .available(ConnectionProtocol.CONFLUENCE)).as("none set").isFalse();
        assertThat(new BuiltInOAuthClients(env, false, false).available(ConnectionProtocol.YOUTUBE)).as("another service's").isFalse();

        // Behind the add-ons it names: Proxyma's one Google project's quota serves the people it was made for.
        env.withProperty("proxyma.oauth.builtin.confluence.requires-addon", "youtube-assistant, constellations");
        BuiltInOAuthClients gated = new BuiltInOAuthClients(env, false, false);
        gated.setAddonsForTest(java.util.Set::of);
        assertThat(gated.available(ConnectionProtocol.CONFLUENCE)).as("no add-on in force").isFalse();
        gated.setAddonsForTest(() -> java.util.Set.of("constellations"));
        assertThat(gated.available(ConnectionProtocol.CONFLUENCE)).as("either one opens it").isTrue();
    }

    @Test
    @DisplayName("a browser sign-in expires after ten minutes, and is only the owner's to begin")
    void browserSignInExpiresAndIsTheOwners() {
        ConnectorConnection c = connection("ana", AuthKind.OAUTH2_CODE, Map.of("clientSecret", "cs"));
        assertThatThrownBy(() -> service.begin("bo", c.getId(), "http://h/cb")).isInstanceOf(IllegalArgumentException.class);

        String state = query(service.begin("ana", c.getId(), "http://h/cb")).get("state");
        now = now.plus(OAuth2ConnectionService.FLOW_LIFETIME).plusSeconds(1);
        assertThat(service.complete(new OAuth2ConnectionService.CallbackRequest(state, "code", null, null, null)))
                .isEqualTo(OAuth2ConnectionService.Outcome.EXPIRED);
        assertThat(auth.requests("/token")).isEmpty();
    }

    @Test
    @DisplayName("where there are accounts no sign-in is unbound: begin is refused and a bound flow still needs its cookie")
    void noUnboundFlowWithAccounts() {
        ConnectorConnection c = connection("ana", AuthKind.OAUTH2_CODE, Map.of("clientSecret", "cs"));
        OAuth2ConnectionService server = service(true);
        assertThatThrownBy(() -> server.begin("ana", c.getId(), "http://h/cb")).isInstanceOf(IllegalStateException.class);

        // A bound flow on a single-user edition is not reported unbound, and a missing cookie fails it.
        OAuth2ConnectionService.FlowStart bound = service.start("ana", c.getId(), "http://h/cb");
        String state = query(bound.authorizeUrl()).get("state");
        assertThat(service.unboundService(state)).isEmpty();
        assertThat(service.complete(new OAuth2ConnectionService.CallbackRequest(state, "code", null, null, null)))
                .isEqualTo(OAuth2ConnectionService.Outcome.FAILED);
        assertThat(server.unboundService(state)).isEmpty();
        assertThat(auth.requests("/token")).isEmpty();
    }

    @Test
    @DisplayName("an expired token is refreshed; a rotated refresh token is stored before the new access token is used")
    void refreshWithRotation() {
        ConnectorConnection c = connection(ConnectorConnection.LOCAL_OWNER, AuthKind.OAUTH2_CODE, Map.of(
                "accessToken", "old", "refreshToken", "rt-1",
                "expiresAt", String.valueOf(now.minusSeconds(10).getEpochSecond())));
        auth.on("POST", "/token").json("{'access_token':'at-2','refresh_token':'rt-2','expires_in':600}");

        String token = service.tokensFor(c.getId()).accessToken();

        assertThat(token).isEqualTo("at-2");
        assertThat(query("http://x/?" + auth.last().body())).containsEntry("grant_type", "refresh_token")
                .containsEntry("refresh_token", "rt-1");
        assertThat(stored(c.getId())).containsEntry("refreshToken", "rt-2").containsEntry("accessToken", "at-2");
        // A token still fresh is not refreshed again.
        assertThat(service.tokensFor(c.getId()).accessToken()).isEqualTo("at-2");
        assertThat(auth.requests("/token")).hasSize(1);
        // One the service refused is, once.
        var supplier = service.tokensFor(c.getId());
        supplier.invalidate();
        auth.reset();
        auth.on("POST", "/token").json("{'access_token':'at-3','expires_in':600}");
        assertThat(supplier.accessToken()).isEqualTo("at-3");
        assertThat(stored(c.getId())).containsEntry("refreshToken", "rt-2");
    }

    @Test
    @DisplayName("a refresh the service refuses marks the connection for a new sign-in, once, and says so")
    void refusedRefreshNeedsReconnect() {
        ConnectorConnection c = connection(ConnectorConnection.LOCAL_OWNER, AuthKind.OAUTH2_CODE, Map.of(
                "accessToken", "old", "refreshToken", "rt-1", "expiresAt", "1"));
        auth.on("POST", "/token").status(400).json("{'error':'invalid_grant'}");

        assertThatThrownBy(() -> service.tokensFor(c.getId()).accessToken())
                .isInstanceOf(ConnectorAuthException.class)
                .hasMessageContaining("Sign in again in Settings > Connections");
        assertThat(rows.get(c.getId()).getStatus()).isEqualTo(ConnectionStatus.NEEDS_RECONNECT);
    }

    @Test
    @DisplayName("client credentials: one token request, cached until shortly before it expires")
    void clientCredentials() {
        ConnectorConnection c = connection(ConnectorConnection.LOCAL_OWNER, AuthKind.OAUTH2_CLIENT, Map.of("clientSecret", "cs"));
        auth.on("POST", "/token").json("{'access_token':'app-1','expires_in':3600}");

        assertThat(service.tokensFor(c.getId()).accessToken()).isEqualTo("app-1");
        assertThat(service.tokensFor(c.getId()).accessToken()).isEqualTo("app-1");
        assertThat(auth.requests("/token")).hasSize(1);
        assertThat(query("http://x/?" + auth.last().body())).containsEntry("grant_type", "client_credentials")
                .containsEntry("client_id", "client-1").containsEntry("scope", "read");

        now = now.plusSeconds(3590);
        auth.reset();
        auth.on("POST", "/token").json("{'access_token':'app-2','expires_in':3600}");
        assertThat(service.tokensFor(c.getId()).accessToken()).isEqualTo("app-2");
    }

    @Test
    @DisplayName("a service account signs an RS256 assertion with its own key, for its token endpoint")
    void serviceAccountAssertion() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(pair.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n";
        String keyJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                "client_email", "svc@example.iam", "private_key", pem, "token_uri", auth.url("/token")));
        ConnectorConnection c = connection(ConnectorConnection.LOCAL_OWNER, AuthKind.SERVICE_ACCOUNT,
                Map.of("serviceAccountJson", keyJson));
        auth.on("POST", "/token").json("{'access_token':'sa-1','expires_in':3600}");

        assertThat(service.tokensFor(c.getId()).accessToken()).isEqualTo("sa-1");

        Map<String, String> form = query("http://x/?" + auth.last().body());
        assertThat(form).containsEntry("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
        String[] jwt = form.get("assertion").split("\\.");
        Signature verify = Signature.getInstance("SHA256withRSA");
        verify.initVerify(pair.getPublic());
        verify.update((jwt[0] + "." + jwt[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verify.verify(Base64.getUrlDecoder().decode(jwt[2]))).isTrue();
        String claims = new String(Base64.getUrlDecoder().decode(jwt[1]), StandardCharsets.UTF_8);
        assertThat(claims).contains("\"iss\":\"svc@example.iam\"").contains("\"aud\":\"" + auth.url("/token") + "\"");
    }

    @Test
    @DisplayName("a service account with domain-wide delegation acts as the connection's user, asking for the service's scopes (Google)")
    void serviceAccountActsAsItsSubject() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(pair.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n";
        String keyJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                "type", "service_account", "client_email", "indexer@project.iam.gserviceaccount.com",
                "private_key", pem, "token_uri", auth.url("/token")));
        ConnectorConnection c = connection(ConnectorConnection.LOCAL_OWNER, AuthKind.SERVICE_ACCOUNT,
                Map.of("serviceAccountJson", keyJson));
        c.setSettingsJson(ConfigJson.write(Map.of("subject", "ana@example.com")));
        auth.on("POST", "/token").json("{'access_token':'sa-ana','expires_in':3600,'token_type':'Bearer'}");

        assertThat(service.tokensFor(c.getId()).accessToken()).isEqualTo("sa-ana");
        // Cached until shortly before it expires: a second ask is not a second assertion.
        assertThat(service.tokensFor(c.getId()).accessToken()).isEqualTo("sa-ana");
        assertThat(auth.requests("/token")).hasSize(1);

        Map<String, String> form = query("http://x/?" + auth.last().body());
        assertThat(form).doesNotContainKeys("client_id", "client_secret");
        String claims = new String(Base64.getUrlDecoder().decode(form.get("assertion").split("\\.")[1]), StandardCharsets.UTF_8);
        assertThat(claims).contains("\"sub\":\"ana@example.com\"", "\"scope\":\"read\"",
                "\"iss\":\"indexer@project.iam.gserviceaccount.com\"");
    }

    @Test
    @DisplayName("a session is handed the method's declared secrets - never the sign-in's refresh token")
    void sessionsNeverSeeTheRefreshToken() {
        ConnectorConnectionService connections = new ConnectorConnectionService(repository, encryption,
                mock(ApplicationEventPublisher.class), mock(ai.proxyma.core.license.FeatureGateService.class),
                mock(org.springframework.beans.factory.ObjectProvider.class), false);
        connections.setCatalog(catalog);
        ConnectorConnection c = connection(ConnectorConnection.LOCAL_OWNER, AuthKind.OAUTH2_CODE, Map.of(
                "clientSecret", "cs", "accessToken", "at", "refreshToken", "rt"));

        ResolvedConnection resolved = connections.resolve(c.getId()).orElseThrow();

        assertThat(resolved.secrets()).containsExactly(Map.entry("clientSecret", "cs"));
        assertThat(resolved.settings()).containsEntry("clientId", "client-1");
        assertThat(resolved.authKind()).isEqualTo(AuthKind.OAUTH2_CODE);
    }

    private void useSpec(OAuth2Spec spec) {
        catalog = ConnectorCatalog.of(List.of(), List.of(plugin(spec)));
        service = service(false);
    }

    // A whole browser sign-in of c: start, then the callback with the code "the-code".
    private OAuth2ConnectionService.Outcome signIn(ConnectorConnection c) {
        OAuth2ConnectionService.FlowStart flow = service.start(null, c.getId(), "http://localhost/cb");
        return service.complete(new OAuth2ConnectionService.CallbackRequest(query(flow.authorizeUrl()).get("state"),
                "the-code", null, flow.browserBinding(), null));
    }

    @Test
    @DisplayName("a service that documents a JSON token request is sent one, with its client as Basic and its own headers (Notion)")
    void jsonTokenBody() throws Exception {
        useSpec(OAuth2Spec.builder(auth.url("/authorize"), auth.url("/token")).clientAuth(OAuth2Spec.ClientAuth.BASIC)
                .tokenBody(OAuth2Spec.TokenBody.JSON).tokenHeader("Notion-Version", "2026-03-11").build());
        ConnectorConnection c = connection(ConnectorConnection.LOCAL_OWNER, AuthKind.OAUTH2_CODE, Map.of("clientSecret", "cs"));
        auth.on("POST", "/token").bodyContaining("\"grant_type\":\"authorization_code\"")
                .json("{'access_token':'at-1','refresh_token':'rt-1','expires_in':1}");
        auth.on("POST", "/token").bodyContaining("\"grant_type\":\"refresh_token\"")
                .json("{'access_token':'at-2','refresh_token':'rt-2','expires_in':3600}");

        assertThat(signIn(c)).isEqualTo(OAuth2ConnectionService.Outcome.CONNECTED);
        var exchange = auth.last();
        assertThat(exchange.header("Content-Type")).startsWith("application/json");
        assertThat(exchange.header("Notion-Version")).isEqualTo("2026-03-11");
        assertThat(exchange.header("Authorization")).isEqualTo("Basic "
                + Base64.getEncoder().encodeToString("client-1:cs".getBytes(StandardCharsets.UTF_8)));
        var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(exchange.body());
        assertThat(body.path("code").asText()).isEqualTo("the-code");
        assertThat(body.has("client_secret")).isFalse();

        // The refresh goes the same way - expires_in 1 is inside the margin, so the next ask refreshes.
        assertThat(service.tokensFor(c.getId()).accessToken()).isEqualTo("at-2");
        assertThat(auth.last().header("Content-Type")).startsWith("application/json");
        assertThat(auth.last().body()).contains("\"refresh_token\":\"rt-1\"");
    }

    @Test
    @DisplayName("a 200 that says ok:false (Slack) is a failed sign-in, and a refused refresh, never an exception or a token")
    void okFalseIsARefusal() {
        ConnectorConnection c = connection(ConnectorConnection.LOCAL_OWNER, AuthKind.OAUTH2_CODE, Map.of("clientSecret", "cs"));
        auth.on("POST", "/token").json("{'ok':false,'error':'invalid_code'}");

        assertThat(signIn(c)).isEqualTo(OAuth2ConnectionService.Outcome.FAILED);
        assertThat(rows.get(c.getId()).getStatus()).isNull();
        assertThat(stored(c.getId())).doesNotContainKey("accessToken");

        ConnectorConnection signedIn = connection(ConnectorConnection.LOCAL_OWNER, AuthKind.OAUTH2_CODE, Map.of(
                "accessToken", "old", "refreshToken", "rt-1", "expiresAt", "1"));
        auth.reset();
        auth.on("POST", "/token").json("{'ok':false,'error':'invalid_refresh_token'}");
        assertThatThrownBy(() -> service.tokensFor(signedIn.getId()).accessToken())
                .isInstanceOf(ConnectorAuthException.class)
                .hasMessageContaining("Sign in again in Settings > Connections");
        assertThat(rows.get(signedIn.getId()).getStatus()).isEqualTo(ConnectionStatus.NEEDS_RECONNECT);
    }

    @Test
    @DisplayName("a refused consent and a failed exchange are remembered for that connection, leave it as it was, and a later success clears them")
    void refusedOrFailedSignInIsRemembered() {
        // The maintainer, 2026-11-09: the page waiting on a sign-in must know when the person said no or it failed.
        ConnectorConnection c = connection(ConnectorConnection.LOCAL_OWNER, AuthKind.OAUTH2_CODE, Map.of("clientSecret", "cs"));
        assertThat(service.lastFailure(c.getId())).isEmpty();

        OAuth2ConnectionService.FlowStart refused = service.start(null, c.getId(), "http://localhost/cb");
        assertThat(service.complete(new OAuth2ConnectionService.CallbackRequest(query(refused.authorizeUrl()).get("state"),
                null, "access_denied", refused.browserBinding(), null))).isEqualTo(OAuth2ConnectionService.Outcome.DENIED);
        assertThat(service.lastFailure(c.getId())).hasValueSatisfying(f -> assertThat(f.outcome()).isEqualTo("DENIED"));
        assertThat(rows.get(c.getId()).getStatus()).isNull();

        auth.on("POST", "/token").json("{'ok':false,'error':'invalid_code'}");
        assertThat(signIn(c)).isEqualTo(OAuth2ConnectionService.Outcome.FAILED);
        assertThat(service.lastFailure(c.getId())).hasValueSatisfying(f -> assertThat(f.outcome()).isEqualTo("FAILED"));

        auth.reset();
        auth.on("POST", "/token").json("{'access_token':'at-1','refresh_token':'rt-1','expires_in':3600}");
        assertThat(signIn(c)).isEqualTo(OAuth2ConnectionService.Outcome.CONNECTED);
        assertThat(service.lastFailure(c.getId())).isEmpty();

        // A callback that is not this connection's own flow - an unknown state - is remembered for nobody.
        assertThat(service.complete(new OAuth2ConnectionService.CallbackRequest("no-such-state", null, "access_denied",
                null, null))).isEqualTo(OAuth2ConnectionService.Outcome.EXPIRED);
        assertThat(service.lastFailure(c.getId())).isEmpty();
    }

    @Test
    @DisplayName("a client-credentials grant sends the parameters the service takes from the connection's settings, and no scope where it takes none (Box)")
    void clientCredentialsWithProviderParameters() {
        useSpec(OAuth2Spec.builder(auth.url("/authorize"), auth.url("/token")).scopes(List.of("root_readonly"))
                .clientGrantParam("box_subject_type", "subjectType").clientGrantParam("box_subject_id", "subjectId")
                .noClientGrantScopes().build());
        ConnectorConnection c = connection(ConnectorConnection.LOCAL_OWNER, AuthKind.OAUTH2_CLIENT, Map.of("clientSecret", "cs"));
        c.setSettingsJson(ConfigJson.write(Map.of("clientId", "client-1", "subjectType", "enterprise",
                "subjectId", "1234")));
        auth.on("POST", "/token").json("{'access_token':'ccg-1','expires_in':3600}");

        assertThat(service.tokensFor(c.getId()).accessToken()).isEqualTo("ccg-1");
        assertThat(query("http://x/?" + auth.last().body())).containsEntry("grant_type", "client_credentials")
                .containsEntry("box_subject_type", "enterprise").containsEntry("box_subject_id", "1234")
                .containsEntry("client_id", "client-1").doesNotContainKey("scope");

        // A setting the grant needs and the connection lacks is said before the service is asked.
        ConnectorConnection missing = connection(ConnectorConnection.LOCAL_OWNER, AuthKind.OAUTH2_CLIENT,
                Map.of("clientSecret", "cs"));
        auth.reset();
        assertThatThrownBy(() -> service.tokensFor(missing.getId()).accessToken())
                .isInstanceOf(ConnectorAuthException.class).hasMessageContaining("missing a setting its sign-in needs (subjectType)");
        assertThat(auth.requests()).isEmpty();
    }

    @Test
    @DisplayName("on Enterprise Server a client-credentials grant uses the client typed on the connection, not the deployment's sign-in app")
    void clientCredentialsUseTheConnectionsOwnClient() {
        ConnectorConnection c = connection("ana", AuthKind.OAUTH2_CLIENT, Map.of("clientSecret", "cs"));
        auth.on("POST", "/token").json("{'access_token':'app-1','expires_in':3600}");

        assertThat(service(true).tokensFor(c.getId()).accessToken()).isEqualTo("app-1");
        assertThat(query("http://x/?" + auth.last().body())).containsEntry("client_id", "client-1")
                .containsEntry("client_secret", "cs");
    }

    @Test
    @DisplayName("the token answer's fields a connector needs are kept beside the grant, never in the secret, replaced by a refresh and gone with a sign-out (Salesforce instance_url)")
    void grantFieldsKeptOnTheConnection() {
        useSpec(OAuth2Spec.builder(auth.url("/authorize"), auth.url("/token")).keepGrantField("instance_url").build());
        ConnectorConnection c = connection(ConnectorConnection.LOCAL_OWNER, AuthKind.OAUTH2_CODE, Map.of("clientSecret", "cs"));
        auth.on("POST", "/token").bodyContaining("grant_type=authorization_code").json(
                "{'access_token':'at-1','refresh_token':'rt-1','expires_in':1,"
                        + "'instance_url':'https://acme--uat.sandbox.my.salesforce.com','id':'https://x/id/1','signature':'s'}");
        auth.on("POST", "/token").bodyContaining("grant_type=refresh_token").json(
                "{'access_token':'at-2','expires_in':3600,'instance_url':'https://acme--uat2.sandbox.my.salesforce.com'}");

        assertThat(signIn(c)).isEqualTo(OAuth2ConnectionService.Outcome.CONNECTED);
        // Only what the spec names - not the identity URL or the signature beside it.
        assertThat(ConfigJson.parse(rows.get(c.getId()).getAccountJson()))
                .containsExactly(Map.entry("instance_url", "https://acme--uat.sandbox.my.salesforce.com"));
        assertThat(stored(c.getId())).doesNotContainKey("instance_url");

        service.tokensFor(c.getId()).accessToken();
        assertThat(ConfigJson.parse(rows.get(c.getId()).getAccountJson()))
                .containsEntry("instance_url", "https://acme--uat2.sandbox.my.salesforce.com");

        service.signOut(null, c.getId());
        assertThat(rows.get(c.getId()).getAccountJson()).isNull();
    }

    // -- 2026-11-08: the built-in YouTube sign-in's review ----------------------------------------

    // The test service with a spec, a consent rule and a price per request.
    private void useYouTubeLike(OAuth2Spec spec, java.util.function.BiFunction<java.util.Set<String>, Boolean,
            Optional<String>> shortfall, int units) {
        ConnectionPlugin base = plugin(spec);
        catalog = ConnectorCatalog.of(List.of(), List.of(new ConnectionPlugin() {
            @Override public ProtocolDescriptor descriptor() { return base.descriptor(); }
            @Override public ConnectionTestResult test(ResolvedCredentials c, ConnectorHttp http) { return ConnectionTestResult.ok("ok"); }
            @Override public OAuth2Spec oauth2() { return spec; }
            @Override public Optional<String> consentShortfall(java.util.Set<String> granted, boolean write) {
                return shortfall.apply(granted, write);
            }
            @Override public int requestUnits(org.springframework.http.HttpMethod method, URI uri) { return units; }
            @Override public boolean identityRequest(org.springframework.http.HttpMethod method, URI uri) {
                return uri.getRawPath().endsWith("/channels");
            }
        }));
        service = service(false);
        service.setRevokerForTest(Runnable::run);
    }

    private OAuth2Spec youTubeSpec() {
        return OAuth2Spec.builder(auth.url("/authorize"), auth.url("/token")).scopes(List.of("read", "numbers"))
                .writeScopes(List.of("write")).writeReplaces(List.of("read")).keepGrantField("scope")
                .revokeUrl(auth.url("/revoke")).build();
    }

    // Needs the read scope, or with Write on the write scope (as YouTubeConnectionPlugin's rule).
    private static Optional<String> needsWhatItAsked(java.util.Set<String> granted, boolean write) {
        if (!granted.contains("read") && !granted.contains("write")) return Optional.of("Cannot read.");
        if (write && !granted.contains("write")) return Optional.of("Cannot post.");
        return Optional.empty();
    }

    @Test
    @DisplayName("with Write on, the sign-in asks the write scope without the read scope it covers")
    void writeScopeReplacesRead() {
        useYouTubeLike(youTubeSpec(), (g, w) -> Optional.empty(), 0);
        ConnectorConnection reader = connection(ConnectorConnection.LOCAL_OWNER, AuthKind.OAUTH2_CODE, Map.of("clientSecret", "cs"));
        ConnectorConnection writer = connection(ConnectorConnection.LOCAL_OWNER, AuthKind.OAUTH2_CODE, Map.of("clientSecret", "cs"));
        writer.setAllowWrite(true);

        assertThat(query(service.start(null, reader.getId(), "http://localhost/cb").authorizeUrl())).containsEntry("scope", "read numbers");
        assertThat(query(service.start(null, writer.getId(), "http://localhost/cb").authorizeUrl())).containsEntry("scope", "numbers write");
    }

    @Test
    @DisplayName("granular consent: a grant missing what the connection needs is kept but needs a new sign-in, with the reason; a whole one is connected")
    void partialConsentNeedsSignIn() {
        useYouTubeLike(youTubeSpec(), OAuth2ConnectionServiceTest::needsWhatItAsked, 0);
        ConnectorConnection c = connection(ConnectorConnection.LOCAL_OWNER, AuthKind.OAUTH2_CODE, Map.of("clientSecret", "cs"));
        c.setAllowWrite(true);
        // The person unticked the write scope on the consent screen.
        auth.on("POST", "/token").json("{'access_token':'at-1','refresh_token':'rt-1','expires_in':3600,'scope':'numbers'}");

        assertThat(signIn(c)).isEqualTo(OAuth2ConnectionService.Outcome.INCOMPLETE);
        assertThat(rows.get(c.getId()).getStatus()).isEqualTo(ConnectionStatus.NEEDS_RECONNECT);
        assertThat(rows.get(c.getId()).getStatusDetail()).isEqualTo("Cannot read.");
        assertThat(stored(c.getId())).containsEntry("accessToken", "at-1").containsEntry("scope", "numbers");
        assertThat(rows.get(c.getId()).getConnectedAt()).isNotNull();

        auth.reset();
        auth.on("POST", "/token").json("{'access_token':'at-2','refresh_token':'rt-2','expires_in':3600,'scope':'read numbers'}");
        assertThat(signIn(c)).as("reads, cannot post").isEqualTo(OAuth2ConnectionService.Outcome.INCOMPLETE);
        assertThat(rows.get(c.getId()).getStatusDetail()).isEqualTo("Cannot post.");

        // A later answer naming no scopes is judged on nothing - never on the scope the last grant left behind.
        auth.reset();
        auth.on("POST", "/token").json("{'access_token':'at-2b','refresh_token':'rt-2b','expires_in':3600}");
        assertThat(signIn(c)).isEqualTo(OAuth2ConnectionService.Outcome.CONNECTED);

        auth.reset();
        auth.on("POST", "/token").json("{'access_token':'at-3','refresh_token':'rt-3','expires_in':3600,'scope':'numbers write'}");
        assertThat(signIn(c)).isEqualTo(OAuth2ConnectionService.Outcome.CONNECTED);
        assertThat(rows.get(c.getId()).getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(rows.get(c.getId()).getStatusDetail()).isNull();

        // An answer that names no scopes is not second-guessed.
        auth.reset();
        auth.on("POST", "/token").json("{'access_token':'at-4','refresh_token':'rt-4','expires_in':3600}");
        assertThat(signIn(c)).isEqualTo(OAuth2ConnectionService.Outcome.CONNECTED);
    }

    @Test
    @DisplayName("signing out revokes the grant at the service - the refresh token, once - and forgets it here")
    void signOutRevokes() {
        useYouTubeLike(youTubeSpec(), (g, w) -> Optional.empty(), 0);
        ConnectorConnection c = connection(ConnectorConnection.LOCAL_OWNER, AuthKind.OAUTH2_CODE, Map.of("clientSecret", "cs"));
        auth.on("POST", "/token").json("{'access_token':'at-1','refresh_token':'rt-1','expires_in':3600}");
        auth.on("POST", "/revoke").json("{}");
        signIn(c);

        service.signOut(null, c.getId());

        assertThat(auth.requests("/revoke")).hasSize(1);
        assertThat(query("http://x/?" + auth.requests("/revoke").get(0).body())).containsExactly(Map.entry("token", "rt-1"));
        assertThat(stored(c.getId())).doesNotContainKeys("accessToken", "refreshToken");
    }

    @Test
    @DisplayName("a revocation the service refuses never stops the sign-out; a service with no revoke address is not asked")
    void revocationIsBestEffort() {
        useYouTubeLike(youTubeSpec(), (g, w) -> Optional.empty(), 0);
        ConnectorConnection c = connection(ConnectorConnection.LOCAL_OWNER, AuthKind.OAUTH2_CODE, Map.of("clientSecret", "cs"));
        auth.on("POST", "/token").json("{'access_token':'at-1','refresh_token':'rt-1','expires_in':3600}");
        auth.on("POST", "/revoke").status(503).json("{}");
        signIn(c);
        service.signOut(null, c.getId());
        assertThat(rows.get(c.getId()).getSecret()).doesNotContain("rt-1");

        useSpec(OAuth2Spec.builder(auth.url("/authorize"), auth.url("/token")).build());
        ConnectorConnection plain = connection(ConnectorConnection.LOCAL_OWNER, AuthKind.OAUTH2_CODE, Map.of("clientSecret", "cs"));
        auth.reset();
        auth.on("POST", "/token").json("{'access_token':'at-1','refresh_token':'rt-1','expires_in':3600}");
        signIn(plain);
        service.signOut(null, plain.getId());
        assertThat(auth.requests("/revoke")).isEmpty();
    }

    @Test
    @DisplayName("the built-in client's daily share: its requests are counted and one past the budget is answered as a spent quota, unsent; a person's own client is never counted")
    void builtInDailyShare() {
        useYouTubeLike(youTubeSpec(), (g, w) -> Optional.empty(), 50);
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("proxyma.oauth.builtin.confluence.client-id", "builtin-1")
                .withProperty("proxyma.oauth.builtin.confluence.daily-units", "120");
        service.setBuiltInClients(new BuiltInOAuthClients(env, false, false));
        service.setBuiltInQuota(new BuiltInQuota(env, null, Clock.fixed(now, ZoneOffset.UTC)));
        ConnectorConnection builtIn = connection(ConnectorConnection.LOCAL_OWNER, AuthKind.OAUTH2_CODE, Map.of());
        builtIn.setSettingsJson(ConfigJson.write(Map.of()));
        repository.save(builtIn);
        ConnectorConnection own = connection(ConnectorConnection.LOCAL_OWNER, AuthKind.OAUTH2_CODE, Map.of("clientSecret", "cs"));
        URI reply = URI.create("https://www.googleapis.com/youtube/v3/comments");
        org.springframework.http.HttpMethod post = org.springframework.http.HttpMethod.POST;

        var tokens = service.tokensFor(builtIn.getId());
        assertThat(tokens.refuseBeforeSending(post, reply)).isNull();
        assertThat(tokens.refuseBeforeSending(post, reply)).isNull();
        var refused = tokens.refuseBeforeSending(post, reply);
        assertThat(refused).isNotNull();
        assertThat(refused.status()).isEqualTo(403);
        assertThat(ai.proxyma.core.connector.spi.GoogleError.reasons(refused)).contains("quotaExceeded", BuiltInQuota.REASON);

        for (int i = 0; i < 5; i++) {
            assertThat(service.tokensFor(own.getId()).refuseBeforeSending(post, reply))
                    .as("the person's own project, their own quota").isNull();
        }
        // On a server with accounts there is no built-in client, so nothing is counted either.
        OAuth2ConnectionService server = service(true);
        server.setBuiltInQuota(new BuiltInQuota(env, null, Clock.fixed(now, ZoneOffset.UTC)));
        assertThat(server.tokensFor(builtIn.getId()).refuseBeforeSending(post, reply)).isNull();
    }

    @Test
    @DisplayName("past the spent share, a Test asking whose channel this is goes through a small reserve; anything else never does (2026-11-11)")
    void identityFromTheReserve() {
        useYouTubeLike(youTubeSpec(), (g, w) -> Optional.empty(), 1);
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("proxyma.oauth.builtin.confluence.client-id", "builtin-1")
                .withProperty("proxyma.oauth.builtin.confluence.daily-units", "1");
        service.setBuiltInClients(new BuiltInOAuthClients(env, false, false));
        service.setBuiltInQuota(new BuiltInQuota(env, null, Clock.fixed(now, ZoneOffset.UTC)));
        ConnectorConnection builtIn = connection(ConnectorConnection.LOCAL_OWNER, AuthKind.OAUTH2_CODE, Map.of());
        builtIn.setSettingsJson(ConfigJson.write(Map.of()));
        repository.save(builtIn);
        URI channel = URI.create("https://www.googleapis.com/youtube/v3/channels?part=snippet&mine=true");
        URI threads = URI.create("https://www.googleapis.com/youtube/v3/commentThreads?part=snippet");
        org.springframework.http.HttpMethod get = org.springframework.http.HttpMethod.GET;
        var tokens = service.tokensFor(builtIn.getId());
        assertThat(tokens.refuseBeforeSending(get, threads)).as("the day's share").isNull();
        assertThat(tokens.refuseBeforeSending(get, threads)).as("spent").isNotNull();
        assertThat(OAuth2ConnectionService.asIdentityCheck(() -> tokens.refuseBeforeSending(get, channel)))
                .as("a Test asking whose channel: the reserve").isNull();
        assertThat(tokens.refuseBeforeSending(get, channel)).as("the same read outside a Test: never the reserve").isNotNull();
        assertThat(OAuth2ConnectionService.asIdentityCheck(() -> tokens.refuseBeforeSending(get, threads)))
                .as("a Test reading anything else: never the reserve").isNotNull();
        for (int i = 1; i < BuiltInQuota.IDENTITY_RESERVE; i++) {
            assertThat(OAuth2ConnectionService.asIdentityCheck(() -> tokens.refuseBeforeSending(get, channel))).isNull();
        }
        assertThat(OAuth2ConnectionService.asIdentityCheck(() -> tokens.refuseBeforeSending(get, channel)))
                .as("the reserve spent too").isNotNull();
    }

    @Test
    @DisplayName("a connection signed in through the built-in client stops when its add-on lapses - refused unsent, with why; a person's own client goes on (2026-11-11 review)")
    void builtInClientOffAfterTheAddOn() {
        useYouTubeLike(youTubeSpec(), (g, w) -> Optional.empty(), 1);
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("proxyma.oauth.builtin.confluence.client-id", "builtin-1")
                .withProperty("proxyma.oauth.builtin.confluence.requires-addon", "youtube-assistant");
        BuiltInOAuthClients clients = new BuiltInOAuthClients(env, false, false);
        java.util.concurrent.atomic.AtomicReference<java.util.Set<String>> inForce =
                new java.util.concurrent.atomic.AtomicReference<>(java.util.Set.of("youtube-assistant"));
        clients.setAddonsForTest(inForce::get);
        service.setBuiltInClients(clients);
        service.setBuiltInQuota(new BuiltInQuota(env, null, Clock.fixed(now, ZoneOffset.UTC)));
        ConnectorConnection builtIn = connection(ConnectorConnection.LOCAL_OWNER, AuthKind.OAUTH2_CODE, Map.of());
        builtIn.setSettingsJson(ConfigJson.write(Map.of()));
        repository.save(builtIn);
        ConnectorConnection own = connection(ConnectorConnection.LOCAL_OWNER, AuthKind.OAUTH2_CODE, Map.of("clientSecret", "cs"));
        URI threads = URI.create("https://www.googleapis.com/youtube/v3/commentThreads?part=snippet");
        org.springframework.http.HttpMethod get = org.springframework.http.HttpMethod.GET;

        assertThat(service.tokensFor(builtIn.getId()).refuseBeforeSending(get, threads)).as("add-on in force").isNull();
        inForce.set(java.util.Set.of());
        var off = service.tokensFor(builtIn.getId()).refuseBeforeSending(get, threads);
        assertThat(off).isNotNull();
        assertThat(off.status()).isEqualTo(403);
        assertThat(off.text()).contains("only while its add-on is active");
        assertThat(service.tokensFor(own.getId()).refuseBeforeSending(get, threads)).as("their own client").isNull();
    }
}
