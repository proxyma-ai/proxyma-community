'use client'

import { forwardRef, type ComponentType, type ReactNode, type Ref } from 'react'
import { ChevronDown } from 'lucide-react'
import { cn } from './cn'
import { iconButton, type IconButtonSize, type IconButtonTone } from './iconButton'
import { Spinner } from './Spinner'
import { Tooltip } from './Tooltip'

type Glyph = ComponentType<{ size?: number | string; className?: string }>

export interface IconButtonProps extends Omit<React.ButtonHTMLAttributes<HTMLButtonElement>, 'children'> {
  /** The glyph, a lucide icon or a kit mark - drawn at 16px (14px at `sm`). */
  icon: Glyph
  /** The accessible name - and the tooltip, unless `tooltip` is false or a different sentence. */
  label: string
  size?: IconButtonSize
  /** `danger` turns red under the pointer, for a control that removes something. */
  tone?: IconButtonTone
  /** `false` for no tooltip (a control whose name is already on screen); a string for a longer one. */
  tooltip?: boolean | string
  side?: 'top' | 'bottom'
  /** An action in flight: the Spinner in place of the glyph, `aria-busy`, and no second press. */
  busy?: boolean
}

/**
 * THE ICON-ONLY BUTTON, whole (2026-10-14): the `iconButton` recipe, the accessible name, the
 * tooltip that says it in words, and an `aria-hidden` glyph at the row's size - which every call
 * site assembled from a `Tooltip`, a `<button>` and `iconButton()`, about fifty times in four apps,
 * eight of them with no name at all. `label` is required, so an unnamed one cannot be written.
 * `iconButton()` stays for a control that is not a plain button (a motion button, a link).
 */
export const IconButton = forwardRef<HTMLButtonElement, IconButtonProps>(function IconButton(
  { icon: Icon, label, size = 'md', tone = 'default', tooltip = true, side = 'top', busy, disabled, className, type = 'button', ...props },
  ref,
) {
  const glyph = size === 'sm' ? 14 : 16
  const button = (
    <button
      {...props}
      ref={ref}
      type={type}
      aria-label={label}
      aria-busy={busy || undefined}
      disabled={disabled || busy}
      className={cn(iconButton(size, tone), className)}
    >
      {busy ? <Spinner size={glyph} className="text-inherit" /> : <Icon size={glyph} aria-hidden="true" />}
    </button>
  )
  if (tooltip === false) return button
  return <Tooltip label={typeof tooltip === 'string' ? tooltip : label} side={side}>{button}</Tooltip>
})

/**
 * AN ICON BUTTON THAT IS ON OR OFF - a graph's labels, a view's pause. The `iconButton` recipe in
 * caption ink while off and the brand ink while on (the colour of a control's own state, by the
 * icon-button rule), `aria-pressed`, and named by its tooltip, since a glyph alone names nothing.
 * The graph's options drew this by hand until the kit had it.
 */
export function IconToggleButton({ icon: Icon, label, tooltip, pressed, onToggle, filled, size = 'md', side = 'top', disabled, className }: {
  icon: Glyph
  /** The accessible name - and the tooltip, unless `tooltip` says it at more length. */
  label: string
  /** A longer sentence for the tooltip ("Good answer - tell us what worked"). */
  tooltip?: string
  pressed: boolean
  onToggle: () => void
  /** The glyph FILLED while on (a thumb given, a pin set) - as well as in the brand's ink. */
  filled?: boolean
  size?: IconButtonSize
  side?: 'top' | 'bottom'
  disabled?: boolean
  className?: string
}) {
  return (
    <Tooltip label={tooltip ?? label} side={side}>
      <button
        type="button"
        onClick={onToggle}
        aria-label={label}
        aria-pressed={pressed}
        disabled={disabled}
        className={cn(pressed ? cn(iconButton(size, null), 'text-brand-legible') : iconButton(size), className)}
      >
        <Icon size={size === 'sm' ? 14 : 16} aria-hidden="true" className={cn(filled && pressed && 'fill-current')} />
      </button>
    </Tooltip>
  )
}

/**
 * AN ICON BUTTON THAT CAN CARRY A COUNT. The box grows to fit the words instead of the caller
 * stretching `iconButton` with `w-auto`; the glyph can carry its own motion class.
 *
 * `working` BLINKS THE GLYPH in the button's own ink - the chat's agents button while sub-agents
 * work (maintainer's request, 2026-10-14: "blinking the icon, keep the white color"). An icon alone
 * blinks; words wave (working.ts). It stays still under reduced motion.
 */
export function IconCountButton({ icon: Icon, label, count, active, working, onClick, size = 'md', iconClassName, className }: {
  icon: Glyph
  /** The accessible name and the tooltip. */
  label: string
  /** The words beside the glyph ("2 agents"); nothing shown when absent. */
  count?: ReactNode
  /** In the brand ink - something is happening. */
  active?: boolean
  /** The glyph blinks, at the product's one 1Hz rhythm, in the button's own ink. */
  working?: boolean
  onClick: () => void
  size?: IconButtonSize
  iconClassName?: string
  className?: string
}) {
  return (
    <Tooltip label={label}>
      <button
        type="button"
        onClick={onClick}
        aria-label={label}
        className={cn(
          iconButton(size, active ? null : 'default'),
          active && 'text-brand-legible',
          count != null && count !== '' && 'w-auto touch:w-auto px-1.5 gap-1.5',
          className,
        )}
      >
        <Icon size={16} className={cn('shrink-0', working && 'animate-agent-working', iconClassName)} aria-hidden="true" />
        {count != null && count !== '' && <span className="text-xs whitespace-nowrap data-readout">{count}</span>}
      </button>
    </Tooltip>
  )
}

/**
 * THE TRIGGER OF A SMALL CHOICE MENU IN A TOOLBAR - its glyph, the value chosen, a chevron: the
 * composer's permission mode. Caption ink, primary under a mouse; `tone="warning"` for a value
 * that deserves a second look (Auto). Pass the `ref` and `aria-expanded` a `Popover` hands its
 * trigger.
 */
export function MenuTrigger({ icon: Icon, value, ariaLabel, open, onClick, tone = 'default', triggerRef, className }: {
  icon?: Glyph
  value: ReactNode
  ariaLabel: string
  open: boolean
  onClick: () => void
  tone?: 'default' | 'warning'
  triggerRef?: Ref<HTMLButtonElement>
  className?: string
}) {
  return (
    <button
      ref={triggerRef}
      type="button"
      onClick={onClick}
      aria-haspopup="menu"
      aria-expanded={open}
      aria-label={ariaLabel}
      className={cn(
        'shrink-0 inline-flex items-center gap-1 rounded-md px-1.5 py-1 text-xs font-ui transition-colors focus-ring',
        tone === 'warning' ? 'text-warning' : 'text-ink-caption mouse:hover:text-ink-secondary',
        className,
      )}
    >
      {Icon && <Icon size={13} className="shrink-0" aria-hidden="true" />}
      <span>{value}</span>
      <ChevronDown size={12} className="shrink-0" aria-hidden="true" />
    </button>
  )
}
