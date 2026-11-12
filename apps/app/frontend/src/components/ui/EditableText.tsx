import { useEffect, useRef, useState, type ElementType, type KeyboardEvent, type MouseEvent, type ReactNode } from 'react'
import { Button, Caption, FadeSwap, Presence, Tooltip, cn } from '@/components/ui/kit'
import { useTranslation } from '@/i18n'
import { caretAt, editorRows, keyAction, settleChecked } from './clickToEdit'

/**
 * The same box in both states, so starting to edit moves nothing: a border that shows only when it means something. It
 * reaches 8px past the text on EACH side, so the text stays on the column's edge - `w-full` with `-mx-2` moved the whole
 * box 8px left instead, out on the left and short on the right (2026-11-10, an approval's note). A caller drawing it as
 * a field, its box on the column's edges, passes `mx-0 w-full`.
 */
const BOX = 'block w-[calc(100%+1rem)] min-w-0 rounded-md border px-2 -mx-2 py-0.5 text-left'

// The offset in `value` under the pointer, when the pointer is over the text itself; null otherwise.
function offsetUnder(e: MouseEvent<HTMLElement>, value: string): number | null {
  const doc = e.currentTarget.ownerDocument as Document & {
    caretPositionFromPoint?: (x: number, y: number) => { offsetNode: Node; offset: number } | null
  }
  let node: Node | null = null
  let offset: number | null = null
  if (typeof doc.caretPositionFromPoint === 'function') {
    const p = doc.caretPositionFromPoint(e.clientX, e.clientY)
    if (p) { node = p.offsetNode; offset = p.offset }
  } else if (typeof doc.caretRangeFromPoint === 'function') {
    const r = doc.caretRangeFromPoint(e.clientX, e.clientY)
    if (r) { node = r.startContainer; offset = r.startOffset }
  }
  // Only a text node that IS the value maps one to one onto the editor's text.
  if (!node || node.nodeType !== Node.TEXT_NODE || !e.currentTarget.contains(node) || node.textContent !== value) return null
  return offset
}

/**
 * A TEXT EDITED WHERE IT IS READ - the one click-to-edit of the app (the maintainer, 2026-11-02: "hover
 * mouse on them turns the pointer to cursor. Click on them could edit them. Unfocus auto saves the
 * changes", to be applied to every text field but the special ones). It replaced the Approvals page's
 * ClickToEditText and the agent panel's InlineText.
 *
 * At rest it is the text - drawn by `children` when the page draws it its own way (a quote), else as it
 * is in `className`'s type style - or `placeholder` in caption ink while it is empty. Under a mouse it
 * shows a text cursor and a field's line. A click, or Enter or Space on the focused text, turns it into
 * an editor of the same box in place, the caret where the text was clicked (else at its end): an input,
 * or a textarea with `multiline`.
 *
 * Leaving the editor COMMITS it (clickToEdit.settle): blur, Enter on a single line, Ctrl+Enter on
 * several. Only a change reaches `onCommit` - trimmed unless `trim` is false; an emptied text becomes
 * `fallback` when there is one, and is refused with `required`'s sentence when it is required. Escape
 * puts back the text it was opened on and calls `onCancel`. `onCommit` may return a promise: the new
 * text shows, a little faded, while it settles, and a rejection puts the old one back (the page says why,
 * in a toast). `onDone` hears every close but Escape, a commit or not - a note left empty goes again.
 *
 * `editable` false is plain text in the same box. `as` names the element the text sits in at rest - a
 * page's title is an `h1`. `validate` checks the draft as it is typed; its sentence, or `error` from the
 * page, shows under it in either state. `startOpen` mounts it already open - a note just added. The swap
 * between the two states fades (FadeSwap, which reduced motion stills).
 *
 * `refuseInvalid` makes `validate` a gate rather than a warning: a draft it finds wrong is not committed,
 * the editor stays open on its sentence and Escape puts the old text back - a number a setting cannot
 * take (Settings, 2026-11-02). `inputMode` picks the on-screen keyboard (`numeric`, `decimal`).
 *
 * `check` refuses the same way with a sentence of its own, without warning as the text is typed - a
 * step limit of 500, a budget that is no number. `onOpen` hears the editor open, for a page that
 * compares what the text was then with what the server says when it is left.
 */
export function EditableText({
  value, onCommit, editable = true, multiline = false, label, placeholder, maxLength, trim, fallback, required,
  validate, error, startOpen, onCancel, onDone, cancelButton, truncate, as: As = 'div', className, children,
  refuseInvalid, inputMode, check, onOpen,
}: {
  value: string
  /** The text as it is left, when it changed - see `settle`. A promise holds the new text until it settles. */
  onCommit: (text: string) => void | Promise<unknown>
  editable?: boolean
  /** A textarea, a new line on Enter and Ctrl+Enter to commit; else one line. */
  multiline?: boolean
  /** Its accessible name - "Project name", "Reply to post". */
  label: string
  /** Shown in caption ink while the text is empty, in either state. */
  placeholder?: string
  maxLength?: number
  /** Strip the ends before comparing and committing. Default true. */
  trim?: boolean
  /** What an emptied text becomes - "Untitled Project". */
  fallback?: string
  /** An emptied text is refused, and this says why under the editor. */
  required?: string
  /** Checks the draft as it is typed; the sentence shows under the editor. Never blocks the commit. */
  validate?: (draft: string) => string | null | undefined
  /** The page's own sentence about the text, shown under it in either state. */
  error?: string
  startOpen?: boolean
  onCancel?: () => void
  onDone?: (text: string) => void
  /** A quiet Cancel under a multi-line editor, for a reader who does not know Escape. */
  cancelButton?: boolean
  /** One line at rest, cut with an ellipsis - a panel's header; else the text wraps. */
  truncate?: boolean
  as?: ElementType
  /** The type style both states share - `TEXT.pageTitle`, `TEXT.caption`. */
  className?: string
  /** The text at rest, drawn as the page draws it (a quote); the bare text when absent. */
  children?: ReactNode
  /** A draft `validate` finds wrong is refused - the editor stays open on the sentence - not committed. */
  refuseInvalid?: boolean
  /** The on-screen keyboard a single-line editor asks for. */
  inputMode?: 'text' | 'numeric' | 'decimal' | 'url'
  /**
   * A changed text that cannot be saved - a number out of range - gets its sentence: the commit is
   * refused and the editor stays open saying it, as an emptied required text does. Escape still reverts.
   */
  check?: (text: string) => string | null | undefined
  /** Heard when the editor opens - a page that must know what the text was when the edit began. */
  onOpen?: () => void
}) {
  const { t } = useTranslation()
  const [draft, setDraft] = useState<string | null>(startOpen && editable ? value : null)
  const [missing, setMissing] = useState(false)
  // A draft `validate` refused (refuseInvalid): the editor stays open with the field's line in danger.
  const [refused, setRefused] = useState(false)
  // The sentence `check` refused a draft with, until the draft changes.
  const [invalid, setInvalid] = useState<string | null>(null)
  // The text a commit still settling put up, shown in place of `value` until the page has it.
  const [pending, setPending] = useState<string | null>(null)
  const caret = useRef<number>(value.length)
  const latest = useRef(value)
  latest.current = value
  // Whether the editor is open, read by the handlers: the box unmounting after Enter or Escape may still
  // report a blur, which must not commit a second time - or at all, after Escape.
  const active = useRef(draft !== null)
  // Closed from the keyboard: the focus goes back to the text, not to the page's start.
  const refocus = useRef(false)
  const rest = useRef<HTMLSpanElement>(null)
  // The page has the text now (or another): what it says wins.
  useEffect(() => { setPending(null) }, [value])

  const editing = draft !== null
  useEffect(() => {
    if (!editing && refocus.current) {
      refocus.current = false
      rest.current?.focus()
    }
  }, [editing])
  const shown = pending ?? value
  const rules = { trim, fallback, required: required !== undefined, check }

  const open = (offset: number | null) => {
    if (!editable) return
    caret.current = caretAt(shown.length, offset)
    active.current = true
    setMissing(false)
    setRefused(false)
    setInvalid(null)
    setDraft(shown)
    onOpen?.()
  }
  const finish = () => {
    if (draft === null || !active.current) return
    const s = settleChecked(shown, draft, rules, refuseInvalid ? validate : undefined)
    if (s.kind === 'missing' || s.kind === 'invalid') {
      refocus.current = false
      if (s.kind === 'missing') setMissing(true)
      else {
        setRefused(true)
        setInvalid(s.reason)
      }
      return
    }
    active.current = false
    setDraft(null)
    setMissing(false)
    setRefused(false)
    setInvalid(null)
    if (s.kind === 'commit') {
      const result = onCommit(s.text)
      if (result && typeof (result as Promise<unknown>).then === 'function') {
        setPending(s.text)
        ;(result as Promise<unknown>).then(
          () => { if (latest.current === s.text) setPending(null) },
          () => setPending(null),
        )
      }
    }
    onDone?.(s.kind === 'commit' ? s.text : draft)
  }
  const cancel = () => {
    if (!active.current) return
    active.current = false
    setDraft(null)
    setMissing(false)
    setRefused(false)
    setInvalid(null)
    onCancel?.()
  }
  const onEditorKey = (e: KeyboardEvent<HTMLInputElement | HTMLTextAreaElement>) => {
    const action = keyAction({ key: e.key, ctrlKey: e.ctrlKey, metaKey: e.metaKey, shiftKey: e.shiftKey, isComposing: e.nativeEvent.isComposing }, multiline)
    if (action === 'cancel') {
      // Its own Escape: a dialog or panel around it does not close as well.
      e.preventDefault()
      e.stopPropagation()
      refocus.current = true
      cancel()
    } else if (action === 'commit') {
      e.preventDefault()
      refocus.current = true
      finish()
    }
  }
  const onRestKey = (e: KeyboardEvent<HTMLElement>) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault()
      open(null)
    }
  }

  const note = missing ? required : editing && validate ? validate(draft) ?? invalid ?? error : invalid ?? error
  const empty = shown.trim() === ''
  const flow = truncate ? 'truncate' : multiline ? 'break-words whitespace-pre-line' : 'break-words'
  const body = empty ? <span className="text-ink-caption">{placeholder}</span> : children ?? shown

  if (!editable) {
    return <As className={cn(BOX, className, 'border-transparent', flow)}>{body}</As>
  }

  const editorProps = {
    autoFocus: true,
    value: draft ?? '',
    maxLength,
    placeholder,
    'aria-label': label,
    'aria-invalid': missing || refused || undefined,
    onBlur: finish,
    onKeyDown: onEditorKey,
    className: cn(BOX, className, 'bg-field outline-none placeholder:text-ink-caption',
      missing || refused ? 'border-danger' : 'border-line focus:border-brand-solid'),
  }
  return (
    <div className="min-w-0">
      <FadeSwap swapKey={editing}>
        {editing ? (
          multiline ? (
            <div className="space-y-2">
              <textarea
                {...editorProps}
                rows={editorRows(draft ?? '')}
                onChange={e => { setDraft(e.target.value); setMissing(false); setRefused(false); setInvalid(null) }}
                onFocus={e => e.currentTarget.setSelectionRange(caret.current, caret.current)}
                // As tall as what is typed, wrapping included, up to eight lines; then it scrolls.
                className={cn(editorProps.className, 'resize-y [field-sizing:content] max-h-48 overflow-y-auto')}
              />
              {cancelButton && (
                // Pressed without taking the focus, so the box is not left (and kept) before Cancel runs.
                <Button variant="quiet" size="sm" onMouseDown={e => e.preventDefault()} onClick={cancel}>{t('Cancel')}</Button>
              )}
            </div>
          ) : (
            <input
              {...editorProps}
              inputMode={inputMode}
              onChange={e => { setDraft(e.target.value); setMissing(false); setRefused(false); setInvalid(null) }}
              onFocus={e => e.currentTarget.setSelectionRange(caret.current, caret.current)}
            />
          )
        ) : (
          <Tooltip label={t('Click to edit')} fullWidth>
            <As className="m-0 min-w-0">
              <span
                ref={rest}
                role="button"
                tabIndex={0}
                aria-busy={pending !== null || undefined}
                aria-label={t('{label}: {value}. Click to change it.', { label, value: shown || placeholder || '' })}
                onClick={e => open(offsetUnder(e, shown))}
                onKeyDown={onRestKey}
                className={cn(BOX, className, 'border-transparent cursor-text', flow,
                  'transition-[border-color,opacity] duration-base focus-ring mouse:hover:border-line',
                  pending !== null && 'opacity-60')}
              >
                {body}
              </span>
            </As>
          </Tooltip>
        )}
      </FadeSwap>
      <Presence show={!!note}>
        <Caption tone="danger" className="mt-1">{note ?? ''}</Caption>
      </Presence>
    </div>
  )
}
