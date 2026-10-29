'use client'

import type { ReactNode } from 'react'
import { useId } from 'react'
import { cn } from './cn'
import { fieldWords } from './field'
import { FIELD_BODY, FIELD_HEAD, FIELD_STACK, FieldHint, FieldNote } from './Input'
import { TEXT } from './typeScale'

/** What `Field` hands its render function to tie a control to its words. */
export interface FieldAria {
  'aria-describedby'?: string
  'aria-invalid'?: true
}

/**
 * A FIELD: its name, the words under the name, the control, and the error under the control - for
 * any control that does not carry its own label (`Select`, `Combobox`, `SecretInput`, a
 * `RadioGroup`, a slider, a row of swatches, a group of switches).
 *
 * The name is a real `<label htmlFor>` when the control has an id - pass `id`, or let the render
 * function receive a generated one.
 *
 * WHERE THE WORDS GO, since 2026-10-29 (the maintainer's review: "Description should be placed below
 * the section title, and have reasonable padding/margins"): `hint` sits directly UNDER THE NAME and
 * ABOVE the control - the name, 4px, the hint (13/18 caption ink, about 70 characters a line), 8px,
 * the control - so the explanation is read before the control it explains. It sat under the control
 * until then, jammed against a swatch row and pushed away from its name by a textarea. `error` stays
 * UNDER the control, which is what it points at, and the control is described by both. With no
 * `label` the hint stays under the control (`fieldWords`): words over a bare control read as its
 * title. `description` is the same place as the hint (the older name for it, from when only a
 * skill's instructions sat above the control); both may be given, description first.
 *
 * DO NOT ALSO hand the hint to the control inside (a `Combobox`'s or `SecretInput`'s own `hint`):
 * those draw it under themselves, so the field would carry its words in two places.
 *
 * Hints were once in six positions across Settings, two of them pulled up with negative margins
 * under the wrong field; this is the one place.
 *
 * `aside` is a small readout at the label's right (a character count, "optional").
 */
export function Field({ label, id, description, hint, error, aside, layout = 'stacked', children, className }: {
  label?: ReactNode
  id?: string
  /**
   * `inline` puts the name BESIDE a short control (a time, a count) from `sm` - the backup
   * schedule's rows: the name and its hint in the left column, the control at the right, the error
   * under the row. Below `sm` it stacks as `stacked` does. `stacked` is everything else.
   */
  layout?: 'stacked' | 'inline'
  /** Under the name, above the control - the same place as `hint` (see above). */
  description?: ReactNode
  /** Under the name, above the control; under the control when there is no `label`. */
  hint?: ReactNode
  error?: string
  aside?: ReactNode
  /**
   * The control, or a function of the field's id that draws it. The function's second argument is
   * what ties the control to its words - spread it on the control: `aria-describedby` naming the
   * hint and the error, and `aria-invalid` while there is an error.
   */
  children: ReactNode | ((id: string, aria: FieldAria) => ReactNode)
  className?: string
}) {
  const generated = useId()
  const fieldId = id ?? generated
  const words = fieldWords(fieldId, { labelled: !!label, hint: !!hint, error: !!error, description: !!description })
  // With no label the description still opens the field (what to type before typing it) and the
  // hint goes under the control.
  const shownHint = words.hintAbove ? hint : null
  const above = words.above
    ? <>{description}{description && shownHint ? <div className="mt-1">{shownHint}</div> : shownHint}</>
    : null
  const below = words.hintAbove ? undefined : hint
  const aria: FieldAria = {
    'aria-describedby': words.describedBy,
    'aria-invalid': error ? true : undefined,
  }
  const control = typeof children === 'function' ? children(fieldId, aria) : children
  if (layout === 'inline') {
    return (
      <div className={cn('min-w-0', className)}>
        <div className="grid grid-cols-1 gap-y-2 sm:grid-cols-[minmax(0,1fr)_auto] sm:items-center sm:gap-x-4">
          {(label || above) && (
            <div className="min-w-0">
              {label && <label htmlFor={fieldId} className={cn(TEXT.label, 'block')}>{label}</label>}
              {above && <FieldHint id={words.hintId} className={cn(label && 'mt-1', 'mb-0')}>{above}</FieldHint>}
            </div>
          )}
          <div className="min-w-0 flex items-center gap-2">
            {control}
            {aside && <span className="text-xs text-ink-caption data-readout">{aside}</span>}
          </div>
        </div>
        <FieldNote id={words.noteId} error={error} hint={below} />
      </div>
    )
  }
  const head = !!(label || aside || above)
  return (
    <div className={cn('flex flex-col min-w-0', head && FIELD_STACK, className)}>
      {head && (
        <div className={FIELD_HEAD}>
          {(label || aside) && (
            <div className={cn('flex items-baseline justify-between gap-3', above ? 'mb-1' : 'mb-2')}>
              {label && <label htmlFor={fieldId} className={TEXT.label}>{label}</label>}
              {aside && <span className="text-xs text-ink-caption data-readout">{aside}</span>}
            </div>
          )}
          {above && <FieldHint id={words.hintId}>{above}</FieldHint>}
        </div>
      )}
      <div className={FIELD_BODY}>
        {control}
        <FieldNote id={words.noteId} error={error} hint={below} />
      </div>
    </div>
  )
}

/**
 * Fields side by side that stack on a narrow screen. Two named densities: `fields` (`gap-4`, a
 * pair of related fields - host and port) and `columns` (`gap-x-8 gap-y-6`, the two or three
 * columns of a wide create/edit dialog). Six spellings of this grid existed.
 *
 * Its fields LINE UP THEIR CONTROLS, since 2026-10-29: a hint sits under its label now, so a field
 * with one and its neighbor without stood their controls at two heights. When every child is a
 * labelled kit field (`.field-stack`), each becomes a two-row subgrid (`.form-columns` in
 * `proxyma-primitives.css`) and every head in a row takes the tallest one's height. A row with
 * anything else in it is laid out as before.
 */
export function FormColumns({ children, cols = 2, ratio, from = 'sm', density = 'fields', className }: {
  children: ReactNode
  cols?: 2 | 3
  /**
   * Two columns of unequal weight - `3:2` gives the first three fifths (a connector's settings,
   * where the left column holds the long fields). Only with `cols={2}`.
   */
  ratio?: '3:2' | '2:3'
  from?: 'sm' | 'md' | 'lg'
  density?: 'fields' | 'columns'
  className?: string
}) {
  // Spelled out whole, so Tailwind's scanner finds every class.
  const grid = ratio === '3:2' ? {
    sm: 'sm:grid-cols-[3fr_2fr]', md: 'md:grid-cols-[3fr_2fr]', lg: 'lg:grid-cols-[3fr_2fr]',
  }[from] : ratio === '2:3' ? {
    sm: 'sm:grid-cols-[2fr_3fr]', md: 'md:grid-cols-[2fr_3fr]', lg: 'lg:grid-cols-[2fr_3fr]',
  }[from] : {
    sm: cols === 2 ? 'sm:grid-cols-2' : 'sm:grid-cols-3',
    md: cols === 2 ? 'md:grid-cols-2' : 'md:grid-cols-3',
    lg: cols === 2 ? 'lg:grid-cols-2' : 'lg:grid-cols-3',
  }[from]
  return (
    <div className={cn('form-columns grid grid-cols-1', grid, density === 'fields' ? 'gap-4' : 'gap-x-8 gap-y-6', className)}>
      {children}
    </div>
  )
}

/** A vertical stack of fields - `space-y-4`, the spacing table's form rhythm. */
export function FormStack({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cn('space-y-4', className)}>{children}</div>
}

/**
 * A VALUE THAT CANNOT BE CHANGED HERE, where a control would be - a connection's service once it is
 * saved: at the field's height and the option size, in primary ink, with its mark. Not a disabled
 * control: a greyed select reads as "not yet", and this is "not ever".
 */
export function StaticValue({ icon, children, className }: { icon?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <p className={cn(TEXT.option, 'flex items-center gap-2 py-2 text-ink-primary', className)}>
      {icon}
      {children}
    </p>
  )
}
