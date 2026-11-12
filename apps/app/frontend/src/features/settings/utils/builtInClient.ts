/**
 * A SIGN-IN WITH THE BUILT-IN CLIENT (2026-11-07, the maintainer: a creator "will just install the app, click login with
 * their Youtube account"). On Desktop a service with a built-in OAuth client (BuiltInOAuthClients on the server; the
 * catalog's `builtInClient`) signs in with no Client ID or secret typed, so the form leaves those two fields - and the
 * redirect address, which only a person's own client needs - out until the person chooses their own app. Plain .ts so
 * `node --test` reads it.
 */

/** The fields a person's own OAuth client is typed into, as every OAuth sign-in names them (OAuth2ConnectionService). */
export const CLIENT_FIELD_KEYS: readonly string[] = ['clientId', 'clientSecret']

/** Whether the sign-in goes through the built-in client: one is offered, and the person has not chosen their own. */
export function usesBuiltInClient(method: { builtInClient?: boolean } | undefined, ownClient: boolean): boolean {
  return !!method?.builtInClient && !ownClient
}

/** The form's fields without the client's own while the built-in client is used. */
export function fieldsForSignIn<T extends { key: string }>(fields: readonly T[], builtIn: boolean): T[] {
  return builtIn ? fields.filter(f => !CLIENT_FIELD_KEYS.includes(f.key)) : [...fields]
}

/** Whether a saved connection signs in with a client of its own: a Client ID is stored on it. */
export function hasOwnClient(settings: Record<string, unknown> | null | undefined): boolean {
  const id = settings?.clientId
  return typeof id === 'string' && id.trim() !== ''
}
