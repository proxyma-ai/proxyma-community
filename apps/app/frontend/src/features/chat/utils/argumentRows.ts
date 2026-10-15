import { redactFilePaths } from './redactFilePaths.ts'
import { NOUNS } from './toolDisplayName.ts'

// A tool call's arguments as label / value rows, for the detail dialog and the approval card.
//
// They were shown as the raw JSON, and a Jira comment's body reached the reader as one escaped
// string with its line breaks spelled `\n` - the review's F28. A row per argument reads as what it
// is; the JSON is still one click away in the dialog for anyone who wants the exact payload.
//
// A NESTED argument - an object of fields, a list - is set out as lines of "Label: value" too,
// since 2026-10-02: it was pretty-printed JSON inside its row, the one place the approval card
// still showed braces to someone deciding whether to allow a change. (The detail dialog draws the
// same payload as a tree - jsonFields.ts.)
//
// Server paths are dropped first, exactly as the JSON view does (redactFilePaths).
//
// Plain .ts, so `node --test` reaches it - argumentRows.test.ts.

export interface ArgumentRow {
  key: string
  label: string
  value: string
  // A value worth showing on its own lines: multi-line text or a structure.
  block: boolean
}

// issueKey -> "Issue key", jql -> "JQL", repoSlug -> "Repository slug". Built from the argument's
// own name at runtime, so there is nothing for a catalog to key on - the same limit toolActivity's
// derive() documents.
const ACRONYMS: Record<string, string> = { jql: 'JQL', cql: 'CQL', id: 'ID', url: 'URL', uri: 'URI', api: 'API' }

export function argumentLabel(key: string): string {
  const words = key.replace(/([a-z0-9])([A-Z])/g, '$1 $2').split(/[\s_-]+/).filter(Boolean)
  const text = words.map(w => ACRONYMS[w.toLowerCase()] ?? NOUNS[w.toLowerCase()] ?? w.toLowerCase()).join(' ')
  return text.charAt(0).toUpperCase() + text.slice(1)
}

const isEmpty = (v: unknown) => v === undefined || v === null || v === ''

// A structure as plain lines: a field to a line, a list item to a line, each level set in by two
// spaces. No braces, quotes or commas - it is read, not parsed.
function lines(value: unknown, indent: string): string[] {
  if (Array.isArray(value)) {
    return value.filter(v => !isEmpty(v)).flatMap(item =>
      item !== null && typeof item === 'object'
        ? lines(item, indent + '  ').map((line, i) => (i === 0 ? `${indent}- ${line.trimStart()}` : line))
        : [`${indent}${String(item)}`])
  }
  return Object.entries(value as Record<string, unknown>).filter(([, v]) => !isEmpty(v)).flatMap(([key, v]) =>
    v !== null && typeof v === 'object'
      ? [`${indent}${argumentLabel(key)}:`, ...lines(v, indent + '  ')]
      : [`${indent}${argumentLabel(key)}: ${String(v)}`])
}

/**
 * The rows for one call's arguments, or null when they are not a JSON object - a caller then
 * falls back to showing the raw text.
 */
export function argumentRows(json: string | undefined): ArgumentRow[] | null {
  if (!json || !json.trim()) return []
  let parsed: unknown
  try {
    parsed = JSON.parse(json)
  } catch {
    return null
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return null
  const safe = redactFilePaths(parsed as Record<string, unknown>)
  const rows: ArgumentRow[] = []
  for (const [key, raw] of Object.entries(safe)) {
    if (raw === undefined || raw === null || raw === '') continue
    const structured = typeof raw === 'object'
    const value = structured ? lines(raw, '').join('\n') : String(raw)
    if (structured && !value) continue
    rows.push({ key, label: argumentLabel(key), value, block: structured || value.includes('\n') })
  }
  return rows
}
