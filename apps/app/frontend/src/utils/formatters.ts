export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`
}

export function formatDate(iso: string): string {
  return new Date(iso).toLocaleString(undefined, {
    dateStyle: 'medium', timeStyle: 'short',
  })
}

// Same as formatDate but labels which timezone the (viewer-local) time is in - e.g. "Jul 27,
// 2026, 12:00 PM GMT+2". Used only where the reader can't otherwise tell what zone a maintenance
// schedule is being shown in (MaintenanceBanner, MaintenanceGate); left as a separate function
// rather than changing formatDate itself, since formatDate's unlabeled form is used in ~10
// unrelated places (Settings token expiry, admin tables, feedback timestamps) that should keep
// their current look.
// Uses explicit component options (year/month/day/hour/minute), NOT dateStyle/timeStyle - the
// Intl spec throws a TypeError if timeZoneName is combined with dateStyle/timeStyle ("Invalid
// option : option" in V8), so those two option shapes can never be mixed here.
export function formatDateWithZone(iso: string): string {
  return new Date(iso).toLocaleString(undefined, {
    year: 'numeric', month: 'short', day: 'numeric',
    hour: 'numeric', minute: '2-digit',
    timeZoneName: 'short',
  })
}

// How long from now until `iso`, as a compact duration ("3h 20m", "45m", "6d 4h") rather than a
// wall-clock timestamp. Used for rolling windows, where an absolute "Resets 31/07/2026, 18:42:11"
// forces the reader to do the subtraction themselves to answer the only question they actually
// have - how long until I can carry on. Rounds down to the minute; anything under a minute (or
// already in the past, which a rolling window's approximated reset point can be by the time it
// reaches the client) collapses to "under a minute" rather than showing "0m" or a negative.
/**
 * `t` is optional so a caller with no locale in hand still gets English rather than a crash.
 *
 * Only the "under a minute" branch is prose. The "2d 3h" / "45m" forms are left alone on purpose:
 * they are a compact READOUT, the same class of thing as "$129.52" or "gpt-4o", and the landing
 * site made the same call for the same strings. Translating the unit letters would also break the
 * alignment the tabular-nums rendering depends on.
 */
export function formatDurationUntil(iso: string, t?: (s: string) => string): string {
  const say = t ?? ((s: string) => s)
  const ms = new Date(iso).getTime() - Date.now()
  if (!Number.isFinite(ms) || ms <= 0) return say('under a minute')
  const totalMinutes = Math.floor(ms / 60_000)
  if (totalMinutes < 1) return say('under a minute')
  const days = Math.floor(totalMinutes / 1440)
  const hours = Math.floor((totalMinutes % 1440) / 60)
  const minutes = totalMinutes % 60
  if (days > 0) return hours > 0 ? `${days}d ${hours}h` : `${days}d`
  if (hours > 0) return minutes > 0 ? `${hours}h ${minutes}m` : `${hours}h`
  return `${minutes}m`
}

export function formatTokenCount(n: number): string {
  if (n < 1000) return String(n)
  return `${(n / 1000).toFixed(1)}k`
}

// Compact display for large item counts (sources/documents/indexing counts) - switches to
// k/M only once the value exceeds 9999, so small counts stay exact (e.g. "247") and large ones
// stay readable (e.g. "4.5k", "1.2M") instead of overflowing the header stat row.
export function formatCompactCount(n: number): string {
  if (n <= 9999) return String(n)
  if (n < 1_000_000) return `${(n / 1000).toFixed(1)}k`
  return `${(n / 1_000_000).toFixed(1)}M`
}

// Estimated LLM cost in USD. Sub-cent amounts (e.g. a handful of cheap requests) would round to
// "$0.00" at 2 decimals, hiding that any cost was incurred at all - so those get 4 decimals
// instead (e.g. "$0.0034"), while everything else uses the normal 2-decimal currency display.
export function formatUsd(n: number): string {
  if (n === 0) return '$0.00'
  if (n < 0.01) return `$${n.toFixed(4)}`
  return `$${n.toFixed(2)}`
}
