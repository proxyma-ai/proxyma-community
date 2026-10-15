package ai.proxyma.core.settings.service;

import ai.proxyma.core.settings.dto.ChatSettings;
import ai.proxyma.core.settings.dto.WebSearchSettings;
import ai.proxyma.core.settings.event.ProviderSettingsChangedEvent;
import ai.proxyma.core.settings.global.GlobalSetting;
import ai.proxyma.core.settings.global.GlobalSettingRepository;
import ai.proxyma.core.settings.model.UserSettings;
import ai.proxyma.core.settings.repository.UserSettingsRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The global settings store's write path (settings-core-02, -07, -12, -13): a snapshot that is
 * never mutated, only the keys a write changed sent to the table, a refused write leaving both as
 * they were, and a value that will not decrypt read as unset rather than failing the prefix.
 *
 * <p>The table is a map behind a mocked repository, so what each write SENDS is what is asserted.
 */
class SettingsStoreTest {

    private final Map<String, String> table = new LinkedHashMap<>();
    private final List<List<String>> upserts = new ArrayList<>();
    private final List<List<String>> deletes = new ArrayList<>();
    private GlobalSettingRepository repository;
    private CredentialEncryptionService encryption;
    private ApplicationEventPublisher events;
    private SettingsService settings;

    @BeforeEach
    void setUp() {
        repository = mock(GlobalSettingRepository.class);
        when(repository.findAll()).thenAnswer(inv -> {
            List<GlobalSetting> rows = new ArrayList<>();
            table.forEach((k, v) -> rows.add(new GlobalSetting(k, v)));
            return rows;
        });
        when(repository.saveAll(anyIterable())).thenAnswer(inv -> {
            List<String> keys = new ArrayList<>();
            for (Object o : (Iterable<?>) inv.getArgument(0)) {
                GlobalSetting row = (GlobalSetting) o;
                if (row.getSettingKey().length() > 256) throw new IllegalStateException("value too long for type");
                keys.add(row.getSettingKey());
            }
            for (Object o : (Iterable<?>) inv.getArgument(0)) {
                GlobalSetting row = (GlobalSetting) o;
                table.put(row.getSettingKey(), row.getSettingValue());
            }
            upserts.add(keys);
            return null;
        });
        doAnswer(inv -> {
            List<String> keys = new ArrayList<>();
            for (Object o : (Iterable<?>) inv.getArgument(0)) keys.add((String) o);
            keys.forEach(table::remove);
            deletes.add(keys);
            return null;
        }).when(repository).deleteAllById(anyIterable());
        encryption = mock(CredentialEncryptionService.class);
        when(encryption.encrypt(anyString())).thenAnswer(inv -> "ENC(" + inv.getArgument(0) + ")");
        when(encryption.decrypt(anyString())).thenAnswer(inv -> {
            String v = inv.getArgument(0);
            if (v.equals("ENC(undecryptable)")) throw new IllegalStateException("Credential decryption failed");
            return v.startsWith("ENC(") ? v.substring(4, v.length() - 1) : v;
        });
        events = mock(ApplicationEventPublisher.class);
        settings = new SettingsService(new ObjectMapper(), encryption, events, repository, null, null);
    }

    @Test
    @DisplayName("a save sends only the key it changed - never the whole table, never a sweep of keys")
    void aSaveSendsItsDeltaOnly() {
        table.put("chat.model", "a");
        table.put("proxy.host", "h");

        settings.saveAiProviderField("chat", "model", "b");

        assertThat(upserts).containsExactly(List.of("chat.model"));
        assertThat(deletes).isEmpty();
        assertThat(table).containsEntry("chat.model", "b").containsEntry("proxy.host", "h");
    }

    @Test
    @DisplayName("a refused write leaves the cache as it was, and the next save of anything still works")
    void aRefusedWriteDoesNotPoisonTheCache() {
        String tooLong = "f".repeat(300);
        assertThatThrownBy(() -> settings.saveAiProviderField("jira", tooLong, "x"))
                .isInstanceOf(IllegalArgumentException.class);

        settings.saveAiProviderField("chat", "model", "b");

        assertThat(settings.getSettingsFields("jira")).isEmpty();
        assertThat(upserts).containsExactly(List.of("chat.model"));
    }

    @Test
    @DisplayName("a write the database rejects is not live in memory afterwards")
    void aFailedPersistIsNotLiveInMemory() {
        when(repository.saveAll(anyIterable())).thenThrow(new IllegalStateException("connection lost"));

        assertThatThrownBy(() -> settings.saveAiProviderField("chat", "model", "b"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(settings.getSettingsFields("chat")).doesNotContainKey("model");
    }

    @Test
    @DisplayName("a value that will not decrypt reads as unset, and the rest of the prefix still reads")
    void anUndecryptableValueIsUnset() {
        table.put("web-search.api-key", "ENC(undecryptable)");
        table.put("web-search.search-engine-id", "cx-1");

        assertThat(settings.getSettingsFields("web-search"))
                .containsEntry("search-engine-id", "cx-1").doesNotContainKey("api-key");
        WebSearchSettings typed = settings.getSettings(WebSearchSettings.PREFIX, WebSearchSettings.class);
        assertThat(typed.getApiKey()).isNull();
        assertThat(typed.getSearchEngineId()).isEqualTo("cx-1");
    }

    @Test
    @DisplayName("a whole settings object stores what differs from its defaults, and drops what equals them")
    void setSettingsDoesNotFreezeDefaults() {
        table.put("chat.max-iterations", String.valueOf(new ChatSettings().getMaxIterations()));

        ChatSettings s = settings.getSettings(ChatSettings.PREFIX, ChatSettings.class);
        s.setModel("gpt-x");
        settings.setSettings(ChatSettings.PREFIX, s);

        assertThat(table).containsEntry("chat.model", "gpt-x").doesNotContainKey("chat.max-iterations");
        assertThat(table.keySet()).allMatch(k -> k.equals("chat.model"));
    }

    @Test
    @DisplayName("an encrypted field is encrypted on every write path")
    void secretsAreEncrypted() {
        settings.saveFields("web-search", Map.of("api-key", "AIza"));
        assertThat(table).containsEntry("web-search.api-key", "ENC(AIza)");
    }

    @Test
    @DisplayName("several fields of one role are one write and one model rebuild")
    void saveFieldsIsOneWriteAndOneEvent() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("provider", "p-1");
        fields.put("model", "m-1");

        settings.saveFields("chat", fields);

        assertThat(upserts).hasSize(1);
        assertThat(upserts.get(0)).containsExactlyInAnyOrder("chat.provider", "chat.model");
        verify(events, times(1)).publishEvent(any(ProviderSettingsChangedEvent.class));
    }

    @Test
    @DisplayName("a change made behind the service - a restore, a reset - is read after reloadFromStore")
    void reloadFromStore() {
        assertThat(settings.getSettingsFields("chat")).isEmpty();
        table.put("chat.model", "restored");

        assertThat(settings.getSettingsFields("chat")).isEmpty();
        settings.reloadFromStore();
        assertThat(settings.getSettingsFields("chat")).containsEntry("model", "restored");
        verify(repository, never()).deleteAllById(anyIterable());
    }

    @Test
    @DisplayName("a save after the table changed behind the service does not write the old values back (platform-01)")
    void aSaveDoesNotRevertARestore() {
        table.put("chat.model", "before-restore");
        table.put("proxy.mode", "none");
        settings.getSettingsFields("chat");
        // A restore rewrites the table under the cached copy.
        table.clear();
        table.put("chat.model", "restored");
        table.put("embedding.model", "restored-embedder");

        settings.saveAiProviderField("reranking", "model", "r-1");

        assertThat(table).containsEntry("chat.model", "restored")
                .containsEntry("embedding.model", "restored-embedder")
                .containsEntry("reranking.model", "r-1")
                .doesNotContainKey("proxy.mode");
    }

    @Test
    @DisplayName("one user's override blob is bounded")
    void userBlobIsBounded() {
        UserSettingsRepository users = mock(UserSettingsRepository.class);
        UserSettings row = new UserSettings();
        row.setUserId("u-1");
        row.setSettingsJson("{\"jira.sync-interval-seconds\":\"" + "9".repeat(SettingsService.MAX_USER_BLOB_CHARS) + "\"}");
        when(users.findById("u-1")).thenReturn(Optional.of(row));
        SettingsService perUser = new SettingsService(new ObjectMapper(), encryption, events, repository, users, null);

        assertThatThrownBy(() -> perUser.saveUserField("jira", "base-url", "https://x", "u-1"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(users, never()).save(any());
    }
}
