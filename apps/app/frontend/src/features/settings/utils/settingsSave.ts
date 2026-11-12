/**
 * WHAT THE SETTINGS WINDOW SAVES, AND WHEN - decided without a DOM (the maintainer's click-to-edit
 * rule, 2026-11-02: every field saves itself when it is left, and the shared Save bar went).
 *
 * A field on its own saves the moment it is left. Fields meaningless apart - the proxy mode and its
 * addresses, a role's provider and model, Microsoft 365's tenant and client IDs - collect a DRAFT
 * while the person works among them, and the draft is saved as one when the person leaves the group.
 * While a save is in flight its values are PENDING: shown in place of what the server held, and
 * taken away when the server has answered (kept on success by the refetch, reverted on failure).
 *
 * Plain .ts so `node --test` reads it (settingsSave.test.ts).
 */

/** One field to write: '' clears it on the server (the field's own default then applies). */
export interface SettingChange {
  prefix: string
  field: string
  value: string
}

/** A group's edits not yet saved, by `prefix/field`. */
export type SettingDraft = Readonly<Record<string, SettingChange>>

function keyOf(prefix: string, field: string): string {
  return `${prefix}/${field}`
}

/**
 * The draft with one more edit. An edit back to what the server holds (`saved`) is no edit and
 * leaves the draft. A SECRET left blank is never sent - blank means "keep the stored one", so it is
 * taken out of the draft rather than put in as a clear.
 */
export function withEdit(draft: SettingDraft, change: SettingChange, opts: { saved?: string; secret?: boolean } = {}): SettingDraft {
  const key = keyOf(change.prefix, change.field)
  const next: Record<string, SettingChange> = { ...draft }
  if ((opts.secret && change.value === '') || (opts.saved !== undefined && change.value === opts.saved)) {
    delete next[key]
  } else {
    next[key] = change
  }
  return next
}

/** The draft's value for a field, else the one given (what the server holds). */
export function draftValue(draft: SettingDraft, prefix: string, field: string, resolved: string): string {
  const entry = draft[keyOf(prefix, field)]
  return entry ? entry.value : resolved
}

/** What leaving the group sends: every edit, in the order they were first made. */
export function draftChanges(draft: SettingDraft): SettingChange[] {
  return Object.values(draft)
}

/**
 * The value a field shows while saves are in flight: the NEWEST pending one (pending is kept newest
 * first, see `withPending`), else undefined.
 */
export function pendingValue(pending: readonly SettingChange[], prefix: string, field: string): string | undefined {
  return pending.find(p => p.prefix === prefix && p.field === field)?.value
}

/** Pending with a save's changes in front - they are the newest. */
export function withPending(pending: readonly SettingChange[], changes: readonly SettingChange[]): SettingChange[] {
  return [...changes, ...pending]
}

/** Pending without one save's changes - by identity, so a later save of the same field stays. */
export function withoutPending(pending: readonly SettingChange[], changes: readonly SettingChange[]): SettingChange[] {
  return pending.filter(p => !changes.includes(p))
}

/**
 * Whether a save changes the embedding model - which asks first, since every source then has to be
 * indexed again. Choosing another embedding provider clears the model, so it asks then too.
 */
export function changesEmbeddingModel(changes: readonly SettingChange[]): boolean {
  return changes.some(c => c.prefix === 'embedding' && c.field === 'model')
}
