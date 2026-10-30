package ai.proxyma.core.license;

import ai.proxyma.contracts.license.LicenseTier;

import java.time.Instant;

/**
 * What the frontend is told about this installation's license.
 *
 * <p>What the tier allows travels with it, so the page never restates {@link Features#forTier} in
 * TypeScript - a second copy free to drift from the one the server enforces. Since the two-tier
 * model (2026-10-14) that is three switches; Community has no limits left to send.
 *
 * <p>Adding fields to this record is safe - it is not part of {@code proxyma-shared-contracts} and
 * has exactly one consumer, this repository's own SPA, which ships inside the same jar.
 */
public record LicenseStatusResponse(
        LicenseTier tier,
        Instant expiresAt,
        boolean valid,
        /** More than one account, and SSO creating new ones (Features#isOrgModeAllowed). */
        boolean orgModeAllowed,
        /** Microsoft 365 sources: Enterprise only (Features#isMicrosoft365Allowed). */
        boolean microsoft365Allowed,
        /** Key accounts may be changed: Enterprise only (Features#isKeyAccountsAllowed). */
        boolean keyAccountsAllowed,
        /**
         * Whether the activated license has been re-confirmed with the license server lately, and
         * what to tell the user if not. Null on the paths that build a status before any re-check
         * has run, and always on the Community edition; Enterprise Server's license re-check fills
         * it in, at its controller.
         */
        RecheckSnapshot recheck,
        /** The license is a trial (a started trial, or a trial key): Enterprise until {@code expiresAt}. */
        boolean trial,
        /** When this deployment's trial ended - set once it has, so the page can say so. */
        Instant trialEndedAt,
        /** Where the Enterprise trial stands, by name (NONE, ASKING, GRANTED, ...); NONE off Enterprise Server. */
        String trialState,
        /**
         * A license or trial was in force here and has ended: the deployment keeps working and stops
         * growing, and the pages word their notices for that rather than for "not licensed yet".
         */
        boolean lapsed,
        /** Constellations' projects and agents may be changed: its add-on is in force (Features#isConstellationsAllowed). */
        boolean constellationsAllowed,
        /** The add-ons in force, by name (Features#addons). */
        java.util.List<String> addons) {

    public static LicenseStatusResponse of(LicenseTier tier, Instant expiresAt, boolean valid) {
        Features f = Features.forTier(tier);
        return new LicenseStatusResponse(tier, expiresAt, valid,
                f.isOrgModeAllowed(), f.isMicrosoft365Allowed(), f.isKeyAccountsAllowed(),
                null, false, null, "NONE", false, f.isConstellationsAllowed(), java.util.List.copyOf(f.addons()));
    }

    /** Same status, with the add-ons actually in force - a license's addons claim decides them, not its tier. */
    public LicenseStatusResponse withAddons(java.util.Collection<String> inForce) {
        Features f = Features.of(tier, inForce);
        return new LicenseStatusResponse(tier, expiresAt, valid, orgModeAllowed, microsoft365Allowed,
                keyAccountsAllowed, recheck, trial, trialEndedAt, trialState, lapsed, f.isConstellationsAllowed(),
                java.util.List.copyOf(f.addons()));
    }

    /** Same status, carrying the re-check snapshot the controller just read. */
    public LicenseStatusResponse withRecheck(RecheckSnapshot snapshot) {
        return new LicenseStatusResponse(tier, expiresAt, valid, orgModeAllowed, microsoft365Allowed,
                keyAccountsAllowed, snapshot, trial, trialEndedAt, trialState, lapsed, constellationsAllowed, addons);
    }

    /** Same status, marked as a trial or not. */
    public LicenseStatusResponse withTrial(boolean isTrial) {
        return new LicenseStatusResponse(tier, expiresAt, valid, orgModeAllowed, microsoft365Allowed,
                keyAccountsAllowed, recheck, isTrial, trialEndedAt, trialState, lapsed, constellationsAllowed, addons);
    }

    /** Same status, saying when this deployment's trial ended. */
    public LicenseStatusResponse withTrialEndedAt(Instant endedAt) {
        return new LicenseStatusResponse(tier, expiresAt, valid, orgModeAllowed, microsoft365Allowed,
                keyAccountsAllowed, recheck, trial, endedAt, trialState, lapsed, constellationsAllowed, addons);
    }

    /** Same status, with the trial's state and whether a license has lapsed. */
    public LicenseStatusResponse withTrialState(String state, boolean hasLapsed) {
        return new LicenseStatusResponse(tier, expiresAt, valid, orgModeAllowed, microsoft365Allowed,
                keyAccountsAllowed, recheck, trial, trialEndedAt, state, hasLapsed, constellationsAllowed, addons);
    }
}
