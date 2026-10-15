import type { Translate } from '@/i18n'

// compact=true drops the " ago" suffix (e.g. "3m" instead of "3m ago") - for tight chip/row
// layouts (chat history list) where the surrounding UI already implies recency.
//
// `t` IS REQUIRED, for the same reason formatSessionReset's is: the non-compact form is a
// sentence with the value substituted into it, and the obvious optional-argument fallback
// - `(s) => s` - would return "{value} ago" unsubstituted to English readers. A default that
// corrupts the fallback language is worse than four call sites passing an argument they have.
//
// The VALUE ITSELF ("3m", "2h", "5d") is deliberately not translated: it is a compact readout,
// the same class as "$129.52", and its unit letters are what keep these rows narrow.
export function formatRelativeTime(iso: string, opts: { compact?: boolean; t: Translate }): string {
  const { compact, t } = opts
  const diff = Date.now() - new Date(iso).getTime()
  const mins = Math.floor(diff / 60000)
  if (mins < 1) return compact ? t('now') : t('just now')
  const hrs = Math.floor(mins / 60)
  const days = Math.floor(hrs / 24)
  const value = mins < 60 ? `${mins}m` : hrs < 24 ? `${hrs}h` : `${days}d`
  return compact ? value : t('{value} ago', { value })
}
