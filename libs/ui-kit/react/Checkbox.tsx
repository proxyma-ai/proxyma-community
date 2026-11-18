'use client'

import { useEffect, useRef, type ReactNode } from 'react'
import { ChoiceMark } from './RadioGroup'
import { cn } from './cn'
import { TEXT } from './typeScale'

interface Props {
  checked: boolean
  /** Some of what it stands for is chosen, not all: a dash, and `aria-checked="mixed"` (CheckAll). */
  indeterminate?: boolean
  onChange: (v: boolean) => void
  disabled?: boolean
  /** Label content. Clicking it toggles the box, because the whole thing is one <label>. */
  children?: ReactNode
  /**
   * A caption directly under the label, 4px below it and aligned with its text rather than the box
   * - the field rule of 2026-10-29: a description sits under the words it explains.
   */
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
export function Checkbox({ checked, indeterminate = false, onChange, disabled, children, hint, shake, ariaLabel, describedBy, invalid, className }: Props) {
  const input = useRef<HTMLInputElement>(null)
  // The native box's own third state, so a screen reader hears "mixed".
  useEffect(() => { if (input.current) input.current.indeterminate = indeterminate && !checked }, [indeterminate, checked])
  return (
    <label
      className={cn(
        // relative: the hidden native input is absolute, and is placed in THIS box. Without it the
        // input was placed in the nearest positioned ancestor - a dialog's panel - so it escaped a
        // scrolling list's clip and made the whole dialog scroll (2026-10-30, the skill dialog).
        'relative inline-flex items-start gap-2 group touch:min-h-tap',
        disabled ? 'cursor-not-allowed opacity-50' : 'cursor-pointer',
        shake && 'animate-shake',
        className,
      )}
    >
      <input
        ref={input}
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
        indeterminate={indeterminate && !checked}
        multi
        className="mt-0.5 peer-focus-visible:ring-2 peer-focus-visible:ring-brand-solid peer-focus-visible:ring-offset-2 peer-focus-visible:ring-offset-canvas"
      />
      {(children != null || hint != null) && (
        <span className="min-w-0">
          {children != null && <span className={cn('block', TEXT.option)}>{children}</span>}
          {hint != null && <span className={cn('block mt-1 max-w-[60ch]', TEXT.caption)}>{hint}</span>}
        </span>
      )}
    </label>
  )
}

/**
 * "ALL" OVER A CHECKLIST, AS A CHECKBOX (the maintainer, 2026-11-17: "'All' should be a check box. When check 'All', all
 * other boxes are auto check. Uncheck 'All', all other boxes auto uncheck. If all are checked, uncheck any item uncheckes
 * the 'All'. This applies for every screens."). Checked while every item is, a dash while some are, empty while none;
 * ticking it chooses every item and clearing it none. It reads the items' state - nothing of its own - so unchecking any
 * one item clears it by itself. It replaced the "All" and "None" links over the export's choices and the file types.
 */
export function CheckAll<K>({ items, chosen, onChange, label, disabled, className }: {
  /** Every item it stands for. */
  items: readonly K[]
  /** The ones chosen now. */
  chosen: readonly K[]
  /** The new choice: every item, or none. */
  onChange: (next: K[]) => void
  label: ReactNode
  disabled?: boolean
  className?: string
}) {
  const count = items.filter(i => chosen.includes(i)).length
  const all = items.length > 0 && count === items.length
  return (
    <Checkbox
      checked={all}
      indeterminate={count > 0 && !all}
      onChange={() => onChange(all ? [] : [...items])}
      disabled={disabled || items.length === 0}
      className={className}
    >
      {label}
    </Checkbox>
  )
}
