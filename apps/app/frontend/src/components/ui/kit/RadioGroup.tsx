/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/RadioGroup.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import { useId, type ReactNode } from 'react'
import { Check, Minus } from 'lucide-react'
import { cn } from './cn'
import { TEXT } from './typeScale'

/**
 * The mark of a choice: a ring with a dot for one-of (`multi` false), a square with a tick for
 * several. The SHAPE is the only thing telling a radio from a checkbox at a glance, which is why
 * the square is `rounded` (4px) and not the 6px every other box takes - at 16px, 6px reads round.
 * The kit `Checkbox` and the question cards in chat draw their box from here.
 */
export function ChoiceMark({ checked, multi = false, indeterminate = false, className }: {
  checked: boolean
  multi?: boolean
  /** A box for "some of these" - a list's select-all while part of it is chosen. `multi` only. */
  indeterminate?: boolean
  className?: string
}) {
  if (multi) {
    return (
      <span
        aria-hidden="true"
        className={cn(
          'w-4 h-4 shrink-0 rounded-sm border flex items-center justify-center transition-all',
          checked || indeterminate ? 'bg-brand-solid border-brand-solid' : 'border-line bg-transparent group-hover:border-ink-disabled',
          className,
        )}
      >
        {checked
          ? <Check size={12} strokeWidth={3} className="text-brand-on-solid" />
          : indeterminate && <Minus size={12} strokeWidth={3} className="text-brand-on-solid" />}
      </span>
    )
  }
  return (
    <span
      aria-hidden="true"
      className={cn(
        'w-4 h-4 shrink-0 rounded-full border-2 flex items-center justify-center transition-all',
        checked ? 'border-brand-solid' : 'border-line group-hover:border-ink-disabled',
        className,
      )}
    >
      {checked && <span className="w-2 h-2 rounded-full bg-brand-solid" />}
    </span>
  )
}

export interface RadioOption<T extends string = string> {
  value: T
  label: ReactNode
  /** A caption directly under the label, 4px below it (the field rule of 2026-10-29). */
  hint?: ReactNode
  disabled?: boolean
}

/**
 * Pick one of a few, as a list of labelled rings - the Settings shape (it was Settings-only; the
 * labels now come in already translated). Options are 13/400. `figures` draws the labels in the
 * readout face, for a numeric set (8k, 32k, 128k). For a compact inline choice use
 * `SegmentedControl`.
 */
export function RadioGroup<T extends string>({ value, onChange, options, horizontal, figures, name, ariaLabel, disabled }: {
  value: T
  onChange: (value: T) => void
  options: readonly RadioOption<T>[]
  horizontal?: boolean
  figures?: boolean
  name?: string
  ariaLabel?: string
  disabled?: boolean
}) {
  // A name of its own when none is given: radios without one are each their own group, so the
  // arrow keys did not move between the options.
  const generated = useId()
  const groupName = name ?? generated
  return (
    <div
      role="radiogroup"
      aria-label={ariaLabel}
      // On glass every option is a 44px target already, so the rows touch rather than adding 8px
      // over that, and a one-line option's words sit in the middle of its target - top-aligned they
      // left 32px of air under every row of a wrapped group (the UI review's phone pass, 2026-10-28).
      className={cn(horizontal ? 'flex flex-wrap gap-x-4 gap-y-2 touch:gap-y-0' : 'space-y-2 touch:space-y-0', 'animate-fade-in', disabled && 'opacity-50')}
    >
      {options.map(o => {
        const off = disabled || o.disabled
        return (
          <label key={o.value} className={cn('relative flex items-start gap-2 group touch:min-h-tap', !o.hint && 'touch:items-center', off ? 'cursor-not-allowed' : 'cursor-pointer')}>
            <input
              type="radio"
              name={groupName}
              value={o.value}
              checked={value === o.value}
              disabled={off}
              onChange={() => onChange(o.value)}
              className="sr-only peer"
            />
            <ChoiceMark
              checked={value === o.value}
              className={cn('mt-0.5 peer-focus-visible:ring-2 peer-focus-visible:ring-brand-solid peer-focus-visible:ring-offset-2 peer-focus-visible:ring-offset-canvas', !o.hint && 'touch:mt-0')}
            />
            <span className="min-w-0">
              <span className={cn(TEXT.option, 'block', figures && 'data-readout')}>{o.label}</span>
              {o.hint && <span className={cn(TEXT.caption, 'block mt-1 max-w-[60ch]')}>{o.hint}</span>}
            </span>
          </label>
        )
      })}
    </div>
  )
}
