// THE ALARMS an agent set in its conversation (the `alarm` tool), as the chip beside the composer
// lists them. Plain .ts with no imports, so `node --test` reaches it - alarms.test.ts.

/** One alarm, as GET /chat/conversations/{id}/alarms lists it. */
export interface PendingAlarm {
  alarmId: string
  /** When it goes off, ISO-8601. */
  dueAt: string
  note: string | null
}

/**
 * How long until an alarm goes off, as a compact readout - "45s", "12m", "3h", "2d" - rounded UP, so
 * an alarm a minute and a half away never reads as one minute. Null once it is due: it is about to
 * go off, and the list drops it when it has.
 */
export function dueIn(dueAt: string, now: number): string | null {
  const due = Date.parse(dueAt)
  if (!Number.isFinite(due)) return null
  const ms = due - now
  if (ms <= 0) return null
  const seconds = Math.ceil(ms / 1000)
  if (seconds < 60) return `${seconds}s`
  const minutes = Math.ceil(seconds / 60)
  if (minutes < 60) return `${minutes}m`
  const hours = Math.ceil(minutes / 60)
  if (hours < 48) return `${hours}h`
  return `${Math.ceil(hours / 24)}d`
}

/** The alarms soonest first; one with no readable time goes last rather than first. */
export function soonestFirst(alarms: PendingAlarm[]): PendingAlarm[] {
  const at = (a: PendingAlarm) => {
    const t = Date.parse(a.dueAt)
    return Number.isFinite(t) ? t : Number.POSITIVE_INFINITY
  }
  return [...alarms].sort((a, b) => at(a) - at(b))
}
