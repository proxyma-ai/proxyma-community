/**
 * What `/?connection-oauth=<code>` means, and what to say about it - the generic OAuth2 sign-in of
 * a connection (OAuth2ConnectionService).
 *
 * THE REDIRECT CARRIES ONLY A CODE, NEVER TEXT: a link that carried its own message would let anyone
 * put a sentence on this deployment's screen by mailing somebody a URL.
 *
 * A PLAIN `.ts` WITH NO JSX AND NO IMPORTS, so `node --test` loads it (connectionSignIn.test.ts).
 */

/** The query parameter the callback appends to the landing URL. */
export const CONNECTION_SIGN_IN_PARAM = 'connection-oauth'

/** Every outcome the callback can report - OAuth2ConnectionService.Outcome's codes. */
export const CONNECTION_SIGN_IN_CODES = ['connected', 'denied', 'expired', 'failed', 'not_configured'] as const

export type ConnectionSignInCode = typeof CONNECTION_SIGN_IN_CODES[number]

/**
 * The English source strings, which double as the translation keys (i18n/catalogs/vi.ts). They
 * reach `t()` as a variable, so keep the Vietnamese in step by hand when this map changes.
 */
const MESSAGES: Record<ConnectionSignInCode, string> = {
  connected: 'Signed in. The connection is ready to use.',
  denied: 'The sign-in was declined, so the connection is not signed in.',
  expired: 'That sign-in link has expired. Start again.',
  failed: 'Signing in failed. Try again.',
  not_configured: "This service's sign-in is not set up. Give the connection its OAuth client, or ask an administrator to set one up.",
}

/**
 * The code a query string carries, or null when there is no sign-in to report. An UNRECOGNIZED
 * value is `failed`, not null: something came back, and saying nothing would read as the button
 * having done nothing.
 */
export function connectionSignInCode(search: string): ConnectionSignInCode | null {
  const raw = new URLSearchParams(search).get(CONNECTION_SIGN_IN_PARAM)
  if (raw == null) return null
  const lower = raw.toLowerCase()
  return (CONNECTION_SIGN_IN_CODES as readonly string[]).includes(lower) ? lower as ConnectionSignInCode : 'failed'
}

/** The English message for a code - the source string a translator keys off. */
export function connectionSignInMessage(code: ConnectionSignInCode): string {
  return MESSAGES[code]
}

/** Whether this outcome left the connection unusable - shown until dismissed, not for four seconds. */
export function isConnectionSignInFailure(code: ConnectionSignInCode): boolean {
  return code !== 'connected'
}

/** The same query string with the sign-in parameter taken out; every other parameter kept, in order. */
export function withoutConnectionSignInParam(search: string): string {
  const params = new URLSearchParams(search)
  if (!params.has(CONNECTION_SIGN_IN_PARAM)) return search.startsWith('?') ? search.slice(1) : search
  params.delete(CONNECTION_SIGN_IN_PARAM)
  return params.toString()
}
