import type { ReactNode } from 'react'
import { ChoiceMark } from './RadioGroup'
import { cn } from './cn'
import { TEXT } from './typeScale'

interface Props {
  checked: boolean
  onChange: (v: boolean) => void
  disabled?: boolean
  /** Label content. Clicking it toggles the box, because the whole thing is one <label>. */
  children?: ReactNode
  /** A caption under the label, aligned with the label's text rather than the box. */
  hint?: ReactNode
  /** Draws the eye to a box that must be ticked before going on (a consent). */
  shake?: boolean
  /** The accessible name of a box with no visible label (a table row's). */
  ariaLabel?: string
  /** The id of the line that explains it - an error under a consent ("You must accept the terms"). */
  describedBy?: string
  /** Marks the box as the reason a form cannot go on; pair it with `describedBy`. */
  invalid?: boolean
  className?: string
}

/**
 * A real checkbox: a visually-hidden native input inside a label, with the kit's square
 * `ChoiceMark` beside it.
 *
 * The native input is what does the work - keyboard focus, space to toggle, the accessibility tree,
 * and form semantics all come free. Painting a <span> and wiring onClick to it, which is what every
 * hand-rolled copy of this ends up being, gets none of those and looks identical until someone
 * tries to reach it without a mouse. Three native boxes with `accent-*` colours sat beside this
 * one in the same dialogs; they are this one now.
 *
 * The label is 13/400 - the type scale's `option` - so a choice reads the same size wherever it is
 * offered, beside a radio or alone.
 */
export function Checkbox({ checked, onChange, disabled, children, hint, shake, ariaLabel, describedBy, invalid, className }: Props) {
  return (
    <label
      className={cn(
        'inline-flex items-start gap-2.5 group',
        disabled ? 'cursor-not-allowed opacity-50' : 'cursor-pointer',
        shake && 'animate-shake',
        className,
      )}
    >
      <input
        type="checkbox"
        checked={checked}
        disabled={disabled}
        aria-label={ariaLabel}
        aria-describedby={describedBy}
        aria-invalid={invalid || undefined}
        onChange={e => onChange(e.target.checked)}
        className="sr-only peer"
      />
      <ChoiceMark
        checked={checked}
        multi
        className="mt-0.5 peer-focus-visible:ring-2 peer-focus-visible:ring-brand-solid peer-focus-visible:ring-offset-2 peer-focus-visible:ring-offset-canvas"
      />
      {(children != null || hint != null) && (
        <span className="min-w-0">
          {children != null && <span className={cn('block', TEXT.option)}>{children}</span>}
          {hint != null && <span className="block mt-0.5 text-xs leading-relaxed text-ink-caption">{hint}</span>}
        </span>
      )}
    </label>
  )
}
