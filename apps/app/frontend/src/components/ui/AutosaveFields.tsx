import { useEffect, useRef, useState, type FocusEvent, type KeyboardEvent } from 'react'
import { Plus, X } from 'lucide-react'
import { Button, Combobox, IconButton, SecretInput, cn, type SecretInputLabels } from '@/components/ui/kit'
import type { ComboboxProps } from '@/components/ui/kit/Combobox'
import { EditableText } from './EditableText'

// The controls an existing item's dialog saves field by field (useAutosave) that are not plain text:
// a stored secret, a free-text model name with suggestions, and a list of one-line entries. Each saves
// when it is left, as EditableText does, and Escape puts back what it held without closing the dialog.

/** A save whose refusal the page has already said (useAutosave's toast): nothing more to do with it. */
export function quietly(result: void | Promise<unknown>): void {
  if (isPromise(result)) result.catch(() => undefined)
}

// Whether a promise came back - a commit that settles later.
function isPromise(v: unknown): v is Promise<unknown> {
  return !!v && typeof (v as Promise<unknown>).then === 'function'
}

/**
 * A STORED SECRET SAVED WHEN IT IS LEFT. The kit's SecretInput as ever - the stored value masked, never
 * shown as text, typing replacing it - but a value typed is sent when the focus leaves the box (or on
 * Enter), and the box is empty again once it has been. A blank box sends nothing: an empty secret is
 * never a change, since the page is never sent the stored one. Escape drops what was typed.
 */
export function SecretAutosave({ id, serverValue, onCommit, placeholder, labels, error, hint, className }: {
  id?: string
  serverValue?: string
  onCommit: (value: string) => void | Promise<unknown>
  placeholder?: string
  labels: SecretInputLabels
  error?: string
  hint?: React.ReactNode
  className?: string
}) {
  const [draft, setDraft] = useState('')
  const [pending, setPending] = useState(false)
  const draftRef = useRef(draft)
  draftRef.current = draft

  const commit = () => {
    const v = draftRef.current.trim()
    if (!v) { setDraft(''); return }
    const result = onCommit(v)
    if (isPromise(result)) {
      setPending(true)
      // Settled either way, the box is empty again: saved, the mask shows the new value; refused, the
      // page has said why and the stored one stands.
      result.then(() => { setPending(false); setDraft('') }, () => { setPending(false); setDraft('') })
    } else {
      setDraft('')
    }
  }
  const onBlur = (e: FocusEvent<HTMLDivElement>) => {
    if (e.currentTarget.contains(e.relatedTarget as Node | null)) return
    commit()
  }
  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.nativeEvent.isComposing) return
    if (e.key === 'Enter') { e.preventDefault(); commit() }
    if (e.key === 'Escape' && draftRef.current !== '') {
      e.preventDefault()
      e.stopPropagation()
      setDraft('')
    }
  }
  return (
    <div onBlur={onBlur} onKeyDown={onKeyDown} aria-busy={pending || undefined} className={cn(pending && 'opacity-60', className)}>
      <SecretInput
        id={id}
        value={draft}
        serverValue={serverValue}
        onChange={setDraft}
        placeholder={placeholder}
        labels={labels}
        error={error}
        hint={hint}
      />
    </div>
  )
}

/**
 * A FREE-TEXT VALUE WITH SUGGESTIONS, SAVED WHEN IT IS LEFT OR PICKED - a model name. A suggestion
 * picked from the list is saved at once; typed text when the box is left. Escape with the list closed
 * puts back the saved value. `validate` refuses a value before it is sent (its sentence under the box).
 */
export function ComboboxAutosave({ value, onCommit, validate, ...props }: Omit<ComboboxProps, 'onChange' | 'value'> & {
  value: string
  onCommit: (value: string) => void | Promise<unknown>
  validate?: (value: string) => string | null | undefined
}) {
  const [draft, setDraft] = useState(value)
  const [pending, setPending] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const focused = useRef(false)
  const typed = useRef(false)
  const draftRef = useRef(draft)
  draftRef.current = draft
  useEffect(() => { if (!focused.current && !pending) setDraft(value) }, [value, pending])

  const commit = (text: string) => {
    const next = text.trim()
    if (next === value.trim()) { setDraft(value); setProblem(null); return }
    const refused = validate?.(next)
    if (refused) { setProblem(refused); return }
    setProblem(null)
    const result = onCommit(next)
    if (isPromise(result)) {
      setPending(true)
      result.then(() => setPending(false), () => { setPending(false); setDraft(value) })
    }
  }
  return (
    <div
      className={cn('min-w-0', pending && 'opacity-60')}
      aria-busy={pending || undefined}
      onFocus={() => { focused.current = true }}
      onInput={() => { typed.current = true }}
      onBlur={(e: FocusEvent<HTMLDivElement>) => {
        if (e.currentTarget.contains(e.relatedTarget as Node | null)) return
        focused.current = false
        commit(draftRef.current)
      }}
      onKeyDown={(e: KeyboardEvent<HTMLDivElement>) => {
        if (e.key === 'Escape' && draftRef.current !== value) {
          e.preventDefault()
          e.stopPropagation()
          setDraft(value)
          setProblem(null)
        }
      }}
    >
      <Combobox
        {...props}
        value={draft}
        error={problem ?? props.error}
        onChange={v => {
          setDraft(v)
          setProblem(null)
          // Not typed: a suggestion was picked, and a pick is a choice made.
          if (!typed.current) commit(v)
          typed.current = false
        }}
      />
    </div>
  )
}

/**
 * ONE-LINE ENTRIES SAVED ROW BY ROW - a list field's values, a set of URL patterns. Each entry is
 * click-to-edit and saves the list when it is left; an emptied entry is removed. "Add" opens a new
 * entry in place, saved once something is typed into it and dropped if it is left empty. Remove
 * saves at once. `onCommit` is given the whole list.
 */
export function EditableRows({ items, onCommit, label, placeholder, addLabel, removeLabel, editable = true, validate, mono }: {
  items: string[]
  onCommit: (next: string[]) => void | Promise<unknown>
  /** Each entry's accessible name. */
  label: string
  placeholder?: string
  addLabel: string
  removeLabel: string
  editable?: boolean
  validate?: (entry: string) => string | null | undefined
  mono?: boolean
}) {
  // An entry added here and not yet typed into - the list's own, never sent.
  const [adding, setAdding] = useState(false)
  const commitAt = (i: number, text: string) => {
    const next = text.trim() === '' ? items.filter((_, j) => j !== i) : items.map((r, j) => (j === i ? text : r))
    const refused = text.trim() !== '' ? validate?.(text) : null
    if (refused) return Promise.reject(new Error(refused))
    return onCommit(next)
  }
  return (
    <div className="space-y-1">
      {items.map((row, i) => (
        <div key={`${i}:${row}`} className="flex items-start gap-2">
          <div className="flex-1 min-w-0">
            <EditableText
              value={row}
              label={label}
              placeholder={placeholder}
              editable={editable}
              validate={validate}
              className={cn(mono && 'font-mono')}
              onCommit={text => commitAt(i, text)}
            />
          </div>
          {editable && (
            <IconButton icon={X} tone="danger" label={`${removeLabel}: ${row}`} tooltip={removeLabel}
              onClick={() => quietly(onCommit(items.filter((_, j) => j !== i)))} />
          )}
        </div>
      ))}
      {adding && (
        <EditableText
          value=""
          startOpen
          label={label}
          placeholder={placeholder}
          validate={validate}
          className={cn(mono && 'font-mono')}
          onCommit={text => {
            const refused = validate?.(text)
            if (refused) return Promise.reject(new Error(refused))
            return onCommit([...items, text])
          }}
          onDone={() => setAdding(false)}
          onCancel={() => setAdding(false)}
        />
      )}
      {editable && !adding && (
        <Button variant="hyperlink" type="button" onClick={() => setAdding(true)}>
          <Plus size={14} aria-hidden="true" />
          {addLabel}
        </Button>
      )}
    </div>
  )
}
