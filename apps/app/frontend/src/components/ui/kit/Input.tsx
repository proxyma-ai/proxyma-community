/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/Input.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import { useId } from 'react'
import { cn } from './cn'
import { FIELD_BASE, FIELD_NO_NATIVE_PASSWORD_UI, fieldBorder, fieldWords } from './field'
import { TEXT } from './typeScale'

interface Props extends React.InputHTMLAttributes<HTMLInputElement> {
  label?: string
  /** Under the label, above the field - or under the field when there is no label. */
  hint?: React.ReactNode
  /**
   * Set by a click-time validation check: shows a red border and this message below the
   * field. Pair with `shake` to also draw the eye on the failed attempt.
   */
  error?: string
  shake?: boolean
}

/**
 * THE TEXT FIELD: its label (14/500, `TEXT.label`), the hint under the label, the control, and the
 * error under the control.
 *
 * WHERE THE WORDS GO, since 2026-10-29 (the maintainer's review: "Description should be placed below
 * the section title"): a field's HINT sits directly UNDER ITS LABEL and ABOVE the control
 * (`FieldHint` - the label, 4px, the hint in 13/18 caption ink, 8px, the control), so the reader
 * meets the explanation before the box it explains. Until then it sat under the control, where a
 * swatch row or a textarea pushed it away from the name it belonged to. The ERROR stays BELOW the
 * control (`FieldNote`): it points at what is wrong in it. A field with NO label keeps its hint
 * below the control - a caption over a bare control reads as the control's title. `fieldWords`
 * (`field.ts`) decides it for every kit field.
 *
 * Since 2026-10-14 the id is `useId()` when none is given - it was the label's text lower-cased, so
 * two fields with one label on a page shared an id and the second label pointed at the first field
 * - and the words are TIED to the field: `aria-describedby` names the hint and the error (both, when
 * both show), and an error sets `aria-invalid`, so a screen reader reads both rather than only the
 * moment an error appears.
 */
export function Input({ label, hint, error, shake, className, id, type, ...props }: Props) {
  const generated = useId()
  const inputId = id ?? generated
  const words = fieldWords(inputId, { labelled: !!label, hint: !!hint, error: !!error })

  return (
    <div className={cn('flex flex-col', label && FIELD_STACK)}>
      {label && (
        <div className={FIELD_HEAD}>
          <label htmlFor={inputId} className={cn(TEXT.label, 'block', words.hintAbove ? 'mb-1' : 'mb-2')}>
            {label}
          </label>
          {words.hintAbove && <FieldHint id={words.hintId}>{hint}</FieldHint>}
        </div>
      )}
      <div className={FIELD_BODY}>
        <input
          id={inputId}
          type={type}
          aria-invalid={error ? true : undefined}
          aria-describedby={words.describedBy}
          className={cn(
            FIELD_BASE,
            'px-3',
            fieldBorder(error),
            type === 'password' && FIELD_NO_NATIVE_PASSWORD_UI,
            shake && 'animate-shake',
            className,
          )}
          {...props}
        />
        <FieldNote id={words.noteId} error={error} hint={words.hintAbove ? undefined : hint} />
      </div>
    </div>
  )
}

/**
 * A labelled field's TWO PARTS, for side-by-side fields (`FormColumns`): the head (the label and the
 * hint under it) and the body (the control and the error under it). The hint over the control made
 * the controls of a row stand at different heights whenever one field had a hint and its neighbor
 * did not; `.field-stack` makes each field a two-row subgrid of the columns
 * (`proxyma-primitives.css`), so every head in a row takes the tallest head's height and the
 * controls line up. A field with no label is not a stack, and a row holding one is laid out as
 * before. Used by every kit field; a hand-made one in a `FormColumns` takes them too.
 */
export const FIELD_STACK = 'field-stack'
export const FIELD_HEAD = 'min-w-0'
// flex-1 min-h-0: a field given a height (a column of a fixed-height dialog) hands it to its
// control, so a list or a `fill` textarea fills the column and scrolls inside it.
export const FIELD_BODY = 'flex flex-1 flex-col min-w-0 min-h-0'

/**
 * A field's hint UNDER ITS LABEL, above the control - 13/18 caption ink, 8px over the control (the
 * label takes `mb-1` over it). About 70 characters a line at most, as every caption that explains
 * (the UI review, 2026-10-28). Give it the `id` the control names in `aria-describedby`. For a field
 * the kit does not draw, use `Field` with a `hint` - it places this for you.
 */
export function FieldHint({ id, children, className }: { id?: string; children: React.ReactNode; className?: string }) {
  return <div id={id} className={cn(TEXT.caption, 'mb-2 max-w-[60ch]', className)}>{children}</div>
}

/**
 * The line UNDER a field: its error, or - for a field with no label (see `fieldWords`) - its hint,
 * 13/18, `mt-2`. A labelled field's hint is `FieldHint`, over the control (since 2026-10-29). Hints
 * once sat in six positions across Settings, some pulled up with negative margins; this pair is the
 * one place. Give it the `id` the control names in `aria-describedby`.
 */
export function FieldNote({ error, hint, id }: { error?: string; hint?: React.ReactNode; id?: string }) {
  if (error) return <p id={id} role="alert" className="mt-2 text-xs text-danger animate-fade-in">{error}</p>
  // About 70 characters a line at most, as every caption that explains (the UI review, 2026-10-28).
  if (hint) return <p id={id} className="mt-2 text-xs text-ink-caption max-w-[60ch]">{hint}</p>
  return null
}

export function Textarea({
  label,
  hint,
  error,
  className,
  id,
  mono,
  shake,
  fill,
  resize = false,
  ...props
}: React.TextareaHTMLAttributes<HTMLTextAreaElement> & {
  label?: string
  /** Under the label, above the box - or under the box when there is no label (see `Input`). */
  hint?: React.ReactNode
  error?: string
  /** For code, a prompt template, a certificate. */
  mono?: boolean
  shake?: boolean
  /** Grow to the height of its container - a fixed-height dialog's one editor. */
  fill?: boolean
  /**
   * Off by default: dialogs do not scroll (frontend.md), so a drag handle that grows the textarea
   * grows the dialog past its layout. `true` allows vertical resizing only - a horizontally
   * resizable box can be dragged wider than the panel holding it.
   */
  resize?: boolean
}) {
  const generated = useId()
  const inputId = id ?? generated
  const words = fieldWords(inputId, { labelled: !!label, hint: !!hint, error: !!error })

  return (
    <div className={cn('flex flex-col', fill && 'flex-1 min-h-0', label && FIELD_STACK)}>
      {label && (
        <div className={FIELD_HEAD}>
          <label htmlFor={inputId} className={cn(TEXT.label, 'block', words.hintAbove ? 'mb-1' : 'mb-2')}>
            {label}
          </label>
          {words.hintAbove && <FieldHint id={words.hintId}>{hint}</FieldHint>}
        </div>
      )}
      <div className={cn(FIELD_BODY, fill && 'flex-1 min-h-0')}>
        <textarea
          id={inputId}
          aria-invalid={error ? true : undefined}
          aria-describedby={words.describedBy}
          className={cn(
            FIELD_BASE,
            'px-3 min-h-[72px]',
            resize ? 'resize-y' : 'resize-none',
            mono && 'font-mono',
            fill && 'flex-1 min-h-0',
            shake && 'animate-shake',
            fieldBorder(error),
            className,
          )}
          {...props}
        />
        <FieldNote id={words.noteId} error={error} hint={words.hintAbove ? undefined : hint} />
      </div>
    </div>
  )
}
