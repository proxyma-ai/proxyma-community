// Where to land after an OAuth2 sign-in. The ?next= that RequireAuth puts on /login survives a
// password login (LoginPage navigates there itself), but it cannot survive the OAuth2 round-trip:
// the browser leaves for the provider's own domain and comes back to the backend's success
// handler, which always redirects to "/". Stashing the target here before leaving, and consuming
// it on the "/" landing, is what keeps a link someone was SENT - typically /shared/{token} - from
// being lost the moment sign-in is required to open it.
//
// sessionStorage, not localStorage: the OAuth flow is one tab's navigation, and a stale target
// must not leak into other tabs or outlive the browser session that started it.

import { safeLocalPath } from '@/components/ui/kit'

const KEY = 'proxyma.postLoginTarget'

// Long enough for a real SSO sign-in (MFA prompts, password resets), short enough that an
// abandoned flow cannot silently redirect a later visit.
const MAX_AGE_MS = 10 * 60 * 1000

// Only same-origin relative paths. An absolute URL, a protocol-relative "//host" or a "/\host"
// (which the browser reads as "//host") would turn this into an open redirect - the same rule
// LoginPage applies to ?next=. `safeLocalPath` is the kit's, and tested.
function isSafeRelativePath(path: string): boolean {
  return safeLocalPath(path) !== null
}

export function rememberPostLoginTarget(path: string): void {
  if (!isSafeRelativePath(path) || path === '/') return
  try {
    sessionStorage.setItem(KEY, JSON.stringify({ path, at: Date.now() }))
  } catch {
    // Storage disabled (private mode, hardened browser). Sign-in still works; it just lands on
    // the app root, which is exactly today's behavior.
  }
}

// Reads and clears in one step - a target is only ever honored once, even if the read decides
// not to use it.
export function takePostLoginTarget(): string | null {
  try {
    const raw = sessionStorage.getItem(KEY)
    sessionStorage.removeItem(KEY)
    if (!raw) return null
    const { path, at } = JSON.parse(raw) as { path?: unknown; at?: unknown }
    if (typeof path !== 'string' || typeof at !== 'number') return null
    if (Date.now() - at > MAX_AGE_MS) return null
    return isSafeRelativePath(path) ? path : null
  } catch {
    return null
  }
}
