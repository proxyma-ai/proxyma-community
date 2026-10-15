package ai.proxyma.core.license;

import ai.proxyma.contracts.license.LicenseTier;
import ai.proxyma.core.connector.spi.Edition;

/**
 * What a tier allows - four switches, since the two-tier model (2026-10-14).
 *
 * <p>COMMUNITY - Proxyma Desktop and the single-user server, free - has every connector and no
 * limits: no cap on folders, files or web pages, subfolders everywhere, Jira, Confluence, Bitbucket,
 * SVN and network shares. What ENTERPRISE adds is what a multi-user server sells: more than one
 * account and SSO creating new ones (org mode), Microsoft 365, and key accounts. The retired PERSONAL
 * and PROFESSIONAL are worth exactly what COMMUNITY is.
 *
 * <p>Quotas and spend limits are NOT here: they are a protection rather than a perk, and are on for
 * every Enterprise Server whatever its license (proxyma-core-auth, which exists only there).
 */
public final class Features {

    // More than one account, SSO creating a NEW account, and the org-mode setting. Creation only:
    // nothing here signs anybody out (FeatureGateService.isNewAccountAllowed).
    private final boolean orgModeAllowed;
    // OneDrive, SharePoint, Teams channels, Teams chats and Outlook - every connector whose descriptor names
    // the ENTERPRISE edition. ALSO Enterprise Server only whatever this says, an edition gate its
    // callers keep (Edition.offeredOn).
    private final boolean microsoft365Allowed;
    // Key accounts (docs/architecture.html#spec-11). A capability of its own rather than a reading of
    // orgModeAllowed: "may this deployment manage users" and "may it run account reviews" are
    // different questions, and the day one moves the other must not move with it.
    private final boolean keyAccountsAllowed;
    // Enterprise deployments never ship diagnostic or rating data off their own infrastructure;
    // proxyma-core-telemetryclient's gates read this beside the edition.
    private final boolean externalTelemetryAllowed;

    private Features(boolean orgModeAllowed, boolean microsoft365Allowed, boolean keyAccountsAllowed,
                     boolean externalTelemetryAllowed) {
        this.orgModeAllowed = orgModeAllowed;
        this.microsoft365Allowed = microsoft365Allowed;
        this.keyAccountsAllowed = keyAccountsAllowed;
        this.externalTelemetryAllowed = externalTelemetryAllowed;
    }

    private static final Features ENTERPRISE = new Features(true, true, true, false);
    private static final Features COMMUNITY = new Features(false, false, false, true);

    public static Features forTier(LicenseTier tier) {
        return tier == LicenseTier.ENTERPRISE ? ENTERPRISE : COMMUNITY;
    }

    public boolean isOrgModeAllowed()           { return orgModeAllowed; }
    public boolean isMicrosoft365Allowed()      { return microsoft365Allowed; }
    public boolean isKeyAccountsAllowed()       { return keyAccountsAllowed; }
    public boolean isExternalTelemetryAllowed() { return externalTelemetryAllowed; }

    /**
     * Whether a connector of this edition is licensed here: every COMMUNITY one is, an ENTERPRISE one
     * (Microsoft 365) needs Enterprise. The edition comes from the connector's descriptor
     * (ConnectorCatalog.edition), so a new connector is gated by what it declares.
     */
    public boolean isConnectorAllowed(Edition edition) {
        return edition != Edition.ENTERPRISE || microsoft365Allowed;
    }
}
