package ai.proxyma.core.configtransfer;

import ai.proxyma.core.chat.service.ChatSkillService;
import ai.proxyma.core.chat.service.SystemPromptService;
import ai.proxyma.core.chat.service.WorkflowService;
import ai.proxyma.core.guardrail.policy.PolicyLibrary;
import ai.proxyma.core.settings.connection.ConnectorConnectionService;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import ai.proxyma.core.settings.provider.ProviderInstanceService;
import ai.proxyma.core.settings.service.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An import is a settings write by another door, so it refuses what the settings API refuses
 * (settings-core-01). A shared configuration file carrying {@code org.enabled = true} used to switch
 * on a vestigial org mode that turned the single-user editions' Host check and CORS off; and the
 * Entra app registration, which exists on Enterprise Server only, could be written on Desktop.
 */
class ConfigImportEditionTest {

    private SettingsService settings;
    private ConfigTransferService service;

    @BeforeEach
    void setUp() {
        settings = mock(SettingsService.class);
        ProviderInstanceService providers = mock(ProviderInstanceService.class);
        ChatSkillService skills = mock(ChatSkillService.class);
        WorkflowService workflows = mock(WorkflowService.class);
        when(settings.getSettingsFields(anyString())).thenReturn(Map.of());
        when(providers.list()).thenReturn(List.of());
        when(skills.list(anyString(), anyBoolean())).thenReturn(List.of());
        when(workflows.list(anyString(), anyBoolean())).thenReturn(List.of());
        service = new ConfigTransferService(settings, providers, skills, workflows,
                mock(SystemPromptService.class), mock(ModelPricingService.class), mock(PolicyLibrary.class),
                mock(ConnectorConnectionService.class), new ObjectMapper());
    }

    private static ConfigExport settingsFile(Map<String, Map<String, String>> settings) {
        return new ConfigExport(null, null, settings, null, null, null, null, null, null, null);
    }

    private ConfigImportReport.Entry onlyEntry(ConfigImportReport report, String item) {
        return report.getEntries().stream().filter(e -> e.item().equals(item)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("an 'org' group is not a settings group any more, and nothing is written for it")
    void orgModeIsNotImported() {
        ConfigImportReport report = service.importConfig(
                settingsFile(Map.of("org", Map.of("enabled", "true"))), null, true, "u", null);

        assertThat(onlyEntry(report, "org").outcome()).isEqualTo(ConfigImportReport.Outcome.SKIPPED);
        verify(settings, never()).saveFields(anyString(), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    @DisplayName("the Microsoft 365 app registration is refused on the single-user editions")
    void m365IsRefusedOffEnterprise() {
        ReflectionTestUtils.setField(service, "multiUser", false);
        ConfigImportReport report = service.importConfig(
                settingsFile(Map.of("m365", Map.of("tenant-id", "t-1"))), null, true, "u", null);

        assertThat(onlyEntry(report, "m365").outcome()).isEqualTo(ConfigImportReport.Outcome.SKIPPED);
        verify(settings, never()).saveFields(anyString(), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    @DisplayName("a secret goes through the same write as everything else, which encrypts it by annotation")
    void secretsTakeTheOneWritePath() {
        ConfigImportReport report = service.importConfig(
                settingsFile(Map.of("web-search", Map.of("api-key", "AIza-plain"))), null, true, "u", null);

        verify(settings).saveFields("web-search", Map.of("api-key", "AIza-plain"));
        verify(settings, never()).setSettings(anyString(), org.mockito.ArgumentMatchers.any());
        assertThat(onlyEntry(report, "web-search.api-key").detail()).isEqualTo("Credential set.");
    }
}
