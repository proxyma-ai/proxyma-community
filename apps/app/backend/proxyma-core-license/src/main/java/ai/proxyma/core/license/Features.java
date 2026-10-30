package ai.proxyma.core.license;

import ai.proxyma.contracts.license.Addons;
import ai.proxyma.contracts.license.LicenseTier;
import ai.proxyma.core.connector.spi.Edition;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a tier allows - four switches, since the two-tier model (2026-10-14).
 *
 * <p>COMMUNITY - Proxyma Desktop and the single-user server, free - has every connector and no
 * limits: no cap on folders, files or web pages, subfolders everywhere, Jira, Confluence, Bitbucket,
 * SVN and network shares. What ENTERPRISE adds is what a multi-user server sells: more than one
 * account and SSO creating new ones (org mode), Microsoft 365, and key accounts. The retired PERSONAL
 * and PROFESSIONAL are worth exactly what COMMUNITY is.
 *
 * <p>ADD-ONS ({@link Addons}) are applications a license names in its {@code addons} claim:
 * Constellations is one. An Enterprise license names every add-on (or none at all, which reads as
 * every one); on Desktop and Home Server each add-on has a key of its own, and COMMUNITY plus the
 * add-ons those keys grant is what is in force ({@link #of}).
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
    // Constellations - saved agents working in projects (agent teams). An ADD-ON: granted by a
    // license's addons claim, on any edition, rather than by the tier.
    private final boolean constellationsAllowed;
    // The add-ons in force, by name (Addons), sorted.
    private final Set<String> addons;
    // Enterprise deployments never ship diagnostic or rating data off their own infrastructure;
    // proxyma-core-telemetryclient's gates read this beside the edition.
    private final boolean externalTelemetryAllowed;

    private Features(boolean orgModeAllowed, boolean microsoft365Allowed, boolean keyAccountsAllowed,
                     Set<String> addons, boolean externalTelemetryAllowed) {
        this.addons = Set.copyOf(new TreeSet<>(addons));
        this.constellationsAllowed = this.addons.contains(Addons.CONSTELLATIONS);
        this.orgModeAllowed = orgModeAllowed;
        this.microsoft365Allowed = microsoft365Allowed;
        this.keyAccountsAllowed = keyAccountsAllowed;
        this.externalTelemetryAllowed = externalTelemetryAllowed;
    }

    private static final Features ENTERPRISE = new Features(true, true, true, Set.copyOf(Addons.ALL), false);
    private static final Features COMMUNITY = new Features(false, false, false, Set.of(), true);

    /** What a tier allows with the add-ons it carries by default: every one for ENTERPRISE, none otherwise. */
    public static Features forTier(LicenseTier tier) {
        return tier == LicenseTier.ENTERPRISE ? ENTERPRISE : COMMUNITY;
    }

    /** What a tier allows with exactly these add-ons in force. Unknown names are dropped. */
    public static Features of(LicenseTier tier, Collection<String> addons) {
        Set<String> known = Addons.known(addons);
        boolean enterprise = tier == LicenseTier.ENTERPRISE;
        return new Features(enterprise, enterprise, enterprise, known, !enterprise);
    }

    public boolean isOrgModeAllowed()           { return orgModeAllowed; }
    public boolean isMicrosoft365Allowed()      { return microsoft365Allowed; }
    public boolean isKeyAccountsAllowed()       { return keyAccountsAllowed; }
    public boolean isConstellationsAllowed()    { return constellationsAllowed; }
    public boolean isExternalTelemetryAllowed() { return externalTelemetryAllowed; }
    /** The add-ons in force, by name, sorted. */
    public Set<String> addons()                 { return addons; }
    public boolean hasAddon(String name)        { return addons.contains(name); }

    /**
     * Whether a connector of this edition is licensed here: every COMMUNITY one is, an ENTERPRISE one
     * (Microsoft 365) needs Enterprise. The edition comes from the connector's descriptor
     * (ConnectorCatalog.edition), so a new connector is gated by what it declares.
     */
    public boolean isConnectorAllowed(Edition edition) {
        return edition != Edition.ENTERPRISE || microsoft365Allowed;
    }
}
