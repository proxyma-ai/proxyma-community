// Gates the periodic "did you know about MCP/API" toast (see AppShell). Plain localStorage
// functions, not a Zustand store - this is checked imperatively once per app mount, not read
// reactively by multiple components, so there's no shared reactive state to model.
const KEY_DISMISSED_FOREVER = 'proxyma-mcp-ad-dismissed-forever'
const KEY_LAST_SHOWN_DATE = 'proxyma-mcp-ad-last-shown-date'
const KEY_FIRST_DISMISSED_AT = 'proxyma-mcp-ad-first-dismissed-at'

// After the user closes the toast via X (rather than clicking through), keep showing it for
// this many more days before giving up - "a few more days" per the feature request.
const EXTRA_DAYS_AFTER_DISMISS = 7
// Roughly "once a day" without being a guaranteed daily interruption.
const DAILY_SHOW_CHANCE = 0.4

function todayIso(): string {
  return new Date().toISOString().slice(0, 10)
}

function readString(key: string): string | null {
  try { return localStorage.getItem(key) } catch { return null }
}

function write(key: string, value: string) {
  try { localStorage.setItem(key, value) } catch { /* private browsing, ignore */ }
}

export function shouldShowMcpAd(): boolean {
  if (readString(KEY_DISMISSED_FOREVER) === 'true') return false
  if (readString(KEY_LAST_SHOWN_DATE) === todayIso()) return false

  const firstDismissedAt = readString(KEY_FIRST_DISMISSED_AT)
  if (firstDismissedAt) {
    const daysSince = (Date.now() - new Date(firstDismissedAt).getTime()) / 86_400_000
    if (daysSince > EXTRA_DAYS_AFTER_DISMISS) return false
  }

  return Math.random() < DAILY_SHOW_CHANCE
}

export function markMcpAdShownToday() {
  write(KEY_LAST_SHOWN_DATE, todayIso())
}

// Only records the FIRST close - repeated closes on later days must not keep pushing the
// "few more days" window back out.
export function markMcpAdClosed() {
  if (!readString(KEY_FIRST_DISMISSED_AT)) write(KEY_FIRST_DISMISSED_AT, new Date().toISOString())
}

export function markMcpAdDismissedForever() {
  write(KEY_DISMISSED_FOREVER, 'true')
}
