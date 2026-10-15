/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/PasswordInput.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import { useId, useState } from 'react'
import { Eye, EyeOff } from 'lucide-react'
import { cn } from './cn'
import { FIELD_BASE, FIELD_NO_NATIVE_PASSWORD_UI, fieldBorder } from './field'
import { FieldNote } from './Input'
import { TEXT } from './typeScale'

export interface PasswordInputLabels {
  /** The reveal toggle's name while the password is hidden ("Show password"). */
  show: string
  /** ...and while it is shown ("Hide password"). */
  hide: string
}

interface Props extends Omit<React.InputHTMLAttributes<HTMLInputElement>, 'type'> {
  label?: string
  hint?: React.ReactNode
  error?: string
  /** Mirrors Input's click-time validation pair - see the `error`/`shake` note there. */
  shake?: boolean
  /** The reveal toggle's names, translated. Required: an icon-only toggle has no other name. */
  labels: PasswordInputLabels
}

/**
 * A FRESH SECRET with a reveal toggle - a sign-in password, a new passphrase. (A secret the server
 * already holds is `SecretInput`.) It is `Input` with a trailing button: the same label (13/500, 8px
 * above), the same hint and error line under it (`FieldNote`, tied to the field by
 * `aria-describedby`, the error by `aria-invalid` too), the same field.
 *
 * The toggle SHOWS ITS ACTION, like SecretInput's: an eye while the password is hidden ("show"), a
 * crossed eye while it is shown ("hide"). It is a toggle button (`aria-pressed`), named by `labels`.
 * It stays out of the tab sequence: it is a convenience, not a step in filling the form, and
 * between the password and the submit button it put a stop in every keyboard sign-in. A screen
 * reader still reaches it in the accessibility tree.
 */
export function PasswordInput({ label, hint, error, shake, labels, className, id, ...props }: Props) {
  const [visible, setVisible] = useState(false)
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
      <div className="relative">
        <input
          id={inputId}
          type={visible ? 'text' : 'password'}
          aria-invalid={error ? true : undefined}
          aria-describedby={error || hint ? noteId : undefined}
          className={cn(
            FIELD_BASE,
            'pl-3 pr-11',
            fieldBorder(error),
            FIELD_NO_NATIVE_PASSWORD_UI,
            shake && 'animate-shake',
            className,
          )}
          {...props}
        />
        <button
          type="button"
          tabIndex={-1}
          onClick={() => setVisible(v => !v)}
          aria-label={visible ? labels.hide : labels.show}
          aria-pressed={visible}
          className="absolute right-1 top-1/2 -translate-y-1/2 w-8 h-8 touch:w-9 touch:h-9 rounded-md flex items-center justify-center text-ink-caption mouse:hover:text-ink-primary transition-colors focus-ring"
        >
          {visible ? <EyeOff className="w-4 h-4" aria-hidden="true" /> : <Eye className="w-4 h-4" aria-hidden="true" />}
        </button>
      </div>
      <FieldNote id={noteId} error={error} hint={hint} />
    </div>
  )
}
