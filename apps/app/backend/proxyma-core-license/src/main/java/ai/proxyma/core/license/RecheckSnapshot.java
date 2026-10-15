package ai.proxyma.core.license;

import java.time.Instant;

/**
 * Whether the activated license has been re-confirmed with the license server lately, and what to
 * tell the user if not - what {@code /api/v1/license/status} carries alongside the tier. Everything
 * the banner and the modal need, already resolved: the frontend renders this, it does not compute it.
 *
 * <p>Filled in on Enterprise Server alone, by its license re-check (Proxyma Enterprise); here because
 * {@link LicenseStatusResponse} carries it and the Community edition answers {@code /status} too,
 * with none.
 */
public record RecheckSnapshot(
        State state,
        Reason reason,
        String message,
        Instant lastVerifiedAt,
        Instant graceEndsAt,
        long daysRemaining) {

    /** What the UI is told, and the only thing it needs to decide what to render. */
    public enum State {
        /** Verified inside the grace window, or no license to verify. Show nothing. */
        OK,
        /** Unverified, grace still running. Banner asking the user to get online. */
        WARNING,
        /**
         * The license was revoked: the deployment has lapsed, as when a trial or license ends -
         * nothing stops, nobody is signed out, and what Enterprise adds waits for a license.
         */
        RESTRICTED,
    }

    public enum Reason { NONE, UNREACHABLE, REVOKED, EXPIRED }

    /** Nothing to report. */
    public static RecheckSnapshot ok() {
        return new RecheckSnapshot(State.OK, Reason.NONE, null, null, null, 0);
    }
}
