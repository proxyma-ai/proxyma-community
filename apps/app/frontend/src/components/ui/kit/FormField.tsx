/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/FormField.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import type { ReactNode } from 'react'
import { useId } from 'react'
import { cn } from './cn'
import { FieldNote } from './Input'
import { TEXT } from './typeScale'

/** What `Field` hands its render function to tie a control to the line under it. */
export interface FieldAria {
  'aria-describedby'?: string
  'aria-invalid'?: true
}

/**
 * A FIELD: its name, the control, and the line under it - for any control that does not carry its
 * own label (`Select`, `Combobox`, `SecretInput`, a `RadioGroup`, a slider, a group of switches).
 *
 * The name is a real `<label htmlFor>` when the control has an id - pass `id`, or let the render
 * function receive a generated one. `description` sits ABOVE the control only where it explains
 * what to type before typing it (a skill's instructions); `hint` and `error` sit BELOW, 13px,
 * `mt-1.5`. Hints were in six positions across Settings, two of them pulled up with negative
 * margins under the wrong field.
 *
 * `aside` is a small readout at the label's right (a character count, "optional").
 */
export function Field({ label, id, description, hint, error, aside, layout = 'stacked', children, className }: {
  label?: ReactNode
  id?: string
  /**
   * `inline` puts the name BESIDE a short control (a time, a count) from `sm`, the hint still
   * under both - the backup schedule's rows. `stacked` is everything else.
   */
  layout?: 'stacked' | 'inline'
  description?: ReactNode
  hint?: ReactNode
  error?: string
  aside?: ReactNode
  /**
   * The control, or a function of the field's id that draws it. The function's second argument is
   * what ties the control to the line under it - spread it on the control: `aria-describedby`
   * naming the hint or error, and `aria-invalid` while there is an error.
   */
  children: ReactNode | ((id: string, aria: FieldAria) => ReactNode)
  className?: string
}) {
  const generated = useId()
  const fieldId = id ?? generated
  const noteId = `${fieldId}-note`
  const aria: FieldAria = {
    'aria-describedby': error || hint ? noteId : undefined,
    'aria-invalid': error ? true : undefined,
  }
  if (layout === 'inline') {
    return (
      <div className={cn('min-w-0', className)}>
        <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between sm:gap-4">
          {label && <label htmlFor={fieldId} className={cn(TEXT.label, 'min-w-0')}>{label}</label>}
          <div className="min-w-0 flex items-center gap-2">
            {typeof children === 'function' ? children(fieldId, aria) : children}
            {aside && <span className="text-xs text-ink-caption data-readout">{aside}</span>}
          </div>
        </div>
        {description && <div className={cn(TEXT.caption, 'mt-1.5')}>{description}</div>}
        <FieldNote id={noteId} error={error} hint={hint} />
      </div>
    )
  }
  return (
    <div className={cn('flex flex-col min-w-0', className)}>
      {(label || aside) && (
        <div className="flex items-baseline justify-between gap-3 mb-2">
          {label && <label htmlFor={fieldId} className={TEXT.label}>{label}</label>}
          {aside && <span className="text-xs text-ink-caption data-readout">{aside}</span>}
        </div>
      )}
      {description && <div className={cn(TEXT.caption, 'mb-2')}>{description}</div>}
      {typeof children === 'function' ? children(fieldId, aria) : children}
      <FieldNote id={noteId} error={error} hint={hint} />
    </div>
  )
}

/**
 * Fields side by side that stack on a narrow screen. Two named densities: `fields` (`gap-4`, a
 * pair of related fields - host and port) and `columns` (`gap-x-8 gap-y-5`, the two or three
 * columns of a wide create/edit dialog). Six spellings of this grid existed.
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
    <div className={cn('grid grid-cols-1', grid, density === 'fields' ? 'gap-4' : 'gap-x-8 gap-y-5', className)}>
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
    <p className={cn(TEXT.option, 'flex items-center gap-2 py-1.5 text-ink-primary', className)}>
      {icon}
      {children}
    </p>
  )
}
