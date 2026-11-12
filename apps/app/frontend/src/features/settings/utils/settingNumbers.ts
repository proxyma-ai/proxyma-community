/**
 * A NUMBER TYPED INTO A SETTING, checked before it is saved (the click-to-edit rule, 2026-11-02:
 * "validated before save, an invalid value shown as an error and not saved"). The settings endpoint
 * stores the text it is given, and the server reads these fields as an int or a double - so a word,
 * a fraction where a count belongs, or a value past the field's range would be stored and fail later,
 * where nobody is looking. Each field's rule is the range the server applies (a clamp, or the
 * smallest value that means anything).
 *
 * Plain .ts so `node --test` reads it (settingNumbers.test.ts).
 */

export interface NumberRule {
  min: number
  max?: number
  /** A count - days, seconds, a day of the month. Else an amount, decimals allowed. */
  whole?: boolean
}

/** Days kept or looked back: 0 is "for ever" or "none", and a century is the ceiling. */
export const DAYS: NumberRule = { min: 0, max: 36500, whole: true }
/** A day of the month the quotas reset on - the server holds it to 1-28 (QuotaResetDayTest). */
export const RESET_DAY: NumberRule = { min: 1, max: 28, whole: true }
/** A spending limit in dollars; 0 is no limit. */
export const DOLLARS: NumberRule = { min: 0, max: 1_000_000_000 }

export type NumberProblem =
  | { kind: 'notNumber' }
  | { kind: 'notWhole' }
  | { kind: 'tooSmall'; min: number }
  | { kind: 'tooLarge'; max: number }

const NUMBER = /^-?\d+(\.\d+)?$/

/** What is wrong with `text` as this field's number, or null when it can be saved. */
export function numberProblem(text: string, rule: NumberRule): NumberProblem | null {
  const s = text.trim()
  if (!NUMBER.test(s)) return { kind: 'notNumber' }
  const n = Number(s)
  if (!Number.isFinite(n)) return { kind: 'notNumber' }
  if (rule.whole && !Number.isInteger(n)) return { kind: 'notWhole' }
  if (n < rule.min) return { kind: 'tooSmall', min: rule.min }
  if (rule.max !== undefined && n > rule.max) return { kind: 'tooLarge', max: rule.max }
  return null
}

/** The number as it is stored: no leading zeros, no trailing ones after a point - "007" is "7". */
export function normalizedNumber(text: string): string {
  const n = Number(text.trim())
  return Number.isFinite(n) ? String(n) : text.trim()
}
