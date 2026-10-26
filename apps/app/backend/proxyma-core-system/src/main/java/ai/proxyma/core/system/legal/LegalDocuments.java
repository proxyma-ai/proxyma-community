package ai.proxyma.core.system.legal;

// The single identifier for "which version of our legal documents is currently in force".
//
// Consent is only meaningful if it records WHAT was consented to. Storing a bare "accepted: true"
// against a user is worth very little the first time the documents change - there is then no way
// to tell who agreed to the old text and who agreed to the new. So account creation stores this
// exact string alongside the timestamp, and a future change to the documents means bumping this
// constant, which puts every existing user behind the re-consent gate (isStale below).
//
// It must be bumped in lockstep with the "Last updated" line on every document a user is asked to
// accept. Those documents are published on the site - the three that consent-requirements returns
// are (Vietnamese beside each under vi/):
//
//   apps/landing/src/content/legal/terms.html
//   apps/landing/src/content/legal/privacy.html
//   apps/landing/src/content/legal/eula.html
//
// It sat at 2026-09-14 through the Terms change of 09-21 and the Privacy change of 09-22 - found
// by the 0.16.0 review, and moved to that release's legal date.
//
// This comment used to name connectors/legal/terms-of-service.md and privacy-policy.md, which were
// deleted when the documents were consolidated onto the site. Pointing a maintainer at files that
// do not exist is how the constant came to sit two days behind the Privacy Policy: the policy was
// revised on 2026-08-06 and nothing here moved, so every consent recorded after that date named a
// version that was not the text the user had been shown.
//
// The EULA counts. It is returned by consent-requirements and the signup form asks users to accept
// it, so a change to it is a change to what consent means, exactly as for the other two.
public final class LegalDocuments {

    public static final String CURRENT_VERSION = "2026-10-26";

    /**
     * Whether an account holding {@code versionAccepted} still needs to accept the documents in
     * force. This is the whole of the re-consent gate's decision, in one place, so the API response
     * and any future caller cannot disagree about who is stale.
     *
     * <p>Null is stale. An account created before consent was collected accepted nothing, and its
     * row is deliberately not backfilled (see {@code User.legalVersionAccepted}) - so the gate is
     * the first time it is asked, which is the correct outcome rather than an edge case.
     *
     * <p>Any non-matching version is stale, not just an older one. Comparing dates would mean
     * parsing this constant as one, and a downgrade - a rollback to a previous release - should ask
     * again rather than silently accept a version this build does not serve.
     */
    public static boolean isStale(String versionAccepted) {
        return versionAccepted == null || !CURRENT_VERSION.equals(versionAccepted.trim());
    }

    private LegalDocuments() {}
}
