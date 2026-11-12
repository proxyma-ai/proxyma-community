/**
 * A DESTRUCTIVE SETTING IS SAVED BY A BUTTON (the maintainer, 2026-11-10: "For danger/destructive actions, it should
 * always use explicit Save button, apply for both app and the others"): what a tab such as Data retention holds as typed
 * until Save, and which of it differs from what is saved. Every other setting is saved as its field is left
 * (click-to-edit). Plain .ts so `node --test` reads it.
 */
import { normalizedNumber, numberProblem, type NumberRule } from './settingNumbers.ts'

/** The fields whose typed value differs from the saved one, as they would be stored. */
export function stagedChanges(saved: Readonly<Record<string, string>>, typed: Readonly<Record<string, string>>): Record<string, string> {
  const out: Record<string, string> = {}
  for (const [field, text] of Object.entries(typed)) {
    const value = normalizedNumber(text)
    if (value !== normalizedNumber(saved[field] ?? '')) out[field] = value
  }
  return out
}

/** The typed fields that cannot be saved under `rule` - empty, not a number, or out of range. */
export function stagedProblems(typed: Readonly<Record<string, string>>, rule: NumberRule): string[] {
  return Object.entries(typed).filter(([, text]) => text.trim() === '' || numberProblem(text, rule) !== null).map(([f]) => f)
}

/** The fields whose new value is lower than the saved one - for a retention, what deletes data at the next sweep. */
export function lowered(saved: Readonly<Record<string, string>>, changes: Readonly<Record<string, string>>): string[] {
  return Object.entries(changes).filter(([field, value]) => {
    const was = Number(saved[field] ?? '0')
    const now = Number(value)
    // 0 keeps forever: going to 0 deletes nothing, and coming from 0 is a first limit.
    return now !== 0 && (was === 0 || now < was)
  }).map(([f]) => f)
}
