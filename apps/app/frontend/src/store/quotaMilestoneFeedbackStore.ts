// Gates the QuotaMilestoneFeedbackModal ($5-spend-bracket survey, see useQuotaMilestoneFeedback).
// Plain localStorage functions, not Zustand - same rationale as mcpAdStore.ts: this is checked
// imperatively whenever useUserQuota's poll produces a new value, not read reactively by
// multiple components, so there's no shared reactive state to model.
const KEY_DISMISSED_FOREVER = 'proxyma-feedback-milestone-dismissed-forever'
const KEY_LAST_SHOWN = 'proxyma-feedback-milestone-last-shown'

interface LastShown {
  bracket: number
  cycleKey: string
}

function readString(key: string): string | null {
  try { return localStorage.getItem(key) } catch { return null }
}

function write(key: string, value: string) {
  try { localStorage.setItem(key, value) } catch { /* private browsing, ignore */ }
}

export function isFeedbackMilestoneDismissedForever(): boolean {
  return readString(KEY_DISMISSED_FOREVER) === 'true'
}

export function markFeedbackMilestoneDismissedForever() {
  write(KEY_DISMISSED_FOREVER, 'true')
}

// bracket + cycleKey are tracked together (not two separate keys) so a new monthly cycle
// (cycleKey derives from the quota endpoint's cycleResetsAt - see useQuotaMilestoneFeedback)
// naturally re-arms every bracket, matching the confirmed "re-arms every cycle" behavior -
// without pairing them, a stale bracket number from last cycle would incorrectly suppress this
// cycle's first crossing of the same bracket number.
export function wasBracketShownThisCycle(bracket: number, cycleKey: string): boolean {
  const raw = readString(KEY_LAST_SHOWN)
  if (!raw) return false
  try {
    const parsed = JSON.parse(raw) as LastShown
    return parsed.cycleKey === cycleKey && parsed.bracket >= bracket
  } catch {
    return false
  }
}

export function markBracketShownThisCycle(bracket: number, cycleKey: string) {
  write(KEY_LAST_SHOWN, JSON.stringify({ bracket, cycleKey } satisfies LastShown))
}
