package ai.proxyma.core.settings.service;

import ai.proxyma.contracts.license.LicenseTier;
import ai.proxyma.core.license.FeatureGateService;
import ai.proxyma.core.license.Features;
import ai.proxyma.core.settings.connection.ConnectionAccessPort;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The connectors' agent and MCP tools follow the license - a GATED-WRONG test since 0.10.0. Since the
 * two-tier model (2026-10-14) Jira, Confluence, Bitbucket and SVN are in Community: every tier allows
 * their tools, and only the per-connection switches (ToolConnectorResolver) and the user's own
 * consent stand in the way.
 */
class ConnectorToolTierTest {

    // No ConnectionAccessPort: nothing here resolves a connection, so the only thing left that can
    // refuse is the tier. The per-connection Read and Write switches are ToolConnectorResolver's.
    @SuppressWarnings("unchecked")
    private static McpGuardService guard(LicenseTier tier) {
        SettingsService settings = mock(SettingsService.class);
        FeatureGateService gate = mock(FeatureGateService.class);
        when(gate.getFeatures()).thenReturn(Features.forTier(tier));
        ObjectProvider<ConnectionAccessPort> none = mock(ObjectProvider.class);
        return new McpGuardService(settings, gate, none);
    }

    @Test
    @DisplayName("Community allows every connector's read and write tools")
    void communityAllows() {
        McpGuardService g = guard(LicenseTier.COMMUNITY);
        assertDoesNotThrow(() -> g.checkJiraRead("u"));
        assertDoesNotThrow(() -> g.checkJiraWrite("u"));
        assertDoesNotThrow(() -> g.checkConfluenceRead("u"));
        assertDoesNotThrow(() -> g.checkConfluenceWrite("u"));
        assertDoesNotThrow(() -> g.checkSvnRead("u"));
        assertDoesNotThrow(() -> g.checkBitbucketRead("u"));
        assertDoesNotThrow(() -> g.checkBitbucketWrite("u"));
    }

    @Test
    @DisplayName("with no connection to refuse it, a Community write is allowed and is no consent question")
    void communityWriteIsAllowed() {
        McpGuardService community = guard(LicenseTier.COMMUNITY);
        String write = WriteToolRegistry.JIRA_NAMES.iterator().next();
        assertFalse(community.isBlockedOnlyByUserConsent(write, "u"));
        assertNull(community.checkWriteAllowed(write, "u"));
    }
}
