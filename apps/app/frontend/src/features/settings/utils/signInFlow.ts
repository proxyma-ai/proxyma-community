/**
 * HOW A CONNECTION SIGNS IN, AND WHEN THE WAIT FOR IT ENDS (2026-10-31, the maintainer: sign in "like
 * the other apps" - the browser opens, you sign in there, Proxyma updates by itself).
 *
 * - `browser`: Desktop and Home Server. The page asks the server for the service's sign-in address
 *   and OPENS it - in the Desktop window that is the system browser (the window hands every other
 *   origin to it), in a browser a new tab - then watches the connection until it reads signed in.
 *   Google refuses a sign-in inside an embedded window, and the system browser shares no cookie with
 *   the Desktop window, which is why the server accepts this flow on its single-use state alone
 *   (OAuth2ConnectionService's class note).
 * - `navigate`: Enterprise Server, and a service whose sign-in is the edition's own (Microsoft 365).
 *   The page itself goes to the service and comes back with `?connection-oauth=<outcome>`.
 *
 * Plain .ts with no imports, so `node --test` can read it.
 */

export type SignInRoute = 'browser' | 'navigate'

/** How often the page asks whether a sign-in in the browser has finished. */
export const SIGN_IN_POLL_MS = 2000

/** How long it keeps asking: the sign-in link is good for ten minutes, nobody waits that long on purpose. */
export const SIGN_IN_WAIT_MS = 5 * 60_000

export function signInRoute(opts: { multiUser: boolean; editionOwn: boolean }): SignInRoute {
  return opts.multiUser || opts.editionOwn ? 'navigate' : 'browser'
}

/** A sign-in opened in the browser: when, the connectedAt it is waiting to see change, and its address. */
export interface SignInWait {
  startedAt: number
  since: string | null
  url: string
  /** The connection's last refused or failed sign-in when the wait began (its `at`), so only a new one counts. */
  failedSince?: string | null
}

/** A refused or failed sign-in as the server remembers it on the connection (OAuth2ConnectionService.SignInFailure). */
export interface SignInFailure {
  outcome: string
  at: string
}

export type SignInWaitState = 'done' | 'incomplete' | 'refused' | 'failed' | 'waiting' | 'timeout'

/**
 * Done once the connection reads signed in with a sign-in time other than the one it had when the
 * wait began - a connection signed in before ("Sign in again") is not done until it signs in anew.
 * Incomplete when that new sign-in left out something the connection needs. Refused or failed when the
 * server remembers a sign-in that came back so after the wait began.
 */
export function signInWaitState(
  wait: SignInWait,
  connection: { status?: string | null; connectedAt?: string | null; signInFailure?: SignInFailure | null } | undefined,
  now: number,
): SignInWaitState {
  if (connection && !!connection.connectedAt && connection.connectedAt !== wait.since) {
    if (connection.status === 'CONNECTED') return 'done'
    // Signed in anew, but with a permission the connection needs left unticked on the consent screen
    // (2026-11-08, OAuth2ConnectionService's granular consent): the server kept the sign-in and marked it
    // as needing another. Nothing else moves the sign-in time AND leaves it needing one.
    if (connection.status === 'NEEDS_RECONNECT') return 'incomplete'
  }
  // A sign-in that came back refused on the consent screen, or failed, since the wait began (2026-11-09, the
  // maintainer: "the login failed or the user refuse the consent") - the wait ends at once and says which.
  const failure = connection?.signInFailure
  if (failure && failure.at !== (wait.failedSince ?? null)) return failure.outcome === 'DENIED' ? 'refused' : 'failed'
  return now - wait.startedAt > SIGN_IN_WAIT_MS ? 'timeout' : 'waiting'
}
