// The composer's prompt-cache timer: how long the conversation's cached prompt stays warm, counted
// down on the page. The server says how many seconds were left when it answered (cacheSecondsLeft);
// the page takes that from the moment the figure arrived, never from a timestamp, because a
// browser's clock and the server's need not agree.
//
// Pure and import-free, so `node --test` reaches it (cacheTimer.test.ts).

/** When the cache goes cold, in this page's clock: arrival plus the seconds left; null when cold. */
export function cacheDeadline(secondsLeft: number | null | undefined, receivedAtMs: number): number | null {
  if (secondsLeft == null || !Number.isFinite(secondsLeft) || secondsLeft <= 0) return null
  return receivedAtMs + secondsLeft * 1000
}

/** Whole seconds until the deadline, never below zero. */
export function secondsUntil(deadlineMs: number | null, nowMs: number): number {
  if (deadlineMs == null) return 0
  return Math.max(0, Math.ceil((deadlineMs - nowMs) / 1000))
}

/** A countdown as a clock reads: "4:07", "59:59", "1:00:00". */
export function countdownText(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  const two = (n: number) => String(n).padStart(2, '0')
  return h > 0 ? `${h}:${two(m)}:${two(sec)}` : `${m}:${two(sec)}`
}

/** The cache's lifetime in the words the tooltip uses: an hour, or five minutes. */
export function cacheLifetime(lifetimeSeconds: number | null | undefined): 'hour' | 'fiveMinutes' {
  return lifetimeSeconds != null && lifetimeSeconds >= 3600 ? 'hour' : 'fiveMinutes'
}

/** The last minute: the timer turns to the warning ink, since the next message may miss. */
export function nearlyCold(seconds: number): boolean {
  return seconds > 0 && seconds <= 60
}

/**
 * How full the cache's ring is, 0-100 (the maintainer's request, 2026-11-02: a ring like the context
 * window's, not a stopwatch and a clock). It DRAINS: full when a write or read has just started the
 * lifetime, empty when it goes cold. Measured against the lifetime the server stated - an hour or
 * five minutes - and, where none was stated, against five minutes or the time left, whichever is
 * longer, so an unstated lifetime never shows a ring over full.
 */
export function cacheRingPercent(secondsLeft: number, lifetimeSeconds: number | null | undefined): number {
  if (!Number.isFinite(secondsLeft) || secondsLeft <= 0) return 0
  const stated = lifetimeSeconds != null && Number.isFinite(lifetimeSeconds) && lifetimeSeconds > 0 ? lifetimeSeconds : 0
  const whole = Math.max(stated || 300, secondsLeft)
  return Math.min(100, (secondsLeft / whole) * 100)
}
