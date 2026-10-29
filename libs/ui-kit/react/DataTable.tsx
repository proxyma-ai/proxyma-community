import type { ReactNode } from 'react'
import { ArrowDown, ArrowUp, ArrowUpDown } from 'lucide-react'
import { Card } from './Card'
import { cn } from './cn'
import { HScroll } from './HScroll'
import { EmptyState } from './States'
import { Skeleton } from './Skeleton'
import { Tooltip } from './Tooltip'

/**
 * ONE TABLE for every data table in four apps - header, row and cell - and the phone layout that
 * goes with it. It was the app's own (2026-09-30, app UI review F23/F24) and never reached the
 * kit, so both consoles kept upper-case headers and their own paddings inside one table.
 *
 * - headers are sentence case in caption ink, the sort glyph always TRAILING the label, and a
 *   header aligned the way its column is (numbers and dates right, words left);
 * - `TABLE_CLASS` carries `.table-stack` (proxyma-primitives.css): below `sm` each row is a block
 *   and each cell a line labelled with its `data-label`. Give every cell but the row's name a
 *   `data-label` - the column heading, translated - and the phone layout comes for free.
 * - A table sits in a `Card padding="none"` inside an `HScroll`, which `TableCard` assembles - with
 *   its pager or footer under it. A table that runs on the page's own column (no card) takes
 *   `TABLE_FLUSH`, so its first and last columns line up with the text above it.
 */
export const TABLE_CLASS = 'table-stack w-full text-sm'

/**
 * A table WITHOUT a card around it, on the text column: from `sm` the first column has no left
 * padding and the last no right padding, so the words in the cells line up with the heading and
 * the paragraph above. It was typed out by hand at four call sites.
 */
export const TABLE_FLUSH = 'sm:[&_th:first-child]:pl-0 sm:[&_td:first-child]:pl-0 sm:[&_th:last-child]:pr-0 sm:[&_td:last-child]:pr-0'

/**
 * THE TABLE'S CARD: `Card padding="none"`, clipped, the table in an `HScroll` (so a wide one
 * scrolls sideways with no bar), and a `footer` under it - a `ListPager` or a `TableFooter`. Every
 * table in the four apps assembled these three by hand; this file's own note named TableCard for
 * months before it existed.
 */
export function TableCard({ children, footer, ariaLabel, flush, className }: {
  /** The `<table className={TABLE_CLASS}>`. */
  children: ReactNode
  /** Under the table, inside the card: a pager, a total, a "showing 20 of 312". */
  footer?: ReactNode
  /** Names the scrolling region, where the table has no caption of its own. */
  ariaLabel?: string
  /**
   * THE TABLE'S TEXT ON THE CARD'S TEXT EDGE: its first and last columns lose their outer padding and
   * the card pads the table instead (`px-4 sm:px-6`, a card's own), so the words in the cells line
   * up with a heading above them. Since 2026-10-29 (the depth review) a flush table on the page is
   * still a card - the page is the canvas and a table of records stands on it as a sheet; it was no
   * card at all from 2026-10-28. INSIDE a card or a settings section's card it is that card's
   * content: no second card, only its rules (`.kit-card-flush` in proxyma-primitives.css). The app's,
   * the Enterprise pages' and the consoles' tables take it; the site's pages draw the plain card.
   */
  flush?: boolean
  className?: string
}) {
  if (flush) {
    return (
      <Card padding="none" className={cn('kit-card-flush overflow-hidden px-4 sm:px-6', TABLE_FLUSH, className)}>
        <HScroll aria-label={ariaLabel}>{children}</HScroll>
        {footer && <div className="border-t border-line/30">{footer}</div>}
      </Card>
    )
  }
  return (
    <Card padding="none" className={cn('overflow-hidden', className)}>
      <HScroll aria-label={ariaLabel}>{children}</HScroll>
      {footer && <div className="border-t border-line/30 px-4">{footer}</div>}
    </Card>
  )
}

/** A body row. `interactive` for a row that opens something: pointer and the one row hover. */
// A row fades in as it mounts - in place of TableSkeletonRows, or as a refetch adds it. Rows are
// keyed, so a re-sort or a live update of a row that is already there does not replay it.
export const TR = 'border-b border-line/20 last:border-b-0 animate-fade-in'
export const TR_INTERACTIVE = 'cursor-pointer transition-colors mouse:hover:bg-ink-caption/5 focus-within:bg-ink-caption/5'

/** The header row. */
export const THEAD_TR = 'border-b border-line/40'

/** A body cell's padding - one value, so rows line up across every table. */
export const TD = 'px-4 py-3'

/**
 * The COMPACT density, for a log read many rows at a time (the per-call ledger, a transfer
 * report): 13px text (`text-xs`) and `px-3 py-2`, header and body alike. A table uses one density throughout.
 */
export const TD_COMPACT = 'px-3 py-2 text-xs'

const TH = 'px-4 py-3 text-xs font-medium text-ink-caption whitespace-nowrap'
const TH_COMPACT = 'px-3 py-2 text-xs font-medium text-ink-caption whitespace-nowrap'

type Align = 'left' | 'right'

/** `wrap`: a header may take two lines, bottom-aligned - a narrow figures column ("Cache write"). */
const TH_WRAP = 'whitespace-normal align-bottom'

export function Th({ children, align = 'left', compact, wrap, className }: { children?: ReactNode; align?: Align; compact?: boolean; wrap?: boolean; className?: string }) {
  return <th scope="col" className={cn(compact ? TH_COMPACT : TH, align === 'right' ? 'text-right' : 'text-left', wrap && TH_WRAP, className)}>{children}</th>
}

export type SortDir = 'asc' | 'desc'
export interface SortState<K extends string> { key: K; dir: SortDir }

/** A header that sorts its column (click to toggle) and explains it (hover). */
export function SortTh<K extends string>({ label, sortKey, sort, onSort, align = 'left', tooltip, compact, wrap, className }: {
  label: string
  sortKey: K
  sort: SortState<K> | null
  onSort: (key: K) => void
  align?: Align
  tooltip?: string
  compact?: boolean
  wrap?: boolean
  className?: string
}) {
  const active = sort?.key === sortKey
  const button = (
    <button
      type="button"
      onClick={() => onSort(sortKey)}
      // A right-aligned column's arrow goes BEFORE its words, so the words end on the column's right
      // edge with the figures under them (the maintainer's review, 2026-10-29: the label sat an
      // arrow's width left of its numbers).
      className={cn('inline-flex items-center gap-1 mouse:hover:text-ink-primary transition-colors focus-ring rounded-sm',
        align === 'right' && 'flex-row-reverse', active && 'text-ink-primary')}
    >
      <span>{label}</span>
      {active
        ? (sort!.dir === 'asc' ? <ArrowUp size={12} aria-hidden="true" /> : <ArrowDown size={12} aria-hidden="true" />)
        : <ArrowUpDown size={12} className="opacity-40" aria-hidden="true" />}
    </button>
  )
  return (
    <th
      scope="col"
      aria-sort={active ? (sort!.dir === 'asc' ? 'ascending' : 'descending') : undefined}
      className={cn(compact ? TH_COMPACT : TH, align === 'right' ? 'text-right' : 'text-left', wrap && TH_WRAP, className)}
    >
      {tooltip ? <Tooltip label={tooltip}>{button}</Tooltip> : button}
    </th>
  )
}

/**
 * A skeleton column: the bar's width, or the width plus the CELL's own classes - the
 * `hidden sm:table-cell` of a column the table drops on a narrow screen, which a skeleton has to
 * drop too or a loading table shows columns the loaded one does not.
 */
export type SkeletonColumn = string | { width: string; cellClassName?: string }

/** Skeleton rows shaped like the table's columns (Tailwind width classes), at the cells' padding. */
export function TableSkeletonRows({ rows, columns, compact }: { rows: number; columns: SkeletonColumn[]; compact?: boolean }) {
  return (
    <>
      {Array.from({ length: rows }).map((_, r) => (
        <tr key={r} className={TR}>
          {columns.map((col, c) => {
            const { width, cellClassName } = typeof col === 'string' ? { width: col, cellClassName: undefined } : col
            return <td key={c} className={cn(compact ? TD_COMPACT : TD, cellClassName)}><Skeleton className={cn('h-3', width)} /></td>
          })}
        </tr>
      ))}
    </>
  )
}

/**
 * The line under a table - "Showing 50 of 312" and a way to see more - on the table's own padding,
 * divided from the last row.
 */
export function TableFooter({ children, action, className }: { children: ReactNode; action?: ReactNode; className?: string }) {
  return (
    <div className={cn('flex items-center justify-between gap-3 px-4 py-3 border-t border-line/30 text-xs text-ink-caption', className)}>
      <span className="data-readout">{children}</span>
      {action}
    </div>
  )
}

/** The one row a table shows when it is empty: the kit `EmptyState` across every column. */
export function TableEmptyRow({ colSpan, children, action }: { colSpan: number; children: ReactNode; action?: ReactNode }) {
  return (
    <tr>
      <td colSpan={colSpan} className="px-4">
        <EmptyState action={action}>{children}</EmptyState>
      </td>
    </tr>
  )
}

/**
 * WHO A ROW IS - a name over a second line (an email, a role, an id) in a table's first cell: the
 * name at the row's weight, the line under it in caption ink, both cut rather than wrapped. The
 * user tables drew it by hand, and the site's scenes copied them.
 */
export function IdentityCell({ name, sub, leading }: { name: ReactNode; sub?: ReactNode; leading?: ReactNode }) {
  return (
    <div className="flex items-center gap-3 min-w-0">
      {leading}
      <div className="min-w-0">
        <div className="text-sm font-medium text-ink-primary truncate">{name}</div>
        {sub && <div className="text-xs text-ink-caption truncate">{sub}</div>}
      </div>
    </div>
  )
}
