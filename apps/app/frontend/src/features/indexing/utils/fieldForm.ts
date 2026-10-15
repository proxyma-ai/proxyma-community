import type { CatalogCategory, CatalogType, FieldSpec } from '../services/catalogApi'

/**
 * A form drawn from a descriptor's fields - what it starts with, what is wrong with it, and what it
 * sends. The same rules as the server's ConfigValidator, checked here first so a mistake is shown
 * under its own field instead of as one refusal for the whole form; the server checks again, since
 * the agent and an MCP client never see this page.
 *
 * A PLAIN `.ts` WITH TYPE-ONLY IMPORTS, so `node --test` loads it (fieldForm.test.ts).
 */

/** Every value is text, as the server stores it: a switch is "true" or "false", a list one line per entry. */
export type FieldValues = Record<string, string>

/** Why a field's value is refused - the sentence is the component's, in the reader's language. */
export type FieldProblem =
  | { code: 'required' }
  | { code: 'number'; min: number | null; max: number | null }
  | { code: 'url' }
  | { code: 'option' }
  | { code: 'pattern'; example: string | null }
  | { code: 'oneLine' }

/**
 * What the form opens with: the stored value where there is one (an edit), else the field's
 * default. A secret always starts empty - the page is never sent one, and an empty secret on an
 * edit keeps what is stored.
 */
export function initialFieldValues(fields: FieldSpec[], stored?: Record<string, string | null | undefined> | null): FieldValues {
  const out: FieldValues = {}
  for (const f of fields) {
    if (f.type === 'SECRET') { out[f.key] = ''; continue }
    const kept = stored?.[f.key]
    if (kept != null) { out[f.key] = kept; continue }
    out[f.key] = f.defaultValue ?? (f.type === 'BOOLEAN' ? 'false' : '')
  }
  return out
}

/**
 * What is wrong with each field, keyed by field; empty when the form can be sent. `storedSecrets`
 * names the secrets already held, which a required secret left blank keeps.
 */
export function fieldProblems(
  fields: FieldSpec[],
  values: FieldValues,
  storedSecrets: readonly string[] = [],
): Record<string, FieldProblem> {
  const out: Record<string, FieldProblem> = {}
  for (const f of fields) {
    const v = (values[f.key] ?? '').trim()
    if (v === '') {
      if (f.required && f.type !== 'BOOLEAN' && !(f.type === 'SECRET' && storedSecrets.includes(f.key))) {
        out[f.key] = { code: 'required' }
      }
      continue
    }
    const problem = problemOf(f, v)
    if (problem) out[f.key] = problem
  }
  return out
}

function problemOf(f: FieldSpec, v: string): FieldProblem | null {
  if (f.type !== 'TEXTAREA' && f.type !== 'LIST' && /[\r\n]/.test(v)) return { code: 'oneLine' }
  switch (f.type) {
    case 'NUMBER': {
      if (!/^-?\d+$/.test(v)) return { code: 'number', min: f.min, max: f.max }
      const n = Number(v)
      if ((f.min != null && n < f.min) || (f.max != null && n > f.max)) return { code: 'number', min: f.min, max: f.max }
      break
    }
    case 'URL':
      if (!/^https?:\/\/[^\s/?#]+/i.test(v)) return { code: 'url' }
      break
    case 'SELECT':
      if (!f.options.some(o => o.value === v)) return { code: 'option' }
      break
    case 'MULTISELECT':
      if (v.split(',').map(p => p.trim()).filter(Boolean).some(p => !f.options.some(o => o.value === p))) return { code: 'option' }
      break
    default:
      break
  }
  if (f.pattern && f.type !== 'LIST') {
    let re: RegExp | null = null
    // Java's matches() is the whole value; a pattern this engine cannot read refuses nothing, as
    // the server's does not.
    try { re = new RegExp(`^(?:${f.pattern})$`) } catch { re = null }
    if (re && !re.test(v)) return { code: 'pattern', example: f.placeholder }
  }
  return null
}

/**
 * What the form sends. Every field is sent, an empty one included - on an edit that is what clears
 * an optional value, and on a new source the server fills the default in for it - except a secret
 * left empty, which would otherwise read as "remove the stored one". A list is one trimmed entry a
 * line.
 */
export function submittedFieldValues(fields: FieldSpec[], values: FieldValues): Record<string, string> {
  const out: Record<string, string> = {}
  for (const f of fields) {
    const raw = values[f.key] ?? ''
    if (f.type === 'SECRET') {
      if (raw.trim() !== '') out[f.key] = raw.trim()
      continue
    }
    out[f.key] = f.type === 'LIST'
      ? raw.split(/\r?\n/).map(l => l.trim()).filter(Boolean).join('\n')
      : raw.trim()
  }
  return out
}

/** Whether the form differs from what it opened with - a secret counts once anything is typed. */
export function fieldValuesChanged(fields: FieldSpec[], initial: FieldValues, values: FieldValues): boolean {
  return fields.some(f => (values[f.key] ?? '').trim() !== (initial[f.key] ?? '').trim())
}

/** The fields on the form's face, then the ones behind "More options". */
export function splitAdvanced(fields: FieldSpec[]): { main: FieldSpec[]; advanced: FieldSpec[] } {
  return { main: fields.filter(f => !f.advanced), advanced: fields.filter(f => f.advanced) }
}

export interface TypeGroup {
  category: string
  label: string
  types: CatalogType[]
}

/**
 * The Connect menu's groups: the types a deployment offers, by category in the catalog's order of
 * categories, each group in the types' own order. A category with nothing offered is left out, and
 * so is a type switched off (`offered: false`) - its existing sources still have a name and a mark,
 * but nothing new is added.
 */
export function groupTypesByCategory(types: CatalogType[], categories: CatalogCategory[]): TypeGroup[] {
  const offered = types.filter(t => t.offered).sort((a, b) => a.order - b.order)
  const known = new Set(categories.map(c => c.key))
  const groups: TypeGroup[] = []
  for (const c of categories) {
    const inGroup = offered.filter(t => t.category === c.key)
    if (inGroup.length > 0) groups.push({ category: c.key, label: c.label, types: inGroup })
  }
  // A type naming a category this page was not told about still appears - last, under its own key.
  const strays = offered.filter(t => !known.has(t.category))
  if (strays.length > 0) groups.push({ category: 'OTHER', label: 'Other', types: strays })
  return groups
}

/** The order the Sources page lists types in - by the catalog's order, unknown types last. */
export function typeRank(types: CatalogType[]): (type: string) => number {
  const order = new Map(types.map(t => [t.type, t.order]))
  return type => order.get(type) ?? Number.MAX_SAFE_INTEGER
}
