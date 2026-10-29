'use client'

import { useId, type ReactNode } from 'react'
import { KeyRound } from 'lucide-react'
import { Callout } from './Callout'
import { cn } from './cn'
import { CopyButton } from './CopyButton'
import { FIELD_BASE, fieldBorder, fieldWords } from './field'
import { FIELD_BODY, FIELD_HEAD, FIELD_STACK, FieldHint, FieldNote } from './Input'
import { TEXT } from './typeScale'

/**
 * A VALUE TO COPY, SHOWN AS A FIELD - a deployment code, an endpoint, a key's id: read-only, in the
 * field's box (a hole in the surface), selectable, with the copy button at its right end. A label
 * above names it. `mono` for something a person pastes into a terminal or a config file (a token,
 * a URL); a figure (a code made of digits) takes the readout face by itself.
 *
 * Six places put a value beside a CopyButton by hand - a ListRow with a Readout, a CodeBlock with a
 * label over it, a span stopping a row's click - each laid out differently.
 */
export function CopyField({ value, label, copyLabel, copiedLabel, mono = true, hint, id, className }: {
  value: string
  /** Its name above the field ("Ingestion token"), translated. */
  label?: ReactNode
  /** The copy button's name ("Copy token") and its word once copied ("Copied"), translated. */
  copyLabel: string
  copiedLabel: string
  mono?: boolean
  /**
   * What the value is for - under the label, above the field; under the field when there is no
   * label (`fieldWords`, since 2026-10-29).
   */
  hint?: ReactNode
  id?: string
  className?: string
}) {
  const generated = useId()
  const fieldId = id ?? generated
  const words = fieldWords(fieldId, { labelled: !!label, hint: !!hint, error: false })
  return (
    <div className={cn('flex flex-col min-w-0', label && FIELD_STACK, className)}>
      {label && (
        <div className={FIELD_HEAD}>
          <label htmlFor={fieldId} className={cn(TEXT.label, 'block', words.hintAbove ? 'mb-1' : 'mb-2')}>{label}</label>
          {words.hintAbove && <FieldHint id={words.hintId}>{hint}</FieldHint>}
        </div>
      )}
      <div className={FIELD_BODY}>
        <div className="relative">
          <input
            id={fieldId}
            readOnly
            value={value}
            onFocus={e => e.currentTarget.select()}
            aria-describedby={words.describedBy}
            spellCheck={false}
            className={cn(FIELD_BASE, fieldBorder(undefined), 'pl-3 pr-10 text-ink-primary', mono && 'font-mono')}
          />
          <span className="absolute right-1 top-1/2 -translate-y-1/2 flex items-center">
            <CopyButton getText={() => value} label={copyLabel} copiedLabel={copiedLabel} />
          </span>
        </div>
        <FieldNote id={words.noteId} hint={words.hintAbove ? undefined : hint} />
      </div>
    </div>
  )
}

/**
 * A SECRET SHOWN ONCE - a new API token, an ingestion token, an issued license key, a trial key:
 * the warning that this is the only time it is shown, the value in a `CopyField`, and what to do
 * with it. A `Callout` in the warning tone, because losing it means making another. The parent
 * decides when it is gone (a dialog's "Done", a page leaving); nothing here hides it.
 *
 * Three were built by hand - Settings' API token, the license console's issued key, the site's
 * trial key - each with its own words and box.
 */
export function OneTimeSecret({ title, children, value, label, copyLabel, copiedLabel, actions, className }: {
  /** "Copy this token now", translated. */
  title: ReactNode
  /** Why: "It is shown once. Store it somewhere safe - you cannot see it again." */
  children?: ReactNode
  value: string
  /** The value's name over the field. */
  label?: ReactNode
  copyLabel: string
  copiedLabel: string
  /** Beside the copy - a download of the key as a file, say. */
  actions?: ReactNode
  className?: string
}) {
  return (
    <Callout tone="warning" icon={KeyRound} title={title} role="status" className={className}>
      {children && <div className="mb-3">{children}</div>}
      <CopyField value={value} label={label} copyLabel={copyLabel} copiedLabel={copiedLabel} />
      {actions && <div className="mt-3 flex flex-wrap items-center gap-3">{actions}</div>}
    </Callout>
  )
}
