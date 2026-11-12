/**
 * WHAT A TEXT EDITED WHERE IT IS READ DECIDES WITHOUT A DOM (EditableText.tsx; the maintainer, 2026-11-02:
 * "hover mouse on them turns the pointer to cursor. Click on them could edit them. Unfocus auto saves the
 * changes" - to be applied app-wide). Where the caret lands when the text is clicked, how tall a multi-line
 * editor opens, what a key does, and what leaving the editor commits. Plain .ts so `node --test` reads it.
 */

/** Where the caret goes when the text is clicked: the offset under the pointer, else the end. */
export function caretAt(length: number, offset: number | null | undefined): number {
  if (offset === null || offset === undefined || !Number.isFinite(offset)) return length
  return Math.max(0, Math.min(length, Math.floor(offset)))
}

/**
 * How many lines a multi-line editor opens with: the text's own, within `min` and `max` - no line to spare (the maintainer,
 * 2026-11-10, of an approval's note: "this box should starts with one line, not 2 lines"). The editor also grows with
 * what wraps (`field-sizing: content`), which a count of line breaks cannot see.
 */
export function editorRows(text: string, min = 1, max = 8): number {
  return Math.min(max, Math.max(min, text.split('\n').length))
}

export type KeyAction = 'commit' | 'cancel' | null

/**
 * What a key does in the editor. Escape cancels. Enter commits a single line; in a multi-line editor it
 * is a new line and Ctrl+Enter (Cmd+Enter) commits. A key pressed while an input method is composing -
 * a Vietnamese or Japanese word still being typed - does nothing here: its Enter confirms the word.
 */
export function keyAction(e: { key: string; ctrlKey?: boolean; metaKey?: boolean; shiftKey?: boolean; isComposing?: boolean }, multiline: boolean): KeyAction {
  if (e.isComposing) return null
  if (e.key === 'Escape') return 'cancel'
  if (e.key !== 'Enter') return null
  if (multiline) return e.ctrlKey || e.metaKey ? 'commit' : null
  return e.shiftKey ? null : 'commit'
}

export interface SettleRules {
  /** Strip the ends before comparing and committing. Default true. */
  trim?: boolean
  /** What an emptied text becomes - "Untitled Project". */
  fallback?: string
  /** An emptied text is refused (the editor stays open) - when there is no fallback. */
  required?: boolean
  /**
   * A changed text that cannot be saved - a number out of range: its sentence, or nothing when it can.
   * The commit is refused and the editor stays open with the sentence, as an emptied required text is.
   */
  check?: (text: string) => string | null | undefined
}

export type Settled =
  | { kind: 'unchanged' }
  | { kind: 'missing' }
  | { kind: 'invalid'; reason: string }
  | { kind: 'commit'; text: string }

/**
 * What leaving the editor does with `draft`, given the text it was opened on: nothing when it says the
 * same, a refusal when it was emptied and is required or when `check` refuses it, else the text to
 * commit - the fallback for an emptied one that has one.
 */
export function settle(value: string, draft: string, rules: SettleRules = {}): Settled {
  const trim = rules.trim !== false
  let next = trim ? draft.trim() : draft
  if (next.trim() === '') {
    if (rules.fallback !== undefined && rules.fallback.trim() !== '') next = rules.fallback
    else if (rules.required) return { kind: 'missing' }
    else next = ''
  }
  const before = trim ? value.trim() : value
  if (next === before) return { kind: 'unchanged' }
  // Only a change is checked: what is stored already stands, whatever the check says of it now.
  const reason = rules.check?.(next)
  return reason ? { kind: 'invalid', reason } : { kind: 'commit', text: next }
}

export type Checked = Settled | { kind: 'invalid'; reason: string }

/**
 * `settle`, then a commit `check` finds wrong is held back with its sentence - the editor stays open
 * on it, as for an emptied required text, and Escape puts the old text back. For a value that must
 * not be saved as typed (a number out of its range, Settings); `validate` alone only warns.
 */
export function settleChecked(value: string, draft: string, rules: SettleRules = {},
                              check?: (text: string) => string | null | undefined): Checked {
  const s = settle(value, draft, rules)
  if (s.kind !== 'commit' || !check) return s
  const reason = check(s.text)
  return reason ? { kind: 'invalid', reason } : s
}
