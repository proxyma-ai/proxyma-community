package ai.proxyma.core.configtransfer;

import ai.proxyma.core.chat.service.ChatSkillService;
import ai.proxyma.core.chat.service.SystemPromptService;
import ai.proxyma.core.chat.service.WorkflowService;
import ai.proxyma.core.guardrail.policy.PolicyLibrary;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import ai.proxyma.core.connector.ConnectionProtocol;
import ai.proxyma.core.settings.connection.ConnectorConnectionService;
import ai.proxyma.core.settings.connection.ConnectorConnectionView;
import ai.proxyma.core.settings.connection.ResolvedConnection;
import ai.proxyma.core.settings.provider.ProviderInstanceService;
import ai.proxyma.core.settings.service.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The choice the maintainer asked for on 2026-09-27: export and import only the groups picked, and
 * say which imported settings need a restart BEFORE they are applied.
 *
 * <p>What is pinned is what would be silently wrong: a group not chosen that is written anyway, a
 * provider created while "AI Providers" was unticked, and a Network change whose restart the
 * preview does not mention.
 */
class ConfigTransferGroupsTest {

    private SettingsService settings;
    private ProviderInstanceService providers;
    private ConnectorConnectionService connections;
    private ConfigTransferService service;

    @BeforeEach
    void setUp() {
        settings = mock(SettingsService.class);
        providers = mock(ProviderInstanceService.class);
        ChatSkillService skills = mock(ChatSkillService.class);
        WorkflowService workflows = mock(WorkflowService.class);
        ModelPricingService pricing = mock(ModelPricingService.class);
        PolicyLibrary policies = mock(PolicyLibrary.class);
        when(settings.getSettingsFields(anyString())).thenReturn(Map.of());
        when(settings.getSettingsFields("proxy")).thenReturn(Map.of("http-proxy", "old.example"));
        when(settings.getSettingsFields("chat")).thenReturn(Map.of("max-iterations", "10"));
        when(providers.list()).thenReturn(List.of());
        when(providers.resolveAll()).thenReturn(List.of());
        when(skills.list(anyString(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(List.of());
        when(workflows.list(anyString(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(List.of());
        when(pricing.listAll()).thenReturn(List.of());
        when(policies.list()).thenReturn(List.of());
        connections = mock(ConnectorConnectionService.class);
        when(connections.offeredProtocols()).thenReturn(List.of(ConnectionProtocol.values()));
        service = new ConfigTransferService(settings, providers, skills, workflows,
                mock(SystemPromptService.class), pricing, policies, connections, new ObjectMapper());
    }

    private static ConfigExport file() {
        return new ConfigExport(null, null,
                Map.of("proxy", Map.of("http-proxy", "new.example"), "chat", Map.of("max-iterations", "50")),
                List.of(new ConfigExport.Provider("Gateway", "OPENAI_COMPATIBLE", "http://gw", null, null, "src-1")),
                null, null, null, null, null, null);
    }

    @Test
    @DisplayName("an export carries only the groups chosen")
    void exportCarriesOnlyTheChosenGroups() {
        ConfigExport out = service.export(null, "u", List.of(TransferGroups.NETWORK));
        assertThat(out.settings()).containsOnlyKeys("proxy");
        assertThat(out.providers()).isEmpty();
    }

    @Test
    @DisplayName("an import writes only the groups chosen, and creates no provider it was not asked to")
    void importWritesOnlyTheChosenGroups() {
        ConfigImportReport report = service.importConfig(file(), null, true, "u", List.of(TransferGroups.AGENTS));
        verify(settings).saveFields("chat", Map.of("max-iterations", "50"));
        verify(settings, never()).saveFields(org.mockito.ArgumentMatchers.eq("proxy"), org.mockito.ArgumentMatchers.anyMap());
        verify(providers, never()).create(org.mockito.ArgumentMatchers.any());
        assertThat(report.getEntries()).allMatch(e -> e.group().equals(TransferGroups.AGENTS));
        assertThat(report.isRequiresRestart()).isFalse();
    }

    @Test
    @DisplayName("the preview marks a Network change as needing a restart, and writes nothing")
    void previewSaysARestartIsNeeded() {
        ConfigImportReport report = service.importConfig(file(), null, false, "u", null);
        assertThat(report.getEntries())
                .filteredOn(e -> e.item().equals("proxy.http-proxy"))
                .singleElement()
                .satisfies(e -> assertThat(e.requiresRestart()).isTrue());
        assertThat(report.getEntries())
                .filteredOn(e -> e.item().equals("chat.max-iterations"))
                .singleElement()
                .satisfies(e -> assertThat(e.requiresRestart()).isFalse());
        verify(settings, never()).saveFields(anyString(), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    @DisplayName("the apply reports the restart it needs")
    void applySaysARestartIsNeeded() {
        ConfigImportReport report = service.importConfig(file(), null, true, "u", List.of(TransferGroups.NETWORK));
        assertThat(report.isRequiresRestart()).isTrue();
    }

    @Test
    @DisplayName("an import offers exactly the groups the file holds")
    void groupsInTheFile() {
        assertThat(service.groupsIn(file()))
                .containsExactly(TransferGroups.PROVIDERS, TransferGroups.AGENTS, TransferGroups.NETWORK);
    }

    @Test
    @DisplayName("web search travels with Search, the tab it is set on")
    void webSearchTravelsWithSearch() {
        assertThat(TransferGroups.ofPrefix("web-search")).isEqualTo(TransferGroups.RETRIEVAL);
    }

    @Test
    @DisplayName("a settings prefix no group names still travels, under Other")
    void unknownPrefixIsOther() {
        assertThat(TransferGroups.ofPrefix("a-prefix-added-later")).isEqualTo(TransferGroups.OTHER);
    }

    private static ConnectorConnectionView view(UUID id, String name, ConnectionProtocol protocol) {
        var d = ai.proxyma.core.connector.spi.CoreDescriptors.of(protocol);
        // Microsoft 365's descriptor is a Proxyma Enterprise module's: a sign-in, with no write tools.
        if (d == null) {
            return new ConnectorConnectionView(id, name, protocol, "Microsoft 365",
                    ai.proxyma.core.settings.connection.ConnectionAuthKind.OAUTH,
                    "https://x.example", null, null, true, true, true, false, false,
                    null, null, null, null);
        }
        return new ConnectorConnectionView(id, name, protocol, d.displayName(),
                ai.proxyma.core.settings.connection.ConnectionAuthKind.of(d.primaryAuth().kind()),
                "https://x.example", null, null, true, true, true, false, d.supportsWrite(),
                null, null, null, null);
    }

    @Test
    @DisplayName("an export carries the exporter's own connections - tokens only when sealed, a Microsoft sign-in never")
    void connectionsExport() {
        UUID jira = UUID.randomUUID();
        UUID ms = UUID.randomUUID();
        when(connections.list("u")).thenReturn(List.of(view(jira, "Work", ConnectionProtocol.JIRA),
                view(ms, "Tenant", ConnectionProtocol.MICROSOFT_365)));
        when(connections.resolveOwned("u", jira)).thenReturn(Optional.of(new ResolvedConnection(jira, "u", "Work",
                ConnectionProtocol.JIRA, "https://jira.example", null, "pat-1", true, true, false, null, null, null)));
        // Signed in the OAuth way, as the connection service resolves it through the Microsoft 365
        // module's descriptor (MicrosoftConnectionEditionTest there pins that it does).
        when(connections.resolveOwned("u", ms)).thenReturn(Optional.of(new ResolvedConnection(ms, "u", "Tenant",
                ConnectionProtocol.MICROSOFT_365, null, null, "refresh-token", true, true, false, null, null, null,
                ai.proxyma.core.connector.spi.AuthKind.OAUTH2_CODE, null, null)));

        ConfigExport withoutSecrets = service.export(null, "u", List.of(TransferGroups.CONNECTORS));
        assertThat(withoutSecrets.connections()).extracting(ConfigExport.Connection::secret)
                .containsExactly(ConfigExport.OMITTED_PLACEHOLDER, null);
        assertThat(withoutSecrets.toString()).doesNotContain("pat-1").doesNotContain("refresh-token");

        ConfigExport sealed = service.export("a passphrase", "u", List.of(TransferGroups.CONNECTORS));
        assertThat(sealed.connections()).extracting(ConfigExport.Connection::secret)
                .containsExactly(ConfigExport.SECRET_PLACEHOLDER, null);
        verify(connections, never()).list("someone-else");
    }

    @Test
    @DisplayName("an import creates connections as the importer's own, and never overwrites one by the same name")
    void connectionsImport() {
        when(connections.list("u")).thenReturn(List.of(view(UUID.randomUUID(), "Work", ConnectionProtocol.JIRA)));
        ConfigExport file = new ConfigExport(null, null, null, null, null, null, null, null, null, List.of(
                new ConfigExport.Connection("work", "JIRA", "https://other.example", null, null, true),
                new ConfigExport.Connection("Wiki", "CONFLUENCE", "https://wiki.example", null, null, false)));

        ConfigImportReport report = service.importConfig(file, null, true, "u", List.of(TransferGroups.CONNECTORS));

        verify(connections).create(org.mockito.ArgumentMatchers.eq("u"), org.mockito.ArgumentMatchers.argThat(
                in -> in.name().equals("Wiki") && in.protocol() == ConnectionProtocol.CONFLUENCE
                        // Read and Write never travel: null leaves the service's defaults, write off.
                        && in.allowRead() == null && in.allowWrite() == null));
        verify(connections, never()).create(anyString(), org.mockito.ArgumentMatchers.argThat(in -> in.name().equalsIgnoreCase("work")));
        assertThat(report.getEntries()).filteredOn(e -> e.item().equals("work")).singleElement()
                .satisfies(e -> assertThat(e.outcome()).isEqualTo(ConfigImportReport.Outcome.SKIPPED));
    }
}
