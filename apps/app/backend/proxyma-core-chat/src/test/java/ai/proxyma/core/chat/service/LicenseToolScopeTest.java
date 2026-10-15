package ai.proxyma.core.chat.service;

import ai.proxyma.contracts.license.LicenseTier;
import ai.proxyma.core.license.FeatureGateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The assistant is offered what the license includes and nothing else (2026-09-29). Since the
 * two-tier model (2026-10-14) Community includes every connector, so what can be left out is
 * Enterprise's alone - Microsoft 365, on an Enterprise Server with no license in force - and the
 * LICENSE line is empty everywhere else.
 */
class LicenseToolScopeTest {

    private static LicenseToolScope scope(LicenseTier tier, boolean multiUser) {
        FeatureGateService gate = new FeatureGateService();
        gate.refresh(tier);
        return new LicenseToolScope(gate, multiUser);
    }

    @Test
    @DisplayName("Community offers every connector's tools and has nothing to say in the prompt")
    void communityOffersEverything() {
        LicenseToolScope s = scope(LicenseTier.COMMUNITY, false);
        assertTrue(s.isSkillIncluded("Jira"));
        assertTrue(s.isToolIncluded("searchJiraIssues", Set.of("Jira")));
        assertTrue(s.isToolIncluded("useServer", Set.of("Jira", "Confluence", "Bitbucket")));
        assertTrue(s.isToolIncluded("addSource", Set.of("Connector Management")));
        assertTrue(s.isToolIncluded("removeSource", Set.of("Connector Management")));
        assertTrue(s.isToolIncluded("listSourceTypes", Set.of("Connector Management")));
        assertTrue(s.isToolIncluded("searchIndexedSources", Set.of()));
        assertEquals("", s.promptSection());
    }

    @Test
    @SuppressWarnings("deprecation")
    @DisplayName("a retired Personal or Professional license is worth exactly what Community is")
    void legacyTiersAreCommunity() {
        for (LicenseTier legacy : new LicenseTier[]{LicenseTier.PERSONAL, LicenseTier.PROFESSIONAL}) {
            LicenseToolScope s = scope(legacy, false);
            assertTrue(s.isToolIncluded("addSource", Set.of("Connector Management")));
            assertEquals("", s.promptSection());
        }
    }

    @Test
    @DisplayName("an Enterprise Server with no license says Microsoft 365 is left out; the source tools stay, and refuse it by type")
    void unlicensedServerLeavesOutMicrosoft() {
        LicenseToolScope s = scope(LicenseTier.COMMUNITY, true);
        // No tool is a connector's own any more: addSource lists and refuses Microsoft 365 by TYPE
        // (SourceTools, ConnectorService.register), so the tool itself stays offered.
        assertTrue(s.isToolIncluded("addSource", Set.of("Connector Management")));
        String line = s.promptSection();
        assertTrue(line.contains("Not included: Microsoft 365"), line);
        assertTrue(line.contains("Enterprise license"), line);
    }

    @Test
    @DisplayName("a licensed Enterprise Server offers Microsoft 365 and has nothing to say")
    void enterpriseIncludesMicrosoft() {
        LicenseToolScope s = scope(LicenseTier.ENTERPRISE, true);
        assertTrue(s.isToolIncluded("addSource", Set.of("Connector Management")));
        assertEquals("", s.promptSection());
    }
}
