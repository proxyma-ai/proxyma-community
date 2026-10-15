package ai.proxyma.core.settings.usage;

import ai.proxyma.core.settings.dto.CostCenterSettings;
import ai.proxyma.core.settings.service.SettingsService;
import org.springframework.stereotype.Service;

/**
 * Answers "is this kind of call charged to the user it was made for?" - see CostCenterSettings.
 * Read live, so a super admin's change applies from the next call.
 */
@Service
public class CostCenterService {

    private final SettingsService settingsService;

    public CostCenterService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /** True when calls of this role (an LlmRole name) are charged to the user, not the platform. */
    public boolean chargedToUser(String role) {
        if (role == null) return false;
        try {
            String center = settingsService.getSettings(CostCenterSettings.PREFIX, CostCenterSettings.class)
                    .byRole().get(role);
            return CostCenterSettings.USER.equalsIgnoreCase(center);
        } catch (Exception e) {
            // Unreadable settings fall back to the default: chat alone is the user's.
            return "CHAT".equals(role);
        }
    }

    /**
     * Every role's cost center, defaults included - what the Administration page shows. An ADMIN
     * reads it there without being allowed to read the setting itself (a super admin's prefix),
     * so it reaches the page through the usage endpoint rather than the settings API.
     */
    public java.util.Map<String, String> byRole() {
        try {
            return settingsService.getSettings(CostCenterSettings.PREFIX, CostCenterSettings.class).byRole();
        } catch (Exception e) {
            return new CostCenterSettings().byRole();
        }
    }
}
