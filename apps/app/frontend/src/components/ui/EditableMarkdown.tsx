import { useEffect, useRef, useState, type FocusEvent, type KeyboardEvent } from 'react'
import { MarkdownField } from './MarkdownField'
import { cn } from '@/components/ui/kit/cn'
import { settle } from './clickToEdit'

/**
 * A BOX OF MARKDOWN EDITED WHERE IT IS READ - the markdown counterpart of EditableText (the maintainer,
 * 2026-11-02: a skill's detail, a workflow's context and its steps are click-to-edit too, saving on
 * blur, Escape putting the text back).
 *
 * At rest it is the text, drawn as it reads, with no field around it; under a mouse a text cursor and
 * the field's line show. A click puts the caret where it landed (CodeMirror places it), and the box
 * becomes the field. Leaving it COMMITS the text - only a change reaches `onCommit`, which may return
 * a promise: a rejection puts the text it had back (the page says why). Escape puts back the text it
 * was opened on and leaves the box, without closing the dialog around it; Ctrl+Enter leaves it too.
 *
 * `editable` false is the read-only box MarkdownField draws for a text the reader may not change.
 */
export function EditableMarkdown({
  value, onCommit, editable = true, label, placeholder, rows, fill, maxLength, className, id, 'aria-label': ariaLabel,
}: {
  value: string
  onCommit: (text: string) => void | Promise<unknown>
  editable?: boolean
  label?: string
  placeholder?: string
  rows?: 2 | 3 | 4 | 6 | 8 | 10 | 18
  fill?: boolean
  maxLength?: number
  className?: string
  id?: string
  'aria-label'?: string
}) {
  const [draft, setDraft] = useState(value)
  const [focused, setFocused] = useState(false)
  const [pending, setPending] = useState(false)
  const draftRef = useRef(draft)
  draftRef.current = draft
  // Escape leaves the box without committing: the blur it causes must not save.
  const canceled = useRef(false)

  // The page's text wins whenever the box is not being edited or settling - a commit landing, a
  // refused one going back, a reset.
  useEffect(() => {
    if (!focused && !pending) setDraft(value)
  }, [value, focused, pending])

  const commit = () => {
    const s = settle(value, draftRef.current, { trim: false })
    if (s.kind !== 'commit') return
    // A text that differs only at its ends is not a change worth a write.
    if (s.text.trim() === value.trim()) return
    const result = onCommit(s.text)
    if (result && typeof (result as Promise<unknown>).then === 'function') {
      setPending(true)
      ;(result as Promise<unknown>).then(() => setPending(false), () => { setPending(false); setDraft(value) })
    }
  }

  const onBlur = (e: FocusEvent<HTMLDivElement>) => {
    if (e.currentTarget.contains(e.relatedTarget as Node | null)) return
    setFocused(false)
    if (canceled.current) { canceled.current = false; return }
    commit()
  }

  const onKeyDownCapture = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.nativeEvent.isComposing) return
    const leave = e.key === 'Escape' || (e.key === 'Enter' && (e.ctrlKey || e.metaKey))
    if (!leave) return
    // Its own Escape: the dialog around it stays open.
    e.preventDefault()
    e.stopPropagation()
    if (e.key === 'Escape') {
      canceled.current = true
      setDraft(value)
    }
    ;(document.activeElement as HTMLElement | null)?.blur()
  }

  if (!editable) {
    return <MarkdownField id={id} label={label} aria-label={ariaLabel} value={value} placeholder={placeholder} rows={rows} fill={fill} disabled className={className} />
  }

  return (
    <div
      className={cn('flex flex-col min-w-0', fill && 'flex-1 min-h-0')}
      onFocus={() => setFocused(true)}
      onBlur={onBlur}
      onKeyDownCapture={onKeyDownCapture}
      aria-busy={pending || undefined}
    >
      <MarkdownField
        id={id}
        label={label}
        aria-label={ariaLabel}
        value={draft}
        onChange={setDraft}
        placeholder={placeholder}
        rows={rows}
        fill={fill}
        maxLength={maxLength}
        // At rest the text with no field around it - a line and a text cursor under the mouse; the
        // field itself while it is being edited. The same box in both, so nothing moves.
        className={cn(
          className,
          !focused && 'bg-transparent border-transparent mouse:hover:border-line',
          pending && 'opacity-60',
        )}
      />
    </div>
  )
}
