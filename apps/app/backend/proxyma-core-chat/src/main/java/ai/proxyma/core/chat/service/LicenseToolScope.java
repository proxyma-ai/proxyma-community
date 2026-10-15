package ai.proxyma.core.chat.service;

import ai.proxyma.core.chat.module.ChatModule;
import ai.proxyma.core.license.FeatureGateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What THIS installation's license lets the assistant offer - since 2026-09-29, at the
 * maintainer's request.
 *
 * <p>The license used to be checked only when a tool was CALLED (McpGuardService). Every tool and
 * skill was still advertised, so the model offered what the license left out and learned otherwise
 * only from a refused call - or never, and told the user the feature did not exist. Now one place
 * answers "is this included", and three things ask it: the tools advertised each call
 * (ToolSkillGating), the skill list in the system prompt (ChatSkillService), and the LICENSE line of
 * the prompt (ChatService). The call-time check stays: this decides what is OFFERED, McpGuardService
 * what is ALLOWED.
 *
 * <p>Since the two-tier model (2026-10-14) Community has every connector; what a license still
 * leaves out is Enterprise's - Microsoft 365, and a product module such as key accounts - and only
 * on Enterprise Server, the one edition that has them at all.
 *
 * <p>Read live from FeatureGateService on every call, so a license activated, or a trial that
 * ends, takes effect on the next model call with no restart.
 */
@Component
public class LicenseToolScope {

    // No tool is any connector's own any more: sources of every type are added through one set of
    // tools (SourceTools), and what the license leaves out is decided per TYPE - listSourceTypes
    // lists only the licensed ones and ConnectorService.register refuses the rest. So a tool is in
    // the plan exactly when its skill is.

    private final FeatureGateService featureGate;
    // Enterprise Server: the one edition where something can be left out of the license.
    private final boolean multiUser;
    // A product module's skills are in the plan while the module says so (ChatModule.licensed) - the
    // key-account skill only on an Enterprise license. Setter-injected: tests build this by hand.
    private List<ChatModule> modules = List.of();

    public LicenseToolScope(FeatureGateService featureGate) {
        this(featureGate, false);
    }

    @Autowired
    public LicenseToolScope(FeatureGateService featureGate, @Value("${proxyma.multi-user:false}") boolean multiUser) {
        this.featureGate = featureGate;
        this.multiUser = multiUser;
    }

    @Autowired(required = false)
    void setModules(List<ChatModule> modules) {
        this.modules = modules == null ? List.of() : List.copyOf(modules);
    }

    /** Whether a skill's subject is in this plan. A skill with no licensed subject always is. */
    public boolean isSkillIncluded(String skillName) {
        for (ChatModule module : modules) {
            if (module.skillNames().stream().anyMatch(n -> n.equalsIgnoreCase(skillName))) {
                try {
                    return module.licensed();
                } catch (RuntimeException e) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Whether a tool may be advertised. A tool of a skill whose subject is out of the plan is out
     * too, and so is a licensed tool of a mixed skill.
     */
    public boolean isToolIncluded(String toolName, Set<String> skillsListingIt) {
        if (skillsListingIt.isEmpty()) return true;
        return skillsListingIt.stream().anyMatch(this::isSkillIncluded);
    }

    /**
     * The LICENSE line of the system prompt, or "" when nothing is left out - always on Desktop and
     * the single-user server. On Enterprise Server without a license in force it says what is NOT
     * included, so the model neither offers it nor denies it exists.
     */
    public String promptSection() {
        if (!multiUser) return "";
        List<String> missing = new ArrayList<>();
        if (!featureGate.getFeatures().isMicrosoft365Allowed()) missing.add("Microsoft 365");
        for (ChatModule module : modules) {
            boolean licensed;
            try {
                licensed = module.licensed();
            } catch (RuntimeException e) {
                licensed = false;
            }
            if (!licensed) missing.addAll(module.skillNames());
        }
        if (missing.isEmpty()) return "";
        return "LICENSE - this server has no Enterprise license in force. Not included: "
                + String.join(", ", missing)
                + ". Never offer them; asked for one, say it needs an Enterprise license."
                + " License: [Settings > License](app://settings/license).";
    }
}
