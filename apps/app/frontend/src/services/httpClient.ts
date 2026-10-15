import axios from 'axios'
import { ENV } from '@/config/env'
// A plain zustand store with no imports of its own beyond zustand, so reaching for it here cannot
// close an import cycle back through this module the way any hook or api module would.
import { useAuthStore } from '@/store/authStore'
import { useAccessStore } from '@/store/accessStore'
import { serverText } from '@/i18n/serverText'

// The access cookie (PROXYMA-TOKEN) lives as long as the token inside it - 24h - well short of the
// 30-day refresh cookie (PROXYMA-REFRESH) backing it, so without this every user hit a hard logout
// once a day despite holding a still-valid refresh token. Single in-flight refresh shared across
// concurrent 401s so a burst of parallel requests triggers one POST /auth/refresh, not one per
// request.
const REFRESH_URL = '/api/v1/auth/refresh'

// /auth/login's own 401 means "wrong credentials" (see AuthController.login's SecurityException
// branch) - there is no stale cookie to refresh and no session to lose, and LoginPage's .catch
// already turns it into an error message. It is the one 401 in this app that neither recovery
// step below has anything to offer.
const NO_RECOVERY_URLS = ['/api/v1/auth/login']

// /auth/me is the page-load session probe, and its 401 is AMBIGUOUS in a way the rest of this file
// is not: it means either "nobody is signed in" or "your access cookie has been dropped by the
// browser while the 30d refresh cookie behind it is still perfectly good". The refresh cookie is
// path-scoped to /auth/refresh, so it is never sent to /auth/me and the backend's own silent
// refresh (JwtAuthFilter.authenticateWithRefreshCookie) cannot see it either - only this client
// can tell the two apart, by asking.
//
// So the probe DOES attempt a refresh, and only refuses to redirect. Treating it as unrecoverable
// is what made a returning user look like an anonymous guest - guest banner, no history, chat
// metered on the guest allowance - until some other authenticated query happened to 401, refreshed
// the cookie behind the scenes, and the page "logged itself in" minutes later.
const SESSION_PROBE_URLS = ['/api/v1/auth/me']

let refreshPromise: Promise<boolean> | null = null

export function refreshAccessToken(): Promise<boolean> {
  if (!refreshPromise) {
    refreshPromise = http.post(REFRESH_URL)
      .then(() => true)
      .catch(() => false)
      .finally(() => { refreshPromise = null })
  }
  return refreshPromise
}

export const http = axios.create({
  baseURL: ENV.apiBaseUrl,
  withCredentials: true,
})

// THE SERVER'S REASON, IN THE READER'S LANGUAGE - before anything else sees the error. A refusal
// carries its sentence in `error`, `message` or `detail` (see apiError.ts), in English, and some
// thirty call sites read one of those fields directly. Translating the body here, once, covers all
// of them and every one written later; a sentence the catalog does not know is left as it came, and
// so is a machine code (`tier_limit`), which is not in any catalog. The English is kept beside it
// as `untranslated`, for the one or two places that route a refusal by its wording.
const REASON_FIELDS = ['error', 'message', 'detail'] as const
function translateReason(err: { response?: { data?: unknown } }) {
  const data = err.response?.data
  if (!data || typeof data !== 'object') return
  const body = data as Record<string, unknown>
  for (const field of REASON_FIELDS) {
    const value = body[field]
    if (typeof value !== 'string' || !value) continue
    const translated = serverText(value)
    if (translated === value) continue
    body.untranslated = { ...(body.untranslated as object | undefined), [field]: value }
    body[field] = translated
  }
}

http.interceptors.response.use(
  res => res,
  async err => {
    translateReason(err)
    const status = err.response?.status
    if (status !== 401) return Promise.reject(err)

    // Desktop and the Home Server asking for their access code (AccessGate): no session to
    // refresh and no sign-in page - the access screen takes the page's place.
    if ((err.response?.data as { error?: unknown } | undefined)?.error === 'access_required') {
      useAccessStore.getState().lock()
      return Promise.reject(err)
    }

    const original = err.config as (typeof err.config & { _retried?: boolean }) | undefined
    const url = original?.url
    if (url && NO_RECOVERY_URLS.includes(url)) return Promise.reject(err)

    const isRefreshCall = url === REFRESH_URL
    if (!isRefreshCall && original && !original._retried) {
      original._retried = true
      if (await refreshAccessToken()) return http(original)
    }

    // The probe has had its refresh and it did not help, so nobody is signed in - which is a
    // normal state for this app, not a lapsed session. Its callers (LoginPage's .catch, useAuth's
    // `data ?? null`) already read the rejection as "no user"; redirecting would send an anonymous
    // visitor to /login from the chat page they are allowed to be on.
    if (url && SESSION_PROBE_URLS.includes(url)) return Promise.reject(err)

    // Refresh failed (or this 401 was the refresh call itself, whose own cookie is now the stale
    // one) - the session is genuinely gone. Force back to the login page instead of leaving every
    // subsequent authenticated action silently broken - this used to only fire outside a
    // PUBLIC_PATHS allowlist that included Chat, the single most-used page, which is exactly why a
    // token expiring mid-conversation left the UI looking normal while quietly failing.
    //
    // ONLY WHEN THERE WAS A SESSION TO LOSE. "A session expired" and "there was never one" are
    // different situations that produce the identical 401, and only the first is worth taking the
    // page away for. Chat is open to anonymous visitors in server mode, so a guest whose page
    // happens to touch an authenticated endpoint was being redirected to /login by this line -
    // which is what made server-mode chat look like it demanded an account. The two offenders are
    // fixed at their own call sites (useUserQuota, useProviderConnectivity); this is the backstop,
    // so the next query that forgets to gate itself degrades instead of hijacking the page.
    if (!useAuthStore.getState().user) return Promise.reject(err)

    window.location.href = '/login'
    return Promise.reject(err)
  },
)
