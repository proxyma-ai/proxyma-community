package ai.proxyma.core.settings.connection;

import ai.proxyma.contracts.license.LicenseTier;
import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.spi.AuthKind;
import ai.proxyma.core.connector.spi.AuthMethod;
import ai.proxyma.core.connector.spi.ConfigJson;
import ai.proxyma.core.connector.spi.ConnectionPlugin;
import ai.proxyma.core.connector.spi.ConnectionTestResult;
import ai.proxyma.core.connector.spi.ConnectorCatalog;
import ai.proxyma.core.connector.spi.ConnectorHttp;
import ai.proxyma.core.connector.spi.FieldSpec;
import ai.proxyma.core.connector.spi.OAuth2Spec;
import ai.proxyma.core.connector.spi.ProtocolDescriptor;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.license.Features;
import ai.proxyma.core.settings.connection.ConnectorConnectionService.ConnectionInput;
import ai.proxyma.core.settings.service.CredentialEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Editing the OAuth client of a connection that signs in through the browser (2026-11-03: the maintainer
 * deleted his Google Cloud OAuth client, made a new one and pasted it into his YouTube connections).
 *
 * <p>The edit is stored - the client id as a setting, the client secret encrypted and never in the view -
 * and a blank secret keeps the stored one. And because the stored grant was issued to the OLD client, a
 * changed client id, client secret or address forgets the sign-in: the connection reads as not signed
 * in, so the dialog offers the sign-in again, instead of a refresh the new client refuses at the next
 * sync. An edit that changes none of them - a rename, the Read switch, the same secret pasted again -
 * keeps it.
 */
class OAuthClientEditTest {

    private final Map<UUID, ConnectorConnection> rows = new LinkedHashMap<>();
    private ConnectorConnectionService service;
    private CredentialEncryptionService encryption;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ConnectorConnectionRepository repository = mock(ConnectorConnectionRepository.class);
        when(repository.save(any(ConnectorConnection.class))).thenAnswer(inv -> {
            ConnectorConnection c = inv.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            rows.put(c.getId(), c);
            return c;
        });
        when(repository.findById(any(UUID.class))).thenAnswer(inv -> Optional.ofNullable(rows.get(inv.<UUID>getArgument(0))));
        when(repository.findByIdAndOwnerId(any(UUID.class), anyString())).thenAnswer(inv ->
                Optional.ofNullable(rows.get(inv.<UUID>getArgument(0))).filter(c -> c.getOwnerId().equals(inv.getArgument(1))));
        when(repository.findByOwnerIdAndProtocolOrderByCreatedAtAsc(anyString(), any())).thenReturn(List.of());
        when(repository.findByOwnerIdAndNameIgnoreCase(anyString(), anyString())).thenReturn(Optional.empty());
        encryption = mock(CredentialEncryptionService.class);
        when(encryption.encrypt(anyString())).thenAnswer(inv -> "ENC(" + inv.getArgument(0) + ")");
        when(encryption.decrypt(anyString())).thenAnswer(inv -> {
            String v = inv.getArgument(0);
            return v.substring(4, v.length() - 1);
        });
        FeatureGateService gate = mock(FeatureGateService.class);
        when(gate.getFeatures()).thenReturn(Features.forTier(LicenseTier.COMMUNITY));
        service = new ConnectorConnectionService(repository, encryption, mock(ApplicationEventPublisher.class), gate,
                mock(ObjectProvider.class), false);

        // YouTube's own shape (YouTubeConnectionPlugin): a browser sign-in through the owner's OAuth client.
        ProtocolDescriptor d = ProtocolDescriptor.builder(ConnectionProtocol.YOUTUBE)
                .displayName("YouTube")
                .auth(AuthMethod.of(AuthKind.OAUTH2_CODE, "Sign in with Google",
                        FieldSpec.text("clientId", "Client ID"),
                        FieldSpec.secret("clientSecret", "Client secret")))
                .baseUrl(FieldSpec.url("baseUrl", "API URL").defaultValue("https://www.googleapis.com").asAdvanced())
                .sourceTypes(ConnectorType.YOUTUBE_CHANNEL)
                .supportsWrite()
                .build();
        OAuth2Spec spec = OAuth2Spec.builder("https://accounts.example/authorize", "https://accounts.example/token")
                .scopes(List.of("read")).build();
        ConnectionPlugin plugin = new ConnectionPlugin() {
            @Override public ProtocolDescriptor descriptor() { return d; }
            @Override public OAuth2Spec oauth2() { return spec; }
            @Override public ConnectionTestResult test(ResolvedCredentials c, ConnectorHttp http) { return ConnectionTestResult.ok("ok"); }
        };
        service.setCatalog(ConnectorCatalog.of(List.of(), List.of(plugin)));
    }

    private static ConnectionInput edit(String name, String baseUrl, Map<String, String> fields, Boolean allowRead) {
        return new ConnectionInput(name, null, baseUrl, null, null, false, null, allowRead, null, null,
                AuthKind.OAUTH2_CODE, fields);
    }

    // A connection with the old client, signed in: the grant the callback stores beside the client secret.
    private UUID signedIn() {
        UUID id = service.create(null, new ConnectionInput("Channel", ConnectionProtocol.YOUTUBE, null, null, null,
                false, null, null, null, null, AuthKind.OAUTH2_CODE,
                Map.of("clientId", "old-client.apps.example", "clientSecret", "old-secret"))).id();
        ConnectorConnection row = rows.get(id);
        Map<String, String> secret = new LinkedHashMap<>(ConfigJson.parse(encryption.decrypt(row.getSecret())));
        secret.put(OAuth2ConnectionService.ACCESS_TOKEN, "access-1");
        secret.put(OAuth2ConnectionService.REFRESH_TOKEN, "refresh-1");
        secret.put(OAuth2ConnectionService.EXPIRES_AT, "4102444800");
        row.setSecret(encryption.encrypt(ConfigJson.write(secret)));
        row.setStatus(ConnectionStatus.CONNECTED);
        row.setConnectedAt(Instant.parse("2026-11-01T00:00:00Z"));
        row.setAccountJson("{\"name\":\"My channel\"}");
        assertThat(service.get(null, id).secretConfigured()).as("signed in").isTrue();
        return id;
    }

    private Map<String, String> storedSecret(UUID id) {
        return ConfigJson.parse(encryption.decrypt(rows.get(id).getSecret()));
    }

    @Test
    @DisplayName("a new client id and secret are stored - the secret encrypted, never in the view - and the sign-in is forgotten")
    void newClientIsStoredAndForgetsTheSignIn() {
        UUID id = signedIn();

        ConnectorConnectionView view = service.update(null, id, edit("Channel", "https://www.googleapis.com",
                Map.of("clientId", "new-client.apps.example", "clientSecret", "new-secret"), true));

        assertThat(ConfigJson.parse(rows.get(id).getSettingsJson())).containsEntry("clientId", "new-client.apps.example");
        assertThat(rows.get(id).getSecret()).startsWith("ENC(").doesNotContain("refresh-1");
        assertThat(storedSecret(id)).containsExactly(Map.entry("clientSecret", "new-secret"));
        // The page reads which secrets are set, never one of them.
        assertThat(view.settings()).containsEntry("clientId", "new-client.apps.example");
        assertThat(view.secretsConfigured()).containsExactly("clientSecret");
        assertThat(view.secretMasked()).isNull();
        assertThat(view.toString()).doesNotContain("new-secret");
        // Not signed in any more: the dialog offers the sign-in again.
        assertThat(view.secretConfigured()).isFalse();
        assertThat(view.status()).isNull();
        assertThat(rows.get(id).getConnectedAt()).isNull();
        assertThat(rows.get(id).getAccountJson()).isNull();
    }

    @Test
    @DisplayName("the client id alone, each saved on its own as the dialog does, forgets the sign-in; a blank secret keeps the stored one")
    void clientIdAloneForgetsAndBlankSecretKeeps() {
        UUID id = signedIn();

        service.update(null, id, edit("Channel", "https://www.googleapis.com",
                Map.of("clientId", "new-client.apps.example"), true));

        assertThat(storedSecret(id)).containsExactly(Map.entry("clientSecret", "old-secret"));
        assertThat(rows.get(id).getStatus()).isNull();

        // The secret's own save, later: a blank one is never a change.
        service.update(null, id, edit("Channel", "https://www.googleapis.com",
                Map.of("clientId", "new-client.apps.example", "clientSecret", ""), true));
        assertThat(storedSecret(id)).containsExactly(Map.entry("clientSecret", "old-secret"));
    }

    @Test
    @DisplayName("a changed client secret alone forgets the sign-in")
    void clientSecretAloneForgets() {
        UUID id = signedIn();

        service.update(null, id, edit("Channel", null, Map.of("clientSecret", "new-secret"), null));

        assertThat(storedSecret(id)).containsExactly(Map.entry("clientSecret", "new-secret"));
        assertThat(service.get(null, id).secretConfigured()).isFalse();
    }

    @Test
    @DisplayName("a changed address forgets the sign-in - a token is never sent where it was not issued for")
    void changedAddressForgets() {
        UUID id = signedIn();

        service.update(null, id, edit("Channel", "https://youtube.test.example", Map.of(), null));

        assertThat(storedSecret(id)).doesNotContainKeys(OAuth2ConnectionService.ACCESS_TOKEN, OAuth2ConnectionService.REFRESH_TOKEN);
        assertThat(rows.get(id).getStatus()).isNull();
    }

    @Test
    @DisplayName("an edit that changes no part of the client - a rename, the Read switch, the same secret again - keeps the sign-in")
    void unrelatedEditKeeps() {
        UUID id = signedIn();

        service.update(null, id, edit("My channel", "https://www.googleapis.com",
                Map.of("clientId", "old-client.apps.example", "clientSecret", "old-secret"), false));

        assertThat(storedSecret(id)).containsEntry(OAuth2ConnectionService.REFRESH_TOKEN, "refresh-1")
                .containsEntry("clientSecret", "old-secret");
        assertThat(rows.get(id).getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(service.get(null, id).secretConfigured()).isTrue();
        assertThat(rows.get(id).getName()).isEqualTo("My channel");
        assertThat(rows.get(id).isAllowRead()).isFalse();
    }
}
