package ai.proxyma.core.configtransfer;

import ai.proxyma.core.chat.service.ChatSkillService;
import ai.proxyma.core.chat.service.SystemPromptService;
import ai.proxyma.core.chat.service.WorkflowService;
import ai.proxyma.core.guardrail.policy.PolicyLibrary;
import ai.proxyma.core.settings.connection.ConnectorConnectionService;
import ai.proxyma.core.settings.pricing.ModelPricingService;
import ai.proxyma.core.settings.provider.ProviderInstanceService;
import ai.proxyma.core.settings.provider.ProviderInstanceView;
import ai.proxyma.core.settings.provider.ProviderProtocol;
import ai.proxyma.core.settings.provider.ResolvedProvider;
import ai.proxyma.core.settings.service.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * settings-core-04: a "no secrets" export is promised safe to attach to a ticket, and it carried a
 * provider's custom headers verbatim - an Authorization or X-Api-Key for a relay included. A header
 * the page treats as a credential now travels as a secret: sealed with a passphrase, omitted without
 * one, and never written to the target as a placeholder.
 */
class ConfigExportSecretsTest {

    private static final String PASSPHRASE = "a long enough passphrase";

    private ProviderInstanceService providers;
    private ConfigTransferService service;

    @BeforeEach
    void setUp() {
        SettingsService settings = mock(SettingsService.class);
        providers = mock(ProviderInstanceService.class);
        ChatSkillService skills = mock(ChatSkillService.class);
        WorkflowService workflows = mock(WorkflowService.class);
        when(settings.getSettingsFields(anyString())).thenReturn(Map.of());
        when(skills.list(anyString(), anyBoolean())).thenReturn(List.of());
        when(workflows.list(anyString(), anyBoolean())).thenReturn(List.of());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer relay-secret-0123456789");
        headers.put("X-Title", "Proxyma");
        when(providers.resolveAll()).thenReturn(List.of(new ResolvedProvider(UUID.randomUUID(), "Relay",
                ProviderProtocol.OPENAI, "https://relay.example/v1", null, headers, false, null, false)));
        when(providers.list()).thenReturn(List.of());
        when(providers.create(any())).thenReturn(new ProviderInstanceView(UUID.randomUUID(), "Relay",
                ProviderProtocol.OPENAI, "OpenAI", null, null, false, Map.of(), false));
        service = new ConfigTransferService(settings, providers, skills, workflows,
                mock(SystemPromptService.class), mock(ModelPricingService.class), mock(PolicyLibrary.class),
                mock(ConnectorConnectionService.class), new ObjectMapper());
    }

    @Test
    @DisplayName("without a passphrase a credential header is omitted; an ordinary one travels")
    void omittedWithoutAPassphrase() throws Exception {
        ConfigExport out = service.export(null, "u", List.of(TransferGroups.PROVIDERS));

        Map<String, String> headers = out.providers().get(0).headers();
        assertThat(headers.get("Authorization")).isEqualTo(ConfigExport.OMITTED_PLACEHOLDER);
        assertThat(headers.get("X-Title")).isEqualTo("Proxyma");
        assertThat(new ObjectMapper().writeValueAsString(out)).doesNotContain("relay-secret");
    }

    @Test
    @DisplayName("with a passphrase it is sealed, and an import on the other side opens it")
    void sealedWithAPassphraseAndRestored() throws Exception {
        ConfigExport out = service.export(PASSPHRASE, "u", List.of(TransferGroups.PROVIDERS));
        assertThat(out.providers().get(0).headers().get("Authorization")).isEqualTo(ConfigExport.SECRET_PLACEHOLDER);
        assertThat(new ObjectMapper().writeValueAsString(out)).doesNotContain("relay-secret");

        service.importConfig(out, PASSPHRASE, true, "u", List.of(TransferGroups.PROVIDERS));

        ArgumentCaptor<ProviderInstanceService.ProviderInstanceInput> created =
                ArgumentCaptor.forClass(ProviderInstanceService.ProviderInstanceInput.class);
        verify(providers).create(created.capture());
        assertThat(created.getValue().headers())
                .containsEntry("Authorization", "Bearer relay-secret-0123456789")
                .containsEntry("X-Title", "Proxyma");
    }

    @Test
    @DisplayName("an omitted credential header is left out of the created provider, and the report says so")
    void omittedHeaderIsNotWrittenAsAPlaceholder() {
        ConfigExport out = service.export(null, "u", List.of(TransferGroups.PROVIDERS));

        ConfigImportReport report = service.importConfig(out, null, true, "u", List.of(TransferGroups.PROVIDERS));

        ArgumentCaptor<ProviderInstanceService.ProviderInstanceInput> created =
                ArgumentCaptor.forClass(ProviderInstanceService.ProviderInstanceInput.class);
        verify(providers).create(created.capture());
        assertThat(created.getValue().headers()).containsOnlyKeys("X-Title");
        assertThat(report.getEntries().get(0).detail()).contains("without a credential");
    }
}
