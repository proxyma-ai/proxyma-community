package ai.proxyma.core.license;

import ai.proxyma.contracts.license.LicenseTier;
import ai.proxyma.core.connector.spi.Edition;
import org.springframework.stereotype.Service;

/**
 * What the license in force allows, read live by every gate.
 *
 * <p>Since the two-tier model (2026-10-14) there are no limits to check: Community has every
 * connector, no folder, file or web-page cap. What is left is what Enterprise sells - Microsoft 365
 * ({@link #checkConnector}), accounts ({@link #isNewAccountAllowed}), and key accounts and org
 * mode, which their own modules ask {@link #getFeatures()} about.
 */
@Service
public class FeatureGateService {

    // Starts on COMMUNITY - the only legitimate way to reach a higher tier is refresh(), called
    // by LicenseActivationService after a real RS256-verified license JWT is activated. There
    // used to be a "-Dproxyma.license.tier=<tier>" JVM-flag override read at startup here with no
    // cryptographic verification at all - anyone launching proxyma-server/proxyma-desktop could
    // pass that flag and get every Enterprise feature for free, bypassing
    // LicenseActivationService/LicenseJwtVerifier entirely. Removed; the enterprise deployment (the only real user of
    // that flag) now activates via a real signed license instead - see LicenseActivationService's
    // PROXYMA_LICENSE_ACTIVATION_KEY bootstrap path.
    private volatile Effective effective = new Effective(LicenseTier.COMMUNITY, false);
    private volatile Features features = Features.forTier(LicenseTier.COMMUNITY);
    private volatile Lapse lapse = Lapse.NONE;

    /**
     * Why there is no license in force: never was one, or one ended - a trial ran out, or a license
     * expired or was revoked (LicenseRecheckService). Only the wording differs.
     */
    public enum Lapse { NONE, TRIAL_ENDED, LICENSE_ENDED }

    /** The tier being enforced and whether it comes from a trial, read together. */
    public record Effective(LicenseTier tier, boolean trial) {}

    // refresh() and markLapsed() are the PORT the license itself drives: Enterprise Server's license
    // activation, re-check and trial (Proxyma Enterprise, ai.proxyma.ee.license) call them, and
    // nothing in the Community edition does - so there the gate stays on COMMUNITY for good.
    public void refresh(LicenseTier tier) {
        refresh(tier, false);
    }

    public void refresh(LicenseTier tier, boolean trial) {
        features = Features.forTier(tier);
        effective = new Effective(tier, trial && tier != LicenseTier.COMMUNITY);
        if (tier == LicenseTier.ENTERPRISE) lapse = Lapse.NONE;
    }

    /**
     * A license or trial was in force and has ended: its expiry passed (LicenseActivationService)
     * or the license server revoked it (LicenseRecheckService).
     */
    public void markLapsed(boolean trial) {
        lapse = trial ? Lapse.TRIAL_ENDED : Lapse.LICENSE_ENDED;
    }

    public Lapse lapse() { return lapse; }

    public LicenseTier getCurrentTier() { return effective.tier(); }

    public Effective effective() { return effective; }

    public Features getFeatures() { return features; }

    /** What a Microsoft 365 action without Enterprise is told, wherever it was attempted. */
    public static final String MICROSOFT_365_MESSAGE = "Microsoft 365 needs an Enterprise license.";

    /**
     * Refuses an ENTERPRISE connector without an Enterprise license; every COMMUNITY one is in every
     * tier. {@code service} names it in the refusal - "Microsoft 365 needs an Enterprise license."
     */
    public void checkConnector(String service, Edition edition) {
        if (!features.isConnectorAllowed(edition)) {
            throw new NotLicensedException(service + " needs an Enterprise license.");
        }
    }

    /** What every refused account creation says, wherever it was attempted - before any license. */
    public static final String ACCOUNT_LIMIT_MESSAGE =
            "Additional accounts need an Enterprise license. Without one, Proxyma Enterprise Server "
            + "has a single account.";

    /** The same refusal once a trial or a license has ended: nobody loses access, nobody is added. */
    public String accountLimitMessage() {
        return switch (lapse) {
            case TRIAL_ENDED -> "Your Enterprise trial has ended. Existing accounts keep working; "
                    + "adding people needs an Enterprise license.";
            case LICENSE_ENDED -> "Your Enterprise license has ended. Existing accounts keep working; "
                    + "adding people needs an Enterprise license.";
            case NONE -> ACCOUNT_LIMIT_MESSAGE;
        };
    }

    /**
     * Whether a NEW account may be created on the multi-user edition, given how many exist.
     *
     * <p>ACCOUNTS ARE WHAT ENTERPRISE SELLS. Until 2026-09-13 nothing checked the tier before
     * creating one - org mode gated SSO, quotas and spend limits, while self-registration and the
     * admin API made any number of accounts on an unlicensed install of a public image. So:
     * Enterprise creates freely; anything else gets exactly one account, the first administrator,
     * which keeps "install it and look at it" possible before a license is issued.
     *
     * <p>CREATION ONLY. Nothing here signs anyone out: an Enterprise license that lapses on a
     * deployment with fifty users leaves all fifty working and refuses the fifty-first: an ended
     * or revoked license keeps the server working and only stops it growing.
     */
    public boolean isNewAccountAllowed(long existingAccounts) {
        return isNewAccountAllowed(existingAccounts, features.isOrgModeAllowed());
    }

    static boolean isNewAccountAllowed(long existingAccounts, boolean orgModeAllowed) {
        return orgModeAllowed || existingAccounts == 0;
    }
}
