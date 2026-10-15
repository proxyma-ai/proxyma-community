package ai.proxyma.core.registry.service;

import ai.proxyma.core.connector.jira.JiraOwnerPreferences;
import ai.proxyma.core.connector.svn.SvnOwnerPreferences;
import ai.proxyma.core.settings.service.SettingsService;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The settings of a source's OWNER that a session reads - read on every session rather than stored on
 * the source: each is a setting of the person, and a source registered before they set it picks it
 * up on its next sync. The sessions depend on nothing of the application but proxyma-core-connector,
 * so they ask through these ports, and this is where the settings module answers.
 */
@Component
public class OwnerConnectorPreferences implements JiraOwnerPreferences, SvnOwnerPreferences {

    private final SettingsService settingsService;

    public OwnerConnectorPreferences(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /** The owner's Jira custom-field deny list. */
    @Override
    public String skipCustomFields(String ownerId) {
        return settingString("jira", "skip-custom-fields", ownerId);
    }

    /** The owner's {@code svn.indexed-extensions}. */
    @Override
    public String indexedExtensions(String ownerId) {
        return settingString("svn", "indexed-extensions", ownerId);
    }

    private String settingString(String prefix, String field, String userId) {
        Map<String, Object> fields = settingsService.getSettingsFieldsForUser(prefix, userId);
        Object value = fields != null ? fields.get(field) : null;
        if (value == null) return null;
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
