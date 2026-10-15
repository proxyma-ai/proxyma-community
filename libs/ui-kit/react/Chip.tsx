import type { ReactNode } from 'react'
import { X } from 'lucide-react'
import { cn } from './cn'

export type ChipVariant = 'outline' | 'file' | 'solid' | 'filter'

/**
 * The classes of a chip, for a chip that cannot be a React element - the composer builds its
 * mention chips as DOM nodes inside a contentEditable, where React does not render.
 *
 * ONE SHAPE for every chip since the 2026-10 kit rebuild: `rounded-md px-1.5 py-0.5 text-xs`. The
 * app had four - a full pill (`rounded-control`, 9999px, whose own comments said "not a pill"), and
 * three `rounded-md` boxes at three paddings - and the same file was a pill before it was sent and
 * a box after. `rounded-md` is the radius the token rules give badges; a pill around a word reads
 * as something to click.
 */
export function chipClass(variant: ChipVariant = 'outline', opts: { pressed?: boolean; interactive?: boolean; fillClass?: string } = {}): string {
  return cn(
    'inline-flex items-center gap-1 max-w-full rounded-md px-1.5 py-0.5 text-xs leading-4 align-baseline whitespace-nowrap',
    variant === 'outline' && 'border border-line text-ink-secondary',
    variant === 'file' && 'border border-line/40 bg-canvas/50 text-ink-secondary',
    // A source type's or a skill's own fill, chosen by the caller (`fillClass`), with white on it.
    variant === 'solid' && cn('font-medium text-on-status', opts.fillClass ?? 'bg-brand-solid'),
    // A filter: the preset's brand-chip pair when pressed - "the flat pale chip pairing ... a
    // selected filter chip" - and an outline at rest.
    variant === 'filter' && (opts.pressed
      ? 'border border-brand-solid/40 bg-brand-chip-bg text-brand-chip-text font-medium'
      : 'border border-line text-ink-secondary'),
    opts.interactive && 'cursor-pointer transition-colors focus-ring',
    opts.interactive && variant !== 'solid' && !opts.pressed && 'mouse:hover:border-ink-disabled mouse:hover:text-ink-primary',
  )
}

interface ChipProps {
  label: ReactNode
  variant?: ChipVariant
  /** A 14px glyph before the label - the rule for marks in chips. */
  icon?: ReactNode
  /** For a `solid` chip: its fill class (a mode-invariant brand or source-type ramp step). */
  fillClass?: string
  /** For a `filter` chip. */
  pressed?: boolean
  /** A figure after the label (a filter's count), in the readout face. */
  count?: number
  onClick?: () => void
  /**
   * A chip that is a LINK - an attachment that opens or downloads (one this deployment serves; a
   * destination outside Proxyma is `ExternalLink`). Not with `onClick` or `onRemove`.
   */
  href?: string
  /** With `href`: `_blank` for a file that opens in a tab of its own. */
  target?: string
  /** Shows a remove X; `removeLabel` is its accessible name ("Remove report.pdf"). */
  onRemove?: () => void
  removeLabel?: string
  title?: string
  /** Not available now (a filter while its list loads) - dimmed, and its buttons refuse the press. */
  disabled?: boolean
  className?: string
}

/**
 * A small token for a thing named or attached - a file, a mention, a skill, a tag, a filter.
 * Not a status: a status is a `Badge`. A tag a user typed is a Chip, not an upper-case Badge.
 */
export function Chip({ label, variant = 'outline', icon, fillClass, pressed, count, onClick, href, target, onRemove, removeLabel, title, disabled, className }: ChipProps) {
  const body = (
    <>
      {icon && <span className="shrink-0 flex items-center [&>svg]:w-3.5 [&>svg]:h-3.5" aria-hidden="true">{icon}</span>}
      <span className="truncate">{label}</span>
      {/* The count in the chip's own ink, set apart by its figures face - it was faded to 80%, which
          took the chosen filter's count under 4.5:1 on its pale fill (2026-10-15). */}
      {count !== undefined && <span className="data-readout">{count}</span>}
    </>
  )
  const cls = cn(chipClass(variant, { pressed, interactive: (!!onClick || !!href) && !disabled, fillClass }), disabled && 'opacity-40 cursor-not-allowed', className)

  if (href && !disabled) {
    return (
      <a href={href} target={target} rel={target === '_blank' ? 'noopener noreferrer' : undefined} title={title} className={cn(cls, 'no-underline')}>
        {body}
      </a>
    )
  }

  if (onClick && !onRemove) {
    return (
      <button type="button" onClick={onClick} disabled={disabled} title={title} aria-pressed={variant === 'filter' ? !!pressed : undefined} className={cls}>
        {body}
      </button>
    )
  }
  return (
    <span title={title} className={cls}>
      {onClick ? <button type="button" onClick={onClick} disabled={disabled} className="inline-flex items-center gap-1 min-w-0 focus-ring rounded-sm">{body}</button> : body}
      {onRemove && (
        <button
          type="button"
          onClick={onRemove}
          disabled={disabled}
          aria-label={removeLabel}
          className="shrink-0 -mr-0.5 inline-flex items-center justify-center w-4 h-4 touch:w-6 touch:h-6 rounded-sm opacity-70 mouse:hover:opacity-100 mouse:hover:text-danger transition-colors focus-ring"
        >
          <X size={12} aria-hidden="true" />
        </button>
      )}
    </span>
  )
}
