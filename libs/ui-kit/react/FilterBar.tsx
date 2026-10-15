'use client'

import type { ReactNode } from 'react'
import { Chip } from './Chip'
import { cn } from './cn'
import { HScroll } from './HScroll'

export interface FilterOption<K extends string> {
  key: K
  label: ReactNode
  count?: number
}

/**
 * A ROW OF FILTERS over a list - All, Files, Jira, Web; Applies, Tailored out, All. One chosen at a
 * time, each with its count. In the kit's `HScroll`, so a long row scrolls sideways with no
 * scrollbar.
 *
 * SINCE 2026-10-14 EACH FILTER IS THE KIT'S FILTER CHIP (`Chip variant="filter"`): an outline at
 * rest, the brand chip pair when chosen, `aria-pressed`, in a labelled group. There were two looks
 * for one idea - these words on a quiet fill as `role="tab"` (with no tab panel and no arrow keys),
 * and the outlined chip - side by side in the gallery. One now, and the right semantics for it.
 *
 * `wrap` lays the chips out on as many lines as they need instead: for a row in a NARROW column -
 * a list beside its detail - where the last chip would otherwise start cut off behind the fade at
 * every width, and a person has to discover that the row scrolls to find a filter at all.
 */
export function FilterChips<K extends string>({ options, active, onChange, ariaLabel, className, wrap = false }: {
  options: readonly FilterOption<K>[]
  active: K
  onChange: (key: K) => void
  ariaLabel: string
  className?: string
  wrap?: boolean
}) {
  const row = (
    <div className={cn('flex items-center gap-1.5 px-1 py-0.5', wrap ? 'flex-wrap' : 'w-max')} role="group" aria-label={ariaLabel}>
      {options.map(o => (
        <Chip
          key={o.key}
          variant="filter"
          label={o.label}
          count={o.count}
          pressed={o.key === active}
          onClick={() => onChange(o.key)}
          className="touch:min-h-tap"
        />
      ))}
    </div>
  )
  if (wrap) return <div className={cn('-mx-1', className)}>{row}</div>
  return <HScroll focusable={false} className={cn('-mx-1', className)}>{row}</HScroll>
}

/**
 * The bar over a list: the filters on the left, a search and toggles on the right, stacking on a
 * phone (search first, full width). Its slots take kit parts.
 */
export function FilterBar({ filters, search, children, className }: {
  filters?: ReactNode
  search?: ReactNode
  /** Toggles or actions beside the search. */
  children?: ReactNode
  className?: string
}) {
  return (
    <div className={cn('space-y-3', className)}>
      {filters}
      {(search || children) && (
        <div className="flex flex-col sm:flex-row sm:items-center gap-3">
          {search && <div className="flex-1 min-w-0 sm:max-w-sm">{search}</div>}
          {children && <div className="flex items-center gap-3 sm:ml-auto">{children}</div>}
        </div>
      )}
    </div>
  )
}

/**
 * A ROW OF CONTROLS OVER A PAGE'S CONTENT - a period and a version picker at the left, an export
 * and a refresh at the right: wrapping onto a second line on a phone rather than overflowing it,
 * `gap-3` between controls (the spacing table's action row). Not `role="toolbar"`, which promises
 * arrow-key movement between its controls; a labelled group when `label` names it. The filters a
 * LIST takes are `FilterBar`; the page header's own actions are `PageHeader`'s.
 */
export function Toolbar({ start, end, label, className }: {
  start?: ReactNode
  end?: ReactNode
  label?: string
  className?: string
}) {
  return (
    <div role={label ? 'group' : undefined} aria-label={label} className={cn('flex flex-wrap items-center justify-between gap-3', className)}>
      {start && <div className="flex flex-wrap items-center gap-3 min-w-0">{start}</div>}
      {end && <div className="flex flex-wrap items-center gap-3 ml-auto">{end}</div>}
    </div>
  )
}
