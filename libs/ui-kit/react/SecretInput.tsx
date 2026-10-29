'use client'

import { useRef, useState } from 'react'
import { Eye, EyeOff } from 'lucide-react'
import { cn } from './cn'
import { FIELD_BASE, FIELD_NO_NATIVE_PASSWORD_UI, fieldBorder } from './field'
import { FieldNote } from './Input'

function maskValue(val: string): string {
  if (!val || val.length <= 8) return val ? '*'.repeat(val.length) : ''
  return `${val.slice(0, 4)}${'*'.repeat(Math.min(val.length - 8, 20))}${val.slice(-4)}`
}

export interface SecretInputLabels {
  /** The eye on a stored secret ("Reveal"). */
  reveal: string
  /** The crossed eye while it is shown ("Hide"). */
  hide: string
  /** The eye while typing a new one ("Show"). */
  show: string
}

/**
 * A STORED SECRET: shows a masked preview of what the server already holds, and becomes a real
 * input once revealed or typed into - an API key, a token, a client secret, a license key.
 *
 * ONE control for every stored secret. A provider's key was a raw password input with a "Stored
 * (...) - type to replace" placeholder and a connection's was a kit `Input` with the same
 * placeholder, beside this one. A secret entered fresh with nothing stored (a passphrase) is
 * `PasswordInput`.
 *
 * ONE FIELD IN EVERY STATE, since 2026-10-14. The masked preview was a `<div>` with no focusable
 * element and no id, so the label pointed at nothing, Tab skipped the whole control, and a
 * keyboard could not replace a stored key at all (WCAG 2.1.1). Now the preview IS the input -
 * read-only, carrying `id` - so it takes focus like any field: typing a character or pasting
 * starts the replacement with it, and the field becomes the password box it always was.
 *
 * The eye shows its ACTION (an eye to show, a crossed eye to hide), like PasswordInput's. While a
 * stored value is masked it is in the tab order - revealing is then the one thing to do besides
 * typing; while typing it is not, so a form of credentials does not put an eye between every pair.
 */
export function SecretInput({ value, serverValue, onChange, placeholder, labels, id, autoFocus, hint, error, className }: {
  value: string
  /** What the server holds (masked by the server or not - it is masked again here). */
  serverValue?: string
  onChange: (value: string) => void
  placeholder?: string
  labels: SecretInputLabels
  id?: string
  autoFocus?: boolean
  /**
   * The line under the field, when there is no error - for a secret with NO label. Inside a
   * labelled `Field`, give the hint to the `Field`, which puts it under the label (2026-10-29).
   */
  hint?: React.ReactNode
  error?: string
  className?: string
}) {
  const [revealed, setRevealed] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)
  const hasStored = !!serverValue
  const editing = value !== '' && value !== serverValue
  const masked = hasStored && !editing && !revealed
  const noteId = id ? `${id}-note` : undefined
  const eye = 'flex-shrink-0 text-ink-caption mouse:hover:text-ink-primary transition-colors focus-ring rounded-sm'

  // Typing into the masked preview starts the new value with that character.
  const onMaskedKey = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key.length !== 1 || e.ctrlKey || e.metaKey || e.altKey) return
    e.preventDefault()
    onChange(e.key)
  }
  const onMaskedPaste = (e: React.ClipboardEvent<HTMLInputElement>) => {
    const text = e.clipboardData.getData('text')
    if (!text) return
    e.preventDefault()
    onChange(text)
  }

  return (
    <div className={cn('relative', className)}>
      <input
        ref={inputRef}
        id={id}
        type={masked || revealed ? 'text' : 'password'}
        value={masked ? maskValue(serverValue!) : value}
        readOnly={masked}
        onChange={masked ? undefined : e => onChange(e.target.value)}
        onKeyDown={masked ? onMaskedKey : undefined}
        onPaste={masked ? onMaskedPaste : undefined}
        placeholder={placeholder}
        autoFocus={autoFocus}
        autoComplete="off"
        spellCheck={false}
        aria-invalid={error ? true : undefined}
        aria-describedby={noteId && (error || hint) ? noteId : undefined}
        className={cn(FIELD_BASE, 'pl-3 pr-8', fieldBorder(error), FIELD_NO_NATIVE_PASSWORD_UI, masked && 'font-mono text-ink-caption')}
      />
      <button
        type="button"
        tabIndex={masked ? 0 : -1}
        onClick={() => { setRevealed(r => !r); inputRef.current?.focus() }}
        className={cn(eye, 'absolute right-0 top-1/2 -translate-y-1/2 h-9 w-9 flex items-center justify-center')}
        aria-label={masked ? labels.reveal : revealed ? labels.hide : labels.show}
        aria-pressed={revealed}
      >
        {revealed ? <EyeOff size={14} aria-hidden="true" /> : <Eye size={14} aria-hidden="true" />}
      </button>
      <FieldNote id={noteId} error={error} hint={hint} />
    </div>
  )
}
