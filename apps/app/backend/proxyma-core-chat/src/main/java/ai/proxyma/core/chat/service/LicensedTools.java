package ai.proxyma.core.chat.service;

import org.springframework.stereotype.Component;

/**
 * Whether this installation's plan includes a tool - the rule the agent's advertised list follows
 * (ToolSkillGating, LicenseToolScope), for the tool catalogs outside the chat to list the same set.
 */
@Component
public class LicensedTools {

    private final ToolSkillGating gating;

    LicensedTools(ToolSkillGating gating) {
        this.gating = gating;
    }

    public boolean isLicensed(String toolName) {
        return gating.isLicensed(toolName);
    }
}
