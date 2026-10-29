package ai.proxyma.core.chat.agents;

import ai.proxyma.core.provider.adapter.LlmProviderAdapter;
import ai.proxyma.core.provider.service.LlmProviderFactory;
import ai.proxyma.core.settings.dto.ChatSettings;
import ai.proxyma.core.settings.dto.SubAgentSettings;
import ai.proxyma.core.settings.provider.ProviderInstanceService;
import ai.proxyma.core.settings.provider.ResolvedProvider;
import ai.proxyma.core.settings.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AN AGENT NAMES ONLY A MODEL A PROVIDER LISTS AND SETTINGS ALLOW (the maintainer's request,
 * 2026-10-29: an agent invented a model name for the agent it deployed, which then failed at its first
 * call). The name resolves to the provider listing it, the agent's own first; anything else is refused
 * with the names it may use; the rule - any listed model, only the chosen ones, all but those - is
 * applied per provider or per model; and the lists are asked for at most every five minutes.
 */
class AgentModelsTest {

    final UUID home = UUID.randomUUID();
    final UUID gateway = UUID.randomUUID();
    final ProviderInstanceService instances = mock(ProviderInstanceService.class);
    final LlmProviderFactory factory = mock(LlmProviderFactory.class);
    final SettingsService settings = mock(SettingsService.class);
    final LlmProviderAdapter homeAdapter = mock(LlmProviderAdapter.class);
    final LlmProviderAdapter gatewayAdapter = mock(LlmProviderAdapter.class);
    final AtomicLong now = new AtomicLong(1_000_000);
    final SubAgentSettings rule = new SubAgentSettings();
    AgentModels models;

    static ResolvedProvider provider(UUID id, String name) {
        ResolvedProvider p = mock(ResolvedProvider.class);
        when(p.id()).thenReturn(id);
        when(p.name()).thenReturn(name);
        return p;
    }

    @BeforeEach
    void setUp() {
        ResolvedProvider h = provider(home, "Anthropic");
        ResolvedProvider g = provider(gateway, "Gateway");
        when(instances.resolveAll()).thenReturn(List.of(h, g));
        when(factory.getAdapter(home.toString())).thenReturn(homeAdapter);
        when(factory.getAdapter(gateway.toString())).thenReturn(gatewayAdapter);
        when(homeAdapter.listModels(any())).thenReturn(List.of("claude-sonnet-5-5", "claude-haiku-5-5"));
        when(gatewayAdapter.listModels(any())).thenReturn(List.of("claude-haiku-5-5", "gpt-5-mini"));
        when(settings.getSettings(SubAgentSettings.PREFIX, SubAgentSettings.class)).thenReturn(rule);
        ChatSettings chat = new ChatSettings();
        chat.setProvider(gateway.toString());
        when(settings.getSettings(ChatSettings.PREFIX, ChatSettings.class)).thenReturn(chat);
        models = new AgentModels(instances, factory, settings, now::get);
    }

    @Test
    @DisplayName("a listed name resolves to the provider listing it, the agent's own first, in the provider's spelling")
    void listedNameResolves() {
        AgentModels.Choice c = models.choose(null, "Claude-Haiku-5-5");
        assertThat(c.ok()).isTrue();
        assertThat(c.providerId()).isEqualTo(gateway.toString());
        assertThat(c.model()).isEqualTo("claude-haiku-5-5");
        assertThat(models.choose(null, "claude-sonnet-5-5").providerId()).isEqualTo(home.toString());
        assertThat(models.choose(home.toString(), "claude-haiku-5-5").providerId()).isEqualTo(home.toString());
        assertThat(models.choose(null, " ").ok()).as("naming none is always allowed").isTrue();
    }

    @Test
    @DisplayName("an invented name is refused with the names the agent may use")
    void inventedNameRefused() {
        AgentModels.Choice c = models.choose(null, "claude-opus-9");
        assertThat(c.ok()).isFalse();
        assertThat(c.error()).contains("No provider lists a model named claude-opus-9")
                .contains("claude-sonnet-5-5 (Anthropic)").contains("gpt-5-mini (Gateway)");
    }

    @Test
    @DisplayName("ONLY allows the chosen entries - a whole provider or one model")
    void onlyTheChosen() {
        rule.setModelRule(SubAgentSettings.MODELS_ONLY);
        rule.setModelList(home + "\n" + gateway + "|gpt-5-mini");
        assertThat(models.choose(null, "claude-sonnet-5-5").ok()).isTrue();
        assertThat(models.choose(null, "gpt-5-mini").ok()).isTrue();
        // Listed by both, allowed only through the provider allowed whole.
        assertThat(models.choose(null, "claude-haiku-5-5").providerId()).isEqualTo(home.toString());
        assertThat(models.choose(gateway.toString(), "claude-haiku-5-5").ok()).isFalse();
    }

    @Test
    @DisplayName("EXCEPT bars the chosen entries and says Settings do not allow them")
    void allButTheChosen() {
        rule.setModelRule("except");
        rule.setModelList(gateway + "|gpt-5-mini");
        AgentModels.Choice barred = models.choose(null, "gpt-5-mini");
        assertThat(barred.ok()).isFalse();
        assertThat(barred.error()).contains("Settings do not allow deployed agents to use gpt-5-mini")
                .doesNotContain("gpt-5-mini (Gateway)");
        assertThat(models.choose(null, "claude-sonnet-5-5").ok()).isTrue();
    }

    @Test
    @DisplayName("the lists are read at most every five minutes, and a provider that fails lists nothing")
    void listsCached() {
        when(gatewayAdapter.listModels(any())).thenThrow(new IllegalStateException("down"));
        assertThat(models.choose(null, "gpt-5-mini").ok()).isFalse();
        assertThat(models.choose(null, "claude-sonnet-5-5").ok()).isTrue();
        verify(homeAdapter, times(1)).listModels(any());
        now.addAndGet(AgentModels.LIST_TTL_MS + 1);
        models.choose(null, "claude-sonnet-5-5");
        verify(homeAdapter, times(2)).listModels(any());
    }

    @Test
    @DisplayName("an unknown rule reads as any listed model")
    void unknownRule() {
        rule.setModelRule("WHATEVER");
        assertThat(rule.modelRule()).isEqualTo(SubAgentSettings.MODELS_ANY);
        assertThat(AgentModels.allowed(rule, home.toString(), "anything")).isTrue();
    }
}
