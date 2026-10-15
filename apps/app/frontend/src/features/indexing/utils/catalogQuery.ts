/**
 * Whether the page may ask for the connector catalog yet.
 *
 * GET /api/v1/connectors/catalog needs a session on Enterprise Server (SecurityConfig's blanket
 * /api/v1/** rule), and AppShell asks for it as the app starts - on the signed-out page and the
 * guest chat page too. Asked there, it answered 401: an error in every visitor's console and, on
 * a browser still holding a refresh cookie, a request httpClient's interceptor answers by trying to
 * re-issue the session - the shape useLicenseStatus already describes. So: always where nobody signs in (Desktop, Home Server),
 * otherwise once somebody has. A visitor has no sources to draw marks for.
 */
export function mayAskForCatalog(accountsEdition: boolean, signedIn: boolean): boolean {
  return !accountsEdition || signedIn
}
