// WHICH OF THE SHELL'S OWN DIALOGS IS UP, decided in one place and in one order. Plain .ts with no
// JSX so `node --test` reaches it (startupDialogs.test.ts).
//
// Four things can want the screen when the app opens, and before this they each decided alone:
// the deletion gate, the consent gate, "What's new" and the walkthrough. The walkthrough started
// as soon as the user's onboarding settings arrived, so an account that had to accept the terms
// first - every account an administrator creates, which has accepted nothing yet - got the
// walkthrough and the consent gate at once, one over the other (maintainer's report, 2026-10-15).
//
// The order, each waiting for everything before it:
//   1. the deletion gate - a PENDING_DELETION account can call nothing but /me, /logout and the
//      deletion endpoints, so nothing after it could do its job;
//   2. the consent gate - blocking and not dismissable; its only alternative is signing out, which
//      reloads the page signed out, so the walkthrough never starts for someone who declined;
//   3. "What's new";
//   4. the walkthrough, which needs the whole page to itself.
//
// Desktop and Home Server have no accounts, so steps 1 and 2 never apply there.

export interface StartupUser {
  accountStatus?: string | null
  consentStale?: boolean | null
}

export interface StartupInput {
  /** The build has accounts (FEATURES.auth) - Enterprise Server. */
  accounts: boolean
  /** The signed-in account; null or undefined when nobody is signed in or it is still loading. */
  user: StartupUser | null | undefined
  /** "Keep waiting" was pressed on the deletion gate this session. */
  deletionGateDismissed: boolean
  /** This browser has not yet seen the running version's "What's new". */
  whatsNewPending: boolean
}

export interface StartupDialogs {
  deletionGate: boolean
  reConsent: boolean
  whatsNew: boolean
  /** The walkthrough may run: started if this user has not finished it, shown if it is running. */
  tour: boolean
}

export function startupDialogs({ accounts, user, deletionGateDismissed, whatsNewPending }: StartupInput): StartupDialogs {
  const pendingDeletion = accounts && user?.accountStatus === 'PENDING_DELETION'
  const deletionGate = pendingDeletion && !deletionGateDismissed
  // Not merely "behind the deletion gate": a pending-deletion account cannot record consent at all
  // (the lockout filter refuses POST /me/consent), so a dismissed deletion gate does not let it up.
  const reConsent = accounts && !pendingDeletion && user?.consentStale === true
  const whatsNew = whatsNewPending && !deletionGate && !reConsent
  // With accounts, only for somebody signed in, whose account is in use and who has agreed to the
  // current terms. A pending-deletion account is left out even with its gate dismissed: its
  // onboarding settings are refused like everything else.
  const accountReady = !accounts || (!!user && !pendingDeletion && user.consentStale !== true)
  const tour = accountReady && !whatsNew
  return { deletionGate, reConsent, whatsNew, tour }
}
