import { redactFilePaths } from './redactFilePaths.ts'
import { argumentLabel } from './argumentRows.ts'

// A JSON payload - a tool call's arguments, or what it returned - as a tree of labelled fields,
// for the detail dialog.
//
// The dialog showed a result as its JSON, braces and escaped quotes included, beside arguments
// that had been label / value rows since the review's F28 - and a nested argument was still a
// block of JSON inside its row (maintainer's feedback, 2026-10-02). Here an object is its fields,
// a list is its items in order, and only a value is ever printed. The exact JSON stays one click
// away in the dialog.
//
// Server paths are dropped first, at every depth, exactly as the JSON view does (redactFilePaths).
//
// Plain .ts, so `node --test` reaches it - jsonFields.test.ts.

export type JsonField =
  | { kind: 'value'; label: string; value: string; block: boolean }
  // An object, or a list whose items are objects: `fields` are its own, one level in.
  | { kind: 'group'; label: string; fields: JsonField[] }

// Past this depth a structure is printed as JSON after all, rather than drawn ever narrower.
const MAX_DEPTH = 5

const isEmpty = (value: unknown): boolean =>
  value === undefined || value === null || value === ''
  || (Array.isArray(value) && value.length === 0)
  || (typeof value === 'object' && !Array.isArray(value) && Object.keys(value as object).length === 0)

const isPlain = (value: unknown): boolean => value === null || typeof value !== 'object'

function field(label: string, raw: unknown, depth: number): JsonField {
  if (isPlain(raw)) {
    const value = String(raw)
    return { kind: 'value', label, value, block: value.includes('\n') }
  }
  if (depth >= MAX_DEPTH) return { kind: 'value', label, value: JSON.stringify(raw, null, 2), block: true }
  if (Array.isArray(raw)) {
    const items = raw.filter(item => !isEmpty(item))
    // A list of plain values is one field, a value to a line.
    if (items.every(isPlain)) {
      return { kind: 'value', label, value: items.map(String).join('\n'), block: items.length > 1 }
    }
    return { kind: 'group', label, fields: items.map((item, i) => field(String(i + 1), item, depth + 1)) }
  }
  return { kind: 'group', label, fields: fieldsOf(raw as Record<string, unknown>, depth + 1) }
}

function fieldsOf(object: Record<string, unknown>, depth: number): JsonField[] {
  return Object.entries(object)
    .filter(([, raw]) => !isEmpty(raw))
    .map(([key, raw]) => field(argumentLabel(key), raw, depth))
}

/**
 * The fields of a JSON object or list, or null when the text is neither - plain text, a bare
 * number, nothing at all - and the caller shows it as it is.
 */
export function jsonFields(json: string | undefined): JsonField[] | null {
  if (!json || !json.trim()) return null
  let parsed: unknown
  try {
    parsed = JSON.parse(json)
  } catch {
    return null
  }
  if (!parsed || typeof parsed !== 'object') return null
  const safe = redactFilePaths(parsed)
  if (Array.isArray(safe)) {
    const list = field('', safe, 0)
    // A list of plain values has no labels to give: left to the caller's plain view.
    return list.kind === 'group' ? list.fields : null
  }
  return fieldsOf(safe as Record<string, unknown>, 0)
}
