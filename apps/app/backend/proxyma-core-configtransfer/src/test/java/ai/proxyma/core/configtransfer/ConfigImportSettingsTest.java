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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What an import writes into the settings, and what it carries for skills.
 */
class ConfigImportSettingsTest {

    private static final String SOURCE_PROVIDER = "6f1d2c3b-0000-4000-8000-000000000001";

    private SettingsService settings;
    private ChatSkillService skills;
    private ConfigTransferService service;

    @BeforeEach
    void setUp() {
        settings = mock(SettingsService.class);
        ProviderInstanceService providers = mock(ProviderInstanceService.class);
        skills = mock(ChatSkillService.class);
        WorkflowService workflows = mock(WorkflowService.class);
        when(settings.getSettingsFields(anyString())).thenReturn(Map.of());
        when(providers.list()).thenReturn(List.of());
        when(providers.resolveAll()).thenReturn(List.of());
        when(skills.list(anyString(), anyBoolean())).thenReturn(List.of());
        when(workflows.list(anyString(), anyBoolean())).thenReturn(List.of());
        when(workflows.visibleNamesLowercase(anyString())).thenReturn(java.util.Set.of());
        when(skills.create(anyString(), any(), any(), anyString(), anyList(), anyList(), anyList()))
                .thenReturn(new ChatSkillService.SkillDetail("s-1", "Review", null, null, "u", false, false,
                        List.of(), List.of(), List.of(), true, true, true, false));
        service = new ConfigTransferService(settings, providers, skills, workflows,
                mock(SystemPromptService.class), mock(ModelPricingService.class), mock(PolicyLibrary.class),
                mock(ConnectorConnectionService.class), new ObjectMapper());
    }

    @Test
    @DisplayName("a role naming a provider the import neither matched nor created is not written")
    void danglingProviderIdIsSkipped() {
        ConfigExport file = new ConfigExport(null, null,
                Map.of("chat", Map.of("provider", SOURCE_PROVIDER, "model", "gpt-x")),
                List.of(new ConfigExport.Provider("Elsewhere", "OPENAI", null, null, null, SOURCE_PROVIDER)),
                null, null, null, null, null, null);

        // Agents chosen, AI Providers not: nothing is created, nothing here has that name.
        ConfigImportReport report = service.importConfig(file, null, true, "u", List.of(TransferGroups.AGENTS));

        assertThat(report.getEntries()).filteredOn(e -> e.item().equals("chat.provider")).singleElement()
                .satisfies(e -> assertThat(e.outcome()).isEqualTo(ConfigImportReport.Outcome.SKIPPED));
        verify(settings).saveFields("chat", Map.of("model", "gpt-x"));
    }

    @Test
    @DisplayName("a field this build does not declare is reported, not written")
    void unknownFieldIsSkipped() {
        ConfigExport file = new ConfigExport(null, null, Map.of("chat", Map.of("no-such-field", "1")),
                null, null, null, null, null, null, null);

        ConfigImportReport report = service.importConfig(file, null, true, "u", null);

        assertThat(report.getEntries()).singleElement()
                .satisfies(e -> assertThat(e.outcome()).isEqualTo(ConfigImportReport.Outcome.SKIPPED));
        verify(settings, never()).saveFields(anyString(), anyMap());
    }

    @Test
    @DisplayName("on Enterprise Server a skill shared in the file arrives shared")
    void sharedSkillStaysShared() {
        ReflectionTestUtils.setField(service, "multiUser", true);
        ConfigExport file = new ConfigExport(null, null, null, null,
                List.of(new ConfigExport.Skill("Review", "short", "detail", null, null, null, true)),
                null, null, null, null, null);

        service.importConfig(file, null, true, "u", List.of(TransferGroups.SKILLS));

        verify(skills).update(eq("s-1"), eq("Review"), eq("short"), eq("detail"), eq("u"), eq(false),
                anyList(), anyList(), anyList(), eq(true));
    }

    @Test
    @DisplayName("an export says which build wrote it - from the property the build actually sets")
    void exportNamesItsBuild() throws Exception {
        // proxyma.version is set nowhere, so every file said "unknown" (settings-core-22).
        org.springframework.beans.factory.annotation.Value value = ConfigTransferService.class
                .getDeclaredField("appVersion").getAnnotation(org.springframework.beans.factory.annotation.Value.class);
        assertThat(value.value()).startsWith("${spring.application.version");
    }
}
