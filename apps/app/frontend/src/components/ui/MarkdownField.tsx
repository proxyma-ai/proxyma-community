import { Suspense, lazy, useId, useRef } from 'react'
import { cn } from '@/components/ui/kit/cn'
import { FIELD_BASE, fieldBorder, fieldWords } from '@/components/ui/kit/field'
import { FIELD_BODY, FIELD_HEAD, FIELD_STACK, FieldHint, FieldNote } from '@/components/ui/kit/Input'
import { TEXT } from '@/components/ui/kit/typeScale'

const MarkdownEditor = lazy(() => import('./MarkdownEditor').then(m => ({ default: m.MarkdownEditor })))

/**
 * The least height of the box by the number of lines it should show at rest - written out, since a
 * class built at run time is never generated. 20px a line and the 12px of padding.
 */
const MIN_ROWS: Record<number, string> = {
  2: '[&_.cm-content]:min-h-[52px]',
  3: '[&_.cm-content]:min-h-[72px]',
  4: '[&_.cm-content]:min-h-[92px]',
  6: '[&_.cm-content]:min-h-[132px]',
  8: '[&_.cm-content]:min-h-[172px]',
  10: '[&_.cm-content]:min-h-[212px]',
  18: '[&_.cm-content]:min-h-[372px]',
}

export interface MarkdownFieldProps {
  value: string
  onChange?: (value: string) => void
  label?: string
  /** Under the label, above the box - or under the box when there is no label (as `Textarea`). */
  hint?: React.ReactNode
  error?: string
  placeholder?: string
  /** Drawn faded and not editable - an official skill's text, a prompt the reader may not change. */
  disabled?: boolean
  maxLength?: number
  /** Lines shown at rest: 2, 3, 4, 6, 8, 10 or 18. A box that is not `fill` grows with its text to 20 lines, then scrolls. */
  rows?: 2 | 3 | 4 | 6 | 8 | 10 | 18
  /** Grow to the height of its container - a panel's or a fixed-height dialog's one editor. */
  fill?: boolean
  shake?: boolean
  autoFocus?: boolean
  id?: string
  'aria-label'?: string
  className?: string
  /**
   * Heard when the focus leaves the box - for a box that saves as it is left. A move within it (the
   * label to the editor) is not leaving it.
   */
  onBlur?: () => void
}

/**
 * A BOX OF MARKDOWN, DRAWN AS IT READS (the maintainer, 2026-10-31: "make all of these kind of box
 * live markdown render support, just like the chat interface"). For text written FOR A MODEL in
 * markdown - an agent's instructions, a skill, a system prompt, a workflow's steps, a task, a
 * workspace note. Headings, bold, lists, code and links are drawn as the chat draws them; the marks
 * that make them show, dimmed, on the line being edited (`markdownPreview.ts`). The text is kept
 * exactly as typed.
 *
 * The same label, hint and error as the kit's `Textarea`, and the same box (`FIELD_BASE`). Not for
 * plain text that is never markdown - a note, a CSV, a certificate: those stay `Textarea`.
 */
export function MarkdownField({
  value, onChange, label, hint, error, placeholder, disabled, maxLength, rows = 4, fill, shake, autoFocus, id, className,
  'aria-label': ariaLabel, onBlur,
}: MarkdownFieldProps) {
  const generated = useId()
  const fieldId = id ?? generated
  const labelId = `${fieldId}-label`
  const words = fieldWords(fieldId, { labelled: !!label, hint: !!hint, error: !!error })
  const focus = useRef<(() => void) | null>(null)

  const attributes: Record<string, string> = { id: fieldId }
  if (label) attributes['aria-labelledby'] = labelId
  else if (ariaLabel) attributes['aria-label'] = ariaLabel
  if (words.describedBy) attributes['aria-describedby'] = words.describedBy
  if (error) attributes['aria-invalid'] = 'true'

  return (
    <div
      className={cn('flex flex-col', fill && 'flex-1 min-h-0', label && FIELD_STACK)}
      onBlur={onBlur && (e => {
        const box = e.currentTarget
        const to = e.relatedTarget as Node | null
        if (to) {
          if (!box.contains(to)) onBlur()
          return
        }
        // The focus went nowhere: a click on the page, another window - or the editor replaced under
        // it (React's development double mount), which focuses its new one in the same turn. Decided
        // once the turn is over: still on the page and no longer holding the focus is leaving it; a
        // box taken off the page was not left, it went (its page says what that means).
        window.setTimeout(() => {
          if (box.isConnected && !box.contains(document.activeElement)) onBlur()
        }, 0)
      })}
    >
      {label && (
        <div className={FIELD_HEAD}>
          <label id={labelId} htmlFor={fieldId} onClick={() => focus.current?.()}
            className={cn(TEXT.label, 'block', words.hintAbove ? 'mb-1' : 'mb-2')}>
            {label}
          </label>
          {words.hintAbove && <FieldHint id={words.hintId}>{hint}</FieldHint>}
        </div>
      )}
      <div className={cn(FIELD_BODY, fill && 'flex-1 min-h-0')}>
        <div
          className={cn(
            FIELD_BASE,
            'py-0 cursor-text overflow-hidden',
            MIN_ROWS[rows] ?? MIN_ROWS[4],
            fill ? 'flex-1 min-h-0' : '[&_.cm-scroller]:max-h-[412px]',
            disabled && 'opacity-40 cursor-not-allowed',
            shake && 'animate-shake',
            fieldBorder(error),
            className,
          )}
        >
          <Suspense fallback={<div className="px-3 py-1.5 whitespace-pre-wrap break-words">{value || ' '}</div>}>
            <MarkdownEditor
              value={value}
              onChange={onChange}
              placeholder={placeholder}
              readOnly={disabled}
              maxLength={maxLength}
              autoFocus={autoFocus}
              contentAttributes={attributes}
              focusRef={focus}
            />
          </Suspense>
        </div>
        <FieldNote id={words.noteId} error={error} hint={words.hintAbove ? undefined : hint} />
      </div>
    </div>
  )
}
