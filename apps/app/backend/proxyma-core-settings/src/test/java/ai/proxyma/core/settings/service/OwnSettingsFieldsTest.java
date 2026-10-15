package ai.proxyma.core.settings.service;

import ai.proxyma.core.settings.global.GlobalSetting;
import ai.proxyma.core.settings.global.GlobalSettingRepository;
import ai.proxyma.core.settings.model.UserSettings;
import ai.proxyma.core.settings.repository.UserSettingsRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A network share is read as its OWNER'S domain account and nobody else's.
 *
 * <p>{@code getSettingsFieldsForUser} falls back to the global value for anything the user has not
 * set, which is right for a default server URL. For an identity it means a global account - however
 * it got there - is silently the account of every user who never saved their own, and every one of
 * their shares is then indexed with that person's rights. {@code getOwnSettingsFieldsForUser} is
 * the read the connector uses, and these pin that it has no such fallback.
 */
class OwnSettingsFieldsTest {

    private GlobalSettingRepository global;
    private UserSettingsRepository users;
    private SettingsService settings;

    @BeforeEach
    void setUp() {
        global = mock(GlobalSettingRepository.class);
        users = mock(UserSettingsRepository.class);
        settings = new SettingsService(new ObjectMapper(), mock(CredentialEncryptionService.class),
                mock(ApplicationEventPublisher.class), global, users, null);

        GlobalSetting user = new GlobalSetting();
        user.setSettingKey("smb.username");
        user.setSettingValue("CORP\\admin");
        GlobalSetting pass = new GlobalSetting();
        pass.setSettingKey("smb.password");
        pass.setSettingValue("admins-password");
        when(global.findAll()).thenReturn(List.of(user, pass));
    }

    private void userBlob(String userId, String json) {
        UserSettings row = new UserSettings();
        row.setUserId(userId);
        row.setSettingsJson(json);
        when(users.findById(userId)).thenReturn(Optional.of(row));
    }

    @Test
    @DisplayName("a user with no account of their own gets NO account - not the global one")
    void noGlobalFallback() {
        userBlob("bob", "{}");

        assertThat(settings.getOwnSettingsFieldsForUser("smb", "bob")).isEmpty();
        // The merged read DOES fall back, which is exactly why the connector must not use it.
        assertThat(settings.getSettingsFieldsForUser("smb", "bob")).containsEntry("password", "admins-password");
    }

    @Test
    @DisplayName("a user who never saved any settings at all gets no account either")
    void noRowAtAll() {
        when(users.findById("carol")).thenReturn(Optional.empty());

        assertThat(settings.getOwnSettingsFieldsForUser("smb", "carol")).isEmpty();
    }

    @Test
    @DisplayName("a user's own account is returned as they saved it")
    void ownValues() {
        userBlob("alice", "{\"smb.username\":\"CORP\\\\alice\",\"smb.password\":\"alices-password\"}");

        assertThat(settings.getOwnSettingsFieldsForUser("smb", "alice"))
                .containsEntry("username", "CORP\\alice")
                .containsEntry("password", "alices-password");
    }

    @Test
    @DisplayName("desktop has one user, so the global values are that user's own")
    void desktopReadsGlobal() {
        assertThat(settings.getOwnSettingsFieldsForUser("smb", null))
                .containsEntry("username", "CORP\\admin");
    }
}
