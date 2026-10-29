/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/Card.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import type { ReactNode } from 'react'
import { cn } from './cn'
import { TEXT } from './typeScale'

type CardVariant = 'subtle' | 'outline' | 'solid'

/**
 * The classes of a card, for an element that is not a `div` (a link card, a button card).
 *
 * THE SPACING TABLE'S CARD since the 2026-10 kit rebuild, padding `px-4 sm:px-6 py-4`. Since
 * 2026-10-29 (the depth review) a card is a SHEET on the page: the card plane (`bg-surface`, white
 * in Light), a full-strength `line` border and `shadow-card` - and since the glass, the same day, in
 * an app shell a pane of glass on the glass page (`.surface-card`: translucent, a light edge, no
 * blur of its own). It was `bg-surface/40` with no shadow,
 * which on the white page of the time read as a faint hole rather than as something on it. The kit
 * Card had been `bg-surface`, a shadow and `px-6` once before, and nothing in the app used it - the
 * app wrote its own (`CARD` in Administration, ~25 class strings with six fills and four borders).
 *
 * - `subtle` (default) - the app's card and panel.
 * - `outline` - border only, for the marketing site's feature and plan cards on the canvas.
 * - `solid` - the same sheet as `subtle`; kept for a card that floats over a picture (a scene, a
 *   canvas), where it says so.
 *
 * `kit-card` marks it for the kit's CSS: a card inside a card (or inside a settings section's card)
 * keeps its line and drops its shadow - a sheet on a sheet needs no height - and a `TableCard flush`
 * inside one is the card's own content, not a second card (proxyma-primitives.css).
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
   * line, no tint - the agent proposed it; `confirmed` a line in the success ink. Five
   * key-account tabs drew these three by hand, five ways.
   */
  state?: CardState
  interactive?: boolean
  padding?: 'none' | 'sm' | 'md' | 'lg'
  className?: string
} = {}): string {
  return cn(
    'kit-card rounded-lg border min-w-0',
    // GLASS in an app shell (`surface-card`, since 2026-10-29): a translucent fill, a light edge and
    // the card shadow's top highlight; the card plane and its line elsewhere. The class decides the
    // edge's color, so the default tone names none.
    (variant === 'subtle' || variant === 'solid') && 'surface-card shadow-card',
    variant === 'outline' && 'bg-transparent border-line',
    tone === 'danger' ? 'border-danger/30 bg-danger/5' : highlighted && 'border-brand-solid/60',
    state === 'proposed' && 'border-dashed border-line',
    state === 'agent' && 'border-dashed border-brand-solid/50',
    state === 'confirmed' && 'border-success/40',
    interactive && 'focus-border cursor-pointer transition-colors',
    padding === 'sm' && 'px-3 py-3',
    padding === 'md' && 'px-4 sm:px-6 py-4',
    padding === 'lg' && 'p-6 sm:p-8',
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
 *
 * `caption` is what the card shows, in a sentence: directly under the title, 4px below it, before
 * the content - never a line after the content (the maintainer's review, 2026-10-29: "Description
 * should be placed below the section title"; the telemetry console's cards closed on theirs).
 */
export function CardHeader({ title, readout, actions, caption, divided, className }: {
  title: ReactNode
  readout?: ReactNode
  actions?: ReactNode
  /** The card's description, under the title. */
  caption?: ReactNode
  divided?: boolean
  className?: string
}) {
  const row = (
    <div className={cn('flex items-center justify-between gap-3 min-h-5', !caption && (divided ? 'px-4 sm:px-6 py-3 border-b border-line/40' : 'mb-3'), !caption && className)}>
      <p className={cn(divided ? TEXT.label : TEXT.eyebrow, 'min-w-0 truncate')}>{title}</p>
      {(readout || actions) && (
        <div className="shrink-0 flex items-center gap-3">
          {readout && <span className="text-xs text-ink-caption data-readout">{readout}</span>}
          {actions}
        </div>
      )}
    </div>
  )
  if (!caption) return row
  return (
    <div className={cn(divided ? 'px-4 sm:px-6 py-3 border-b border-line/40' : 'mb-3', className)}>
      {row}
      <p className={cn(TEXT.caption, 'mt-1 max-w-[60ch]')}>{caption}</p>
    </div>
  )
}

/** A card's padded body, for a card with `padding="none"` and a divided header. */
export function CardBody({ children, className }: { children: React.ReactNode; className?: string }) {
  return <div className={cn('px-4 sm:px-6 py-4', className)}>{children}</div>
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
