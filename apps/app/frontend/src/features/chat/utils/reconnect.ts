/**
 * When a chat stream that dropped is tried again, and when the page stops trying and says
 * "Connection lost".
 *
 * <p>LONG ENOUGH TO OUTLAST A RESTART (2026-10-16). It was four retries, 1 + 2 + 4 + 8 seconds, so
 * the page gave up about fifteen seconds after the server went away. An application restart - Apply
 * update, Settings' Restart - takes longer than that on most machines (77 seconds to start on the
 * laptop in the 0.36.0 feedback), so the page said "Connection lost" before the new process could
 * tell it the turn was gone (see `opened` in chatStream.ts), and the release's ChatRestartIT failed
 * on the CI laptop for exactly that. Now: double from one second to a ceiling of five, and keep
 * going for three minutes since the stream was last open.
 *
 * <p>Plain TypeScript, tested by `node --test` (reconnect.test.ts).
 */

export const RECONNECT_FIRST_MS = 1000
export const RECONNECT_CEILING_MS = 5000
export const RECONNECT_BUDGET_MS = 180_000

/**
 * The wait before the next attempt, or null to give up. `attempt` counts the retries since the
 * stream was last open (0 for the first), `downSinceMs` is when it was last open or first tried.
 */
export function nextReconnectDelay(attempt: number, downSinceMs: number, nowMs: number): number | null {
  if (nowMs - downSinceMs >= RECONNECT_BUDGET_MS) return null
  return Math.min(RECONNECT_CEILING_MS, RECONNECT_FIRST_MS * Math.pow(2, Math.max(0, attempt)))
}
