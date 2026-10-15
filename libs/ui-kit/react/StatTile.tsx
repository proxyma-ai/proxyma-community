import type { ComponentType, ReactNode } from 'react'
import { cardClass } from './Card'
import { cn } from './cn'
import { Skeleton } from './Skeleton'

/**
 * ONE LABELLED NUMBER - a label over a figure, and nothing else by default.
 *
 * It replaced three tiles and the kit's `StatCard`: the kit one opened with an icon in brand ink
 * and an UPPER-CASE label, against the 2026-09-30 rule (stat-tile labels are sentence case in
 * caption ink), so the app had built two tiles of its own instead of fixing it - Administration's
 * (label over value) and the Guardrails tab's (value over label, smaller, on the canvas). A row of
 * four icon tiles read as four differently-coloured badges before it read as four figures.
 *
 * `icon` stays possible for a console's filter tile; `onSelect` + `selected` make the tile a
 * button that filters the list under it (the telemetry console's levels).
 */
export function StatTile({ label, value, sub, icon: Icon, loading, selected, onSelect, size = 'md', ariaLabel, className }: {
  label: ReactNode
  value: ReactNode
  /** A line under the figure - a comparison, a unit, a period. */
  sub?: ReactNode
  icon?: ComponentType<{ size?: number | string; className?: string }>
  loading?: boolean
  selected?: boolean
  onSelect?: () => void
  /** `display` is the landing site's large unboxed figure. */
  size?: 'md' | 'display'
  /** A filter tile's name when "label, figure" is not the whole of it ("Show all licenses"). */
  ariaLabel?: string
  className?: string
}) {
  const shown = typeof value === 'number' ? value.toLocaleString() : (value ?? '-')
  const body = (
    <>
      <span className="flex items-center gap-1.5 mb-1 min-w-0">
        {Icon && <Icon size={14} className="shrink-0 text-ink-caption" aria-hidden="true" />}
        <span className={cn('truncate', size === 'display' ? 'text-sm text-ink-caption' : 'text-xs text-ink-caption')}>{label}</span>
      </span>
      {loading ? (
        <Skeleton className="h-7 w-16" />
      ) : (
        <span className={cn('block font-semibold text-ink-primary data-readout truncate', size === 'display' ? 'text-4xl' : 'text-lg')}>{shown}</span>
      )}
      {sub && <span className="block text-xs text-ink-caption mt-0.5">{sub}</span>}
    </>
  )
  const cls = cn(
    'block min-w-0 text-left',
    // The kit card (cardClass) at the tile's padding; a chosen tile is its highlighted border.
    size === 'md' && cardClass({ padding: 'none', highlighted: !!selected, className: 'px-4 py-3' }),
    onSelect && 'focus-border cursor-pointer transition-colors w-full',
    className,
  )
  return onSelect
    ? <button type="button" onClick={onSelect} aria-pressed={!!selected} aria-label={ariaLabel} className={cls}>{body}</button>
    : <div className={cls}>{body}</div>
}

/** A row of tiles: two across on a phone, four from `sm`, `gap-3` - the spacing table's grid. */
export function StatGrid({ children, columns = 4, className }: { children: ReactNode; columns?: 2 | 3 | 4 | 5 | 6; className?: string }) {
  return (
    <div className={cn(
      'grid grid-cols-2 gap-3',
      columns === 3 && 'sm:grid-cols-3',
      columns === 4 && 'sm:grid-cols-4',
      columns === 5 && 'sm:grid-cols-3 lg:grid-cols-5',
      columns === 6 && 'sm:grid-cols-3 lg:grid-cols-6',
      className,
    )}>
      {children}
    </div>
  )
}

export interface FilterTile<K extends string> {
  key: K
  label: ReactNode
  value: ReactNode
  sub?: ReactNode
  icon?: ComponentType<{ size?: number | string; className?: string }>
  /** The tile's whole name where "label, figure" is not it ("Show expired licenses, 4"). */
  ariaLabel?: string
}

/**
 * TILES THAT ARE THE FILTER - the license console's "All, Active, Expiring, Expired" and the
 * telemetry console's ratings: a `StatGrid` of `StatTile`s where pressing a tile shows only its
 * rows, and pressing the chosen one again goes back to all (`clearKey`). One chosen at a time
 * (`aria-pressed`), in a labelled group. Both consoles wrote this by hand.
 */
export function FilterTiles<K extends string>({ tiles, active, onChange, clearKey, label, columns = 4, loading, className }: {
  tiles: readonly FilterTile<K>[]
  active: K
  onChange: (key: K) => void
  /** The key that means "no filter" - pressing the chosen tile returns to it. */
  clearKey?: K
  /** The group's accessible name. */
  label: string
  columns?: 2 | 3 | 4 | 5 | 6
  loading?: boolean
  className?: string
}) {
  return (
    <div role="group" aria-label={label}>
      <StatGrid columns={columns} className={className}>
        {tiles.map(t => (
          <StatTile
            key={t.key}
            label={t.label}
            value={t.value}
            sub={t.sub}
            icon={t.icon}
            loading={loading}
            selected={t.key === active}
            ariaLabel={t.ariaLabel}
            onSelect={() => onChange(t.key === active && clearKey !== undefined ? clearKey : t.key)}
          />
        ))}
      </StatGrid>
    </div>
  )
}
