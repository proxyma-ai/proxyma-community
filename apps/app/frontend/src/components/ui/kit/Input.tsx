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
import { FIELD_BASE, FIELD_NO_NATIVE_PASSWORD_UI, fieldBorder } from './field'
import { TEXT } from './typeScale'

interface Props extends React.InputHTMLAttributes<HTMLInputElement> {
  label?: string
  hint?: React.ReactNode
  /**
   * Set by a click-time validation check: shows a red border and this message below the
   * field instead of `hint`. Pair with `shake` to also draw the eye on the failed attempt.
   */
  error?: string
  shake?: boolean
}

/**
 * THE TEXT FIELD, with its label above (13/500, `TEXT.label`) and its line below (`FieldNote`).
 *
 * Since 2026-10-14 the id is `useId()` when none is given - it was the label's text lower-cased, so
 * two fields with one label on a page shared an id and the second label pointed at the first field
 * - and the line under the field is TIED to it: `aria-describedby` names the hint or the error, and
 * an error sets `aria-invalid`, so a screen reader reads both rather than only the moment an error
 * appears.
 */
export function Input({ label, hint, error, shake, className, id, type, ...props }: Props) {
  const generated = useId()
  const inputId = id ?? generated
  const noteId = `${inputId}-note`

  return (
    <div className="flex flex-col">
      {label && (
        <label htmlFor={inputId} className={cn(TEXT.label, 'mb-2')}>
          {label}
        </label>
      )}
      <input
        id={inputId}
        type={type}
        aria-invalid={error ? true : undefined}
        aria-describedby={error || hint ? noteId : undefined}
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
      <FieldNote id={noteId} error={error} hint={hint} />
    </div>
  )
}

/**
 * The line under a field: the error when there is one, else the hint - 13px, `mt-1.5`, always
 * BELOW the control. Hints sat in six positions across Settings, some pulled up with negative
 * margins; this is the one place. Give it the `id` the control names in `aria-describedby`.
 */
export function FieldNote({ error, hint, id }: { error?: string; hint?: React.ReactNode; id?: string }) {
  if (error) return <p id={id} role="alert" className="mt-1.5 text-xs leading-relaxed text-danger animate-fade-in">{error}</p>
  if (hint) return <p id={id} className="mt-1.5 text-xs leading-relaxed text-ink-caption">{hint}</p>
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
  const noteId = `${inputId}-note`

  return (
    <div className={cn('flex flex-col', fill && 'flex-1 min-h-0')}>
      {label && (
        <label htmlFor={inputId} className={cn(TEXT.label, 'mb-2')}>
          {label}
        </label>
      )}
      <textarea
        id={inputId}
        aria-invalid={error ? true : undefined}
        aria-describedby={error || hint ? noteId : undefined}
        className={cn(
          FIELD_BASE,
          'px-3 min-h-[72px] leading-relaxed',
          resize ? 'resize-y' : 'resize-none',
          mono && 'font-mono',
          fill && 'flex-1 min-h-0',
          shake && 'animate-shake',
          fieldBorder(error),
          className,
        )}
        {...props}
      />
      <FieldNote id={noteId} error={error} hint={hint} />
    </div>
  )
}
