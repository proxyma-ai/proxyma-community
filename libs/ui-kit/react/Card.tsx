import type { ReactNode } from 'react'
import { cn } from './cn'
import { TEXT } from './typeScale'

type CardVariant = 'subtle' | 'outline' | 'solid'

/**
 * The classes of a card, for an element that is not a `div` (a link card, a button card).
 *
 * THE SPACING TABLE'S CARD since the 2026-10 kit rebuild: `bg-surface/40 border border-line/40
 * rounded-lg`, padding `px-4 sm:px-5 py-4`, no shadow. The kit Card was `bg-surface shadow-card
 * px-6` and nothing in the app used it - the app wrote its own (`CARD` in Administration, ~25 class
 * strings with six fills and four borders) and the landing site wrote two more because the kit one
 * had a shadow. Now:
 *
 * - `subtle` (default) - the app's card and panel.
 * - `outline` - border only, for the marketing site's feature and plan cards on the canvas.
 * - `solid` - an opaque `bg-surface`, for a card that floats over a picture (a scene, a canvas).
 *
 * `tone="danger"` is a danger zone; `highlighted` promotes one card in a row with a brand BORDER
 * (never a second fill, never a glow). `interactive` gives a card that opens something the
 * focus-border response and a pointer.
 */
export type CardState = 'proposed' | 'agent' | 'confirmed'

export function cardClass({ variant = 'subtle', tone = 'default', highlighted, interactive, padding = 'md', state, className }: {
  variant?: CardVariant
  tone?: 'default' | 'danger'
  highlighted?: boolean
  /**
   * WHERE A RECORD STANDS, for a card that holds one someone has to look at (a key account's
   * rating, move or report): `proposed` a dashed line - not confirmed yet; `agent` a dashed brand
   * line and a faint brand tint - the agent proposed it; `confirmed` a line in the success ink. Five
   * key-account tabs drew these three by hand, five ways.
   */
  state?: CardState
  interactive?: boolean
  padding?: 'none' | 'sm' | 'md' | 'lg'
  className?: string
} = {}): string {
  return cn(
    'rounded-lg border min-w-0',
    variant === 'subtle' && 'bg-surface/40',
    variant === 'solid' && 'bg-surface',
    variant === 'outline' && 'bg-transparent',
    tone === 'danger' ? 'border-danger/30 bg-danger/5' : highlighted ? 'border-brand-solid/60' : 'border-line/40',
    variant === 'outline' && tone !== 'danger' && !highlighted && 'border-line',
    state === 'proposed' && 'border-dashed border-line',
    state === 'agent' && 'border-dashed border-brand-solid/50 bg-brand-solid/[0.04]',
    state === 'confirmed' && 'border-success/40',
    interactive && 'focus-border cursor-pointer transition-colors',
    padding === 'sm' && 'px-3 py-2.5',
    padding === 'md' && 'px-4 sm:px-5 py-4',
    padding === 'lg' && 'p-5 sm:p-6',
    className,
  )
}

type Props = React.HTMLAttributes<HTMLDivElement> & {
  children: React.ReactNode
  variant?: CardVariant
  tone?: 'default' | 'danger'
  highlighted?: boolean
  interactive?: boolean
  /** `none` for a card whose content runs edge to edge (a table pads its own cells). */
  padding?: 'none' | 'sm' | 'md' | 'lg'
  state?: CardState
}

export function Card({ children, className, variant, tone, highlighted, interactive, padding, state, ...rest }: Props) {
  return (
    <div className={cardClass({ variant, tone, highlighted, interactive, padding, state, className })} {...rest}>
      {children}
    </div>
  )
}

/**
 * The line at the top of a card: what it shows (an eyebrow, sentence case), a readout at the right
 * of it (a total, a period), and actions. `divided` draws a rule under it and pads it like a
 * dialog header - for a card whose body is a table.
 */
export function CardHeader({ title, readout, actions, divided, className }: {
  title: ReactNode
  readout?: ReactNode
  actions?: ReactNode
  divided?: boolean
  className?: string
}) {
  return (
    <div className={cn('flex items-center justify-between gap-3 min-h-5', divided ? 'px-4 sm:px-5 py-3 border-b border-line/40' : 'mb-3', className)}>
      <p className={cn(divided ? TEXT.label : TEXT.eyebrow, 'min-w-0 truncate')}>{title}</p>
      {(readout || actions) && (
        <div className="shrink-0 flex items-center gap-3">
          {readout && <span className="text-xs text-ink-caption data-readout">{readout}</span>}
          {actions}
        </div>
      )}
    </div>
  )
}

/** A card's padded body, for a card with `padding="none"` and a divided header. */
export function CardBody({ children, className }: { children: React.ReactNode; className?: string }) {
  return <div className={cn('px-4 sm:px-5 py-4', className)}>{children}</div>
}

/**
 * A card holding ONE chart or ranked list: its eyebrow, a readout at the right of it, and a body
 * that FILLS the card's height - so cards side by side in a grid draw their plots to the same
 * bottom edge. A chart library's responsive container goes inside `ChartCard`'s plot box, which
 * it can measure (`absolute inset-0` in a growing body).
 *
 * CONTENT WITH A HEIGHT OF ITS OWN - a `BarList`, rows of anything - is `flow`. In the absolute
 * plot box it takes no room, so the card stays the plot box's minimum and the rows past it are
 * drawn over the card's border and whatever is below (the usage page's "Features and tools used",
 * nine rows in a box of six). In the flow the card grows to the list, and a grid row stretches the
 * charts beside it to the same height, so their plots still share one bottom edge. The body keeps
 * its minimum either way, so a loading skeleton (`absolute inset-0`) or an empty sentence holds
 * the same height as the chart it stands in for.
 */
export function ChartCard({ title, readout, actions, children, flow = false, className }: {
  title: ReactNode
  readout?: ReactNode
  actions?: ReactNode
  children: ReactNode
  /** The content sets the height (a list) rather than filling the box (a chart). */
  flow?: boolean
  className?: string
}) {
  return (
    <div className={cardClass({ className: cn('flex flex-col', className) })}>
      <CardHeader title={title} readout={readout} actions={actions} />
      {flow ? (
        <div className="relative flex-1 min-h-44 min-w-0">{children}</div>
      ) : (
        <div className="relative flex-1 min-h-44">
          <div className="absolute inset-0">{children}</div>
        </div>
      )}
    </div>
  )
}
