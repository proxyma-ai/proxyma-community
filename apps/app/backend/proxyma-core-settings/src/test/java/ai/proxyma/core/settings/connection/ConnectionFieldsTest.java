package ai.proxyma.core.settings.connection;

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
import ai.proxyma.core.connector.spi.ProtocolDescriptor;
import ai.proxyma.core.connector.spi.ResolvedCredentials;
import ai.proxyma.contracts.license.LicenseTier;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.license.Features;
import ai.proxyma.core.settings.service.CredentialEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

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
 * A connection's fields as its service's descriptor declares them: several secrets kept as ONE
 * encrypted object, settings kept apart from them, a blank secret on an edit keeping what is stored,
 * and the view naming which secrets are set without ever carrying one.
 */
class ConnectionFieldsTest {

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

        ProtocolDescriptor d = ProtocolDescriptor.builder(ConnectionProtocol.CONFLUENCE)
                .displayName("Keyed")
                .auth(AuthMethod.of(AuthKind.KEY_PAIR, "Key pair",
                        FieldSpec.text("keyId", "Key id").asRequired(),
                        FieldSpec.secret("privateKey", "Private key"),
                        FieldSpec.secret("passphrase", "Passphrase")))
                .baseUrl(FieldSpec.url("baseUrl", "API URL").defaultValue("https://api.keyed.example"))
                .sourceTypes(ConnectorType.CONFLUENCE)
                .build();
        ConnectionPlugin plugin = new ConnectionPlugin() {
            @Override public ProtocolDescriptor descriptor() { return d; }
            @Override public ConnectionTestResult test(ResolvedCredentials c, ConnectorHttp http) { return ConnectionTestResult.ok("ok"); }
        };
        service.setCatalog(ConnectorCatalog.of(List.of(), List.of(plugin)));
    }

    private ConnectorConnectionService.ConnectionInput input(Map<String, String> fields) {
        return new ConnectorConnectionService.ConnectionInput("Keyed", ConnectionProtocol.CONFLUENCE, null, null, null,
                false, null, null, null, null, AuthKind.KEY_PAIR, fields);
    }

    @Test
    @DisplayName("several secrets are one encrypted object; settings sit beside them; the default address is taken")
    void severalSecretsAreOneObject() {
        ConnectorConnectionView view = service.create(null, input(Map.of("keyId", "k-1", "privateKey", "PRIV",
                "passphrase", "pass")));
        ConnectorConnection row = rows.get(view.id());

        assertThat(row.getSecret()).startsWith("ENC(");
        assertThat(ConfigJson.parse(encryption.decrypt(row.getSecret())))
                .containsExactly(Map.entry("privateKey", "PRIV"), Map.entry("passphrase", "pass"));
        assertThat(ConfigJson.parse(row.getSettingsJson())).containsExactly(Map.entry("keyId", "k-1"));
        assertThat(row.getBaseUrl()).isEqualTo("https://api.keyed.example");
        // The view says which are set and shows none of them.
        assertThat(view.secretsConfigured()).containsExactly("privateKey", "passphrase");
        assertThat(view.secretMasked()).isNull();
        assertThat(view.settings()).containsEntry("keyId", "k-1");
        assertThat(view.authMethod()).isEqualTo("KEY_PAIR");

        ResolvedConnection resolved = service.resolve(view.id()).orElseThrow();
        assertThat(resolved.secrets()).containsEntry("privateKey", "PRIV").containsEntry("passphrase", "pass");
        assertThat(resolved.settings()).containsEntry("keyId", "k-1");
    }

    @Test
    @DisplayName("an edit that sends a secret blank keeps it; one that sends a new value replaces only that one")
    void blankSecretsAreKept() {
        UUID id = service.create(null, input(Map.of("keyId", "k-1", "privateKey", "PRIV", "passphrase", "pass"))).id();

        service.update(null, id, input(Map.of("privateKey", "", "passphrase", "new-pass")));

        assertThat(ConfigJson.parse(encryption.decrypt(rows.get(id).getSecret())))
                .containsEntry("privateKey", "PRIV").containsEntry("passphrase", "new-pass");
        assertThat(ConfigJson.parse(rows.get(id).getSettingsJson())).containsEntry("keyId", "k-1");
    }

    @Test
    @DisplayName("a setting the method requires is required, and a method the service does not offer is refused")
    void settingsAreValidated() {
        assertThatThrownBy(() -> service.create(null, input(Map.of("privateKey", "PRIV"))))
                .hasMessage("Key id is required.");
        assertThatThrownBy(() -> service.create(null, new ConnectorConnectionService.ConnectionInput("Keyed",
                ConnectionProtocol.CONFLUENCE, null, null, null, false, null, null, null, null, AuthKind.BASIC, Map.of())))
                .hasMessage("Keyed does not sign in that way.");
    }
}
