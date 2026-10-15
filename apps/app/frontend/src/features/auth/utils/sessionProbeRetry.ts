/**
 * WHETHER THE SESSION PROBE (`GET /auth/me`) IS ASKED AGAIN, and when.
 *
 * Its answer decides the whole page: while it is unanswered the app shows its boot screen, a 401
 * means nobody is signed in, and a user means the app. It was asked once. A 429 - the rate limiter
 * after a burst from the same person's other tabs - or a server mid-restart was read as "nobody is
 * signed in" or left the boot screen up, and only a reload helped (found 2026-10-06). Those answers
 * say "not now", not "no": so the probe is asked again, after what the limiter says to wait
 * (`Retry-After`) or a growing pause, and a 401 or 403 is final at once.
 */
export const SESSION_PROBE_MAX_RETRIES = 8
const BASE_DELAY_MS = 1000
const MAX_DELAY_MS = 30_000

interface ProbeError {
  response?: { status?: number; headers?: Record<string, unknown> }
}

function statusOf(error: unknown): number | undefined {
  return (error as ProbeError | null)?.response?.status
}

/** Whether a failure is worth asking again: rate limited, the server failing, or no answer at all. */
export function isTransientProbeFailure(error: unknown): boolean {
  const status = statusOf(error)
  if (status === undefined) return true
  return status === 429 || status === 408 || status >= 500
}

/** React Query's `retry`: transient failures only, a bounded number of times. */
export function retrySessionProbe(failureCount: number, error: unknown): boolean {
  return failureCount < SESSION_PROBE_MAX_RETRIES && isTransientProbeFailure(error)
}

/** React Query's `retryDelay`: what `Retry-After` asks for, else 1 s, 2 s, 4 s ... up to 30 s. */
export function sessionProbeDelay(attempt: number, error: unknown): number {
  const header = (error as ProbeError | null)?.response?.headers?.['retry-after']
  const seconds = typeof header === 'string' || typeof header === 'number' ? Number(header) : NaN
  if (Number.isFinite(seconds) && seconds > 0) return Math.min(seconds * 1000, MAX_DELAY_MS)
  return Math.min(BASE_DELAY_MS * 2 ** Math.max(0, attempt), MAX_DELAY_MS)
}
