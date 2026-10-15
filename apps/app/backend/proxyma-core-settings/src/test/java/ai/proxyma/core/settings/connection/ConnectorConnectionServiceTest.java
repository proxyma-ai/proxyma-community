package ai.proxyma.core.settings.connection;

import ai.proxyma.core.connector.ConnectionProtocol;

import ai.proxyma.contracts.license.LicenseTier;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.license.Features;
import ai.proxyma.core.settings.connection.ConnectorConnectionService.ConnectionInput;
import ai.proxyma.core.settings.connection.ConnectorConnectionService.InvalidConnectionException;
import ai.proxyma.core.settings.service.CredentialEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A user's connections - where every Jira, Confluence, Bitbucket and SVN token now lives.
 *
 * <p>Security tests wearing behavior clothes, like the provider ones they mirror. The rules pinned
 * here are the ones whose failure is silent: a token readable by the wrong person, a token written
 * in clear text (SEC-31's shape), a token erased by an unrelated edit, and a connection deleted out
 * from under sources that still sync through it.
 */
class ConnectorConnectionServiceTest {

    private static final String ANA = "ana";
    private static final String BO = "bo";

    private final Map<UUID, ConnectorConnection> rows = new LinkedHashMap<>();
    private ConnectorConnectionRepository repository;
    private ConnectionUsage usage;
    private LicenseTier tier;
    private ConnectorConnectionService service;

    @BeforeEach
    void setUp() {
        tier = LicenseTier.COMMUNITY;
        repository = mock(ConnectorConnectionRepository.class);
        when(repository.save(any(ConnectorConnection.class))).thenAnswer(inv -> {
            ConnectorConnection c = inv.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            rows.put(c.getId(), c);
            return c;
        });
        doAnswer(inv -> rows.remove(inv.<ConnectorConnection>getArgument(0).getId()))
                .when(repository).delete(any(ConnectorConnection.class));
        when(repository.findById(any(UUID.class))).thenAnswer(inv -> Optional.ofNullable(rows.get(inv.<UUID>getArgument(0))));
        when(repository.findByIdAndOwnerId(any(UUID.class), anyString())).thenAnswer(inv ->
                Optional.ofNullable(rows.get(inv.<UUID>getArgument(0))).filter(c -> c.getOwnerId().equals(inv.getArgument(1))));
        when(repository.findByOwnerIdOrderByNameAsc(anyString())).thenAnswer(inv -> rows.values().stream()
                .filter(c -> c.getOwnerId().equals(inv.getArgument(0))).sorted(Comparator.comparing(ConnectorConnection::getName)).toList());
        when(repository.findByOwnerIdAndProtocolOrderByCreatedAtAsc(anyString(), any())).thenAnswer(inv -> rows.values().stream()
                .filter(c -> c.getOwnerId().equals(inv.getArgument(0)) && c.getProtocol() == inv.getArgument(1)).toList());
        when(repository.findByOwnerIdAndNameIgnoreCase(anyString(), anyString())).thenAnswer(inv -> rows.values().stream()
                .filter(c -> c.getOwnerId().equals(inv.getArgument(0)) && c.getName().equalsIgnoreCase(inv.getArgument(1))).findFirst());

        CredentialEncryptionService encryption = mock(CredentialEncryptionService.class);
        when(encryption.encrypt(anyString())).thenAnswer(inv -> "ENC(" + inv.getArgument(0) + ")");
        when(encryption.decrypt(anyString())).thenAnswer(inv -> {
            String v = inv.getArgument(0);
            return v.substring(4, v.length() - 1);
        });

        FeatureGateService gate = mock(FeatureGateService.class);
        when(gate.getFeatures()).thenAnswer(inv -> Features.forTier(tier));

        usage = mock(ConnectionUsage.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ConnectionUsage> usageProvider = mock(ObjectProvider.class);
        when(usageProvider.getIfAvailable()).thenReturn(usage);

        service = new ConnectorConnectionService(repository, encryption, mock(ApplicationEventPublisher.class),
                gate, usageProvider, true);
    }

    private static ConnectionInput jira(String name, String token) {
        return new ConnectionInput(name, ConnectionProtocol.JIRA, "https://jira.example/", null, token,
                false, null, null, null);
    }

    @Test
    @DisplayName("the token is stored encrypted, never as typed, and the view shows only a mask")
    void tokenIsEncrypted() {
        var view = service.create(ANA, jira("Work", "abcdefghijklmnop"));
        ConnectorConnection row = rows.get(view.id());
        assertThat(row.getSecret()).isEqualTo("ENC(abcdefghijklmnop)");
        assertThat(view.secretMasked()).isEqualTo("abcd***nop");
        assertThat(view.secretConfigured()).isTrue();
        assertThat(row.getBaseUrl()).isEqualTo("https://jira.example");
    }

    @Test
    @DisplayName("somebody else's connection is not found - not readable, not editable, not resolvable")
    void ownerScope() {
        UUID id = service.create(ANA, jira("Work", "token-ana-1234")).id();
        assertThrows(NoSuchElementException.class, () -> service.get(BO, id));
        assertThrows(NoSuchElementException.class, () -> service.update(BO, id, jira("Mine now", null)));
        assertThrows(NoSuchElementException.class, () -> service.delete(BO, id));
        assertThat(service.resolveOwned(BO, id)).isEmpty();
        assertThat(service.list(BO)).isEmpty();
        assertThat(service.resolveOwned(ANA, id)).get().extracting(ResolvedConnection::secret).isEqualTo("token-ana-1234");
    }

    @Test
    @DisplayName("an edit with a blank token keeps the stored one; clearing is its own flag")
    void blankKeepsClearClears() {
        UUID id = service.create(ANA, jira("Work", "token-1234567890")).id();
        service.update(ANA, id, new ConnectionInput("Work renamed", null, "https://jira2.example", null, "",
                false, null, null, null));
        assertThat(rows.get(id).getSecret()).isEqualTo("ENC(token-1234567890)");
        assertThat(rows.get(id).getName()).isEqualTo("Work renamed");
        service.update(ANA, id, new ConnectionInput("Work renamed", null, null, null, null, true, null, null, null));
        assertThat(rows.get(id).getSecret()).isNull();
    }

    @Test
    @DisplayName("the first connection of a service is its default, and there is only ever one")
    void oneDefault() {
        UUID first = service.create(ANA, jira("A", "t1-abcdefghijk")).id();
        UUID second = service.create(ANA, jira("B", "t2-abcdefghijk")).id();
        assertThat(rows.get(first).isDefaultConnection()).isTrue();
        assertThat(rows.get(second).isDefaultConnection()).isFalse();

        service.update(ANA, second, new ConnectionInput("B", null, null, null, null, false, true, null, null));
        assertThat(rows.get(first).isDefaultConnection()).isFalse();
        assertThat(rows.get(second).isDefaultConnection()).isTrue();

        // Deleting the default hands it to the next one rather than leaving the service with none.
        when(usage.sourcesUsing(second)).thenReturn(0L);
        service.delete(ANA, second);
        assertThat(rows.get(first).isDefaultConnection()).isTrue();
        assertThat(service.defaultFor(ANA, ConnectionProtocol.JIRA)).get().extracting(ResolvedConnection::id).isEqualTo(first);
    }

    @Test
    @DisplayName("a connection sources still read through cannot be deleted")
    void inUseIsKept() {
        UUID id = service.create(ANA, jira("Work", "token-1234567890")).id();
        when(usage.sourcesUsing(id)).thenReturn(2L);
        InvalidConnectionException e = assertThrows(InvalidConnectionException.class, () -> service.delete(ANA, id));
        assertThat(e.getMessage()).contains("2 sources");
        assertThat(rows).containsKey(id);
    }

    @Test
    @DisplayName("Community connects every service it describes, and refuses one only an Enterprise module describes")
    void communityConnectsWhatItDescribes() {
        // Microsoft 365's descriptor is Proxyma Enterprise's (MicrosoftConnectionEditionTest there pins
        // its license refusal); a catalog without that module does not know the service at all.
        tier = LicenseTier.COMMUNITY;
        InvalidConnectionException refused = assertThrows(InvalidConnectionException.class, () -> service.create(ANA,
                new ConnectionInput("Office", ConnectionProtocol.MICROSOFT_365, null, null, null, false, null, null, null)));
        assertThat(refused.getMessage()).contains("not a known type of connection");
        assertThat(rows).isEmpty();
        assertThat(service.create(ANA, jira("Work", "token-1234567890")).id()).isNotNull();
    }

    @Test
    @DisplayName("Write never sticks on a service with no write tools, and starts off everywhere")
    void writeOnlyWhereItMeansSomething() {
        UUID svn = service.create(ANA, new ConnectionInput("Repo", ConnectionProtocol.SVN, "https://svn.example/svn",
                "ana", "pw", false, null, true, true)).id();
        assertThat(rows.get(svn).isAllowWrite()).isFalse();

        UUID jira = service.create(ANA, jira("Work", "token-1234567890")).id();
        assertThat(rows.get(jira).isAllowWrite()).isFalse();
        assertThat(rows.get(jira).isAllowRead()).isTrue();
        service.update(ANA, jira, new ConnectionInput("Work", null, null, null, null, false, null, null, true));
        assertThat(rows.get(jira).isAllowWrite()).isTrue();
    }

    @Test
    @DisplayName("SVN's credential reaches the connector as user:password, and never half of one")
    void svnCredential() {
        UUID id = service.create(ANA, new ConnectionInput("Repo", ConnectionProtocol.SVN, "https://svn.example/svn",
                "ana", "p:w", false, null, null, null)).id();
        assertThat(service.resolveOwned(ANA, id).orElseThrow().credential()).isEqualTo("ana:p:w");
        service.update(ANA, id, new ConnectionInput("Repo", null, null, null, null, true, null, null, null));
        assertThat(service.resolveOwned(ANA, id).orElseThrow().credential()).isNull();
    }

    @Test
    @DisplayName("a connection's service cannot be changed under the sources registered against it")
    void protocolIsFixed() {
        UUID id = service.create(ANA, jira("Work", "token-1234567890")).id();
        assertThrows(InvalidConnectionException.class, () -> service.update(ANA, id,
                new ConnectionInput("Work", ConnectionProtocol.CONFLUENCE, null, null, null, false, null, null, null)));
    }

    @Test
    @DisplayName("names are unique per owner only - two people may both have a 'Work' Jira")
    void namesPerOwner() {
        service.create(ANA, jira("Work", "token-ana-12345"));
        assertThrows(InvalidConnectionException.class, () -> service.create(ANA, jira("work", "token-ana-67890")));
        service.create(BO, jira("Work", "token-bo-123456"));
        assertThat(service.list(BO)).hasSize(1);
    }

    @Test
    @DisplayName("Microsoft 365 is offered nowhere without its module; the built-in services everywhere")
    void microsoftIsNotOfferedWithoutItsModule() {
        assertThat(service.offeredProtocols()).doesNotContain(ConnectionProtocol.MICROSOFT_365);
        ConnectorConnectionService desktop = new ConnectorConnectionService(repository,
                mock(CredentialEncryptionService.class), mock(ApplicationEventPublisher.class),
                mock(FeatureGateService.class), null, false);
        assertThat(desktop.offeredProtocols()).doesNotContain(ConnectionProtocol.MICROSOFT_365);
        assertThat(List.of(ConnectionProtocol.JIRA, ConnectionProtocol.SVN)).allMatch(desktop.offeredProtocols()::contains);
    }

    private static ConnectionInput share(String name, String server) {
        return new ConnectionInput(name, ConnectionProtocol.NETWORK_SHARE, server, "CORP\\me", "pw",
                false, null, null, null);
    }

    @Test
    @DisplayName("a network share connection stores its file server as one bare host, and its password encrypted")
    void networkShareStoresTheHost() {
        UUID id = service.create(ANA, share("Files", "\\\\FS1.Corp.Example\\share\\docs")).id();
        assertThat(rows.get(id).getBaseUrl()).isEqualTo("fs1.corp.example");
        assertThat(rows.get(id).getSecret()).isEqualTo("ENC(pw)");
        assertThat(service.resolveOwned(ANA, id)).get().extracting(ResolvedConnection::credential).isEqualTo("CORP\\me:pw");
    }

    @Test
    @DisplayName("a network share connection names one server - a pattern or nothing is refused")
    void networkShareRefusesAPattern() {
        assertThrows(InvalidConnectionException.class, () -> service.create(ANA, share("Many", "*.corp.example")));
        assertThrows(InvalidConnectionException.class, () -> service.create(ANA, share("None", "")));
    }

    @Test
    @DisplayName("on Enterprise Server the admin list of file servers is a ceiling; blank is none")
    void networkShareCeiling() {
        ai.proxyma.core.settings.service.SettingsService settings = mock(ai.proxyma.core.settings.service.SettingsService.class);
        service.setSettingsService(settings);

        when(settings.getNetworkShareAllowedHosts()).thenReturn("");
        service.create(ANA, share("Any", "fs9.elsewhere.example"));

        when(settings.getNetworkShareAllowedHosts()).thenReturn("*.corp.example");
        service.create(ANA, share("Listed", "fs1.corp.example"));
        InvalidConnectionException e = assertThrows(InvalidConnectionException.class,
                () -> service.create(ANA, share("Unlisted", "fs2.other.example")));
        assertThat(e.getMessage()).contains("fs2.other.example");
    }

    @Test
    @DisplayName("Desktop and Home Server file every connection under one local owner")
    void localOwner() {
        UUID id = service.create(null, jira("Work", "token-1234567890")).id();
        assertThat(rows.get(id).getOwnerId()).isEqualTo(ConnectorConnection.LOCAL_OWNER);
        assertThat(service.resolveOwned("", id)).isPresent();
    }
}
