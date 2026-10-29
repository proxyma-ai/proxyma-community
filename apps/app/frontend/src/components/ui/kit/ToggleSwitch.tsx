/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/ToggleSwitch.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import { cn } from './cn'

interface Props {
  checked: boolean
  /** Omit for a switch drawn as a picture (a landing-site replica) - it then cannot be pressed. */
  onChange?: (v: boolean) => void
  disabled?: boolean
  /** Its accessible name, when no visible label names it - a bare switch was unnamed in three places. */
  ariaLabel?: string
  /** `sm` for a dense row; `md` is the default 24px-tall switch. */
  size?: 'sm' | 'md'
  id?: string
  className?: string
}

/** A toggle is genuinely pill-shaped, so this is one of the few legitimate `rounded-full` uses. */
export function ToggleSwitch({ checked, onChange, disabled, ariaLabel, size = 'md', id, className }: Props) {
  const small = size === 'sm'
  return (
    <button
      type="button"
      role="switch"
      id={id}
      aria-checked={checked}
      aria-label={ariaLabel}
      disabled={disabled}
      tabIndex={onChange ? undefined : -1}
      onClick={onChange ? () => onChange(!checked) : undefined}
      className={cn(
        'relative inline-flex shrink-0 rounded-full border-2 border-transparent',
        'transition-colors duration-base focus-ring',
        small ? 'h-4 w-7' : 'h-6 w-11',
        onChange ? 'cursor-pointer' : 'cursor-default',
        'disabled:opacity-50 disabled:cursor-not-allowed',
        // Off is the disabled ink, not the hairline: a switch is a control, and its track has to hold
        // 3:1 against the page (the hairline is 1.3:1) for the knob to read as a position.
        checked ? 'bg-brand-solid' : 'bg-ink-disabled',
        // The small switch is 28x16 - under the 24px floor. Its TARGET is 44px all round (an
        // invisible box), while the drawing keeps its size.
        small && 'before:absolute before:-inset-x-2 before:-inset-y-3.5',
        className,
      )}
    >
      <span
        className={cn(
          'pointer-events-none inline-block rounded-full bg-thumb transition-transform duration-base ease-out',
          small ? 'h-3 w-3' : 'h-5 w-5',
          checked ? (small ? 'translate-x-3' : 'translate-x-5') : 'translate-x-0',
        )}
      />
    </button>
  )
}
