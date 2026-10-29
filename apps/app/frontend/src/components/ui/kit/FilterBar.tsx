/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/FilterBar.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import type { ReactNode } from 'react'
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
 * THE SHAPE OF A TAB BAR, since 2026-10-30 (the maintainer: "should be similar to the sub menu bar
 * as the other pages"): a sunken well - a 12px track, a 4px inset - with the chosen filter a lifted
 * pill, as SubNavTabs and SegmentedControl draw it. The semantics stay a filter's: buttons with
 * `aria-pressed` in a labelled group, since nothing here is a tab panel. They were outlined chips
 * from 2026-10-14.
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
    <div role="group" aria-label={ariaLabel}
      className={cn('inline-flex items-center gap-0.5 rounded-lg bg-surface-alt shadow-well p-1', wrap ? 'flex-wrap' : 'w-max')}>
      {options.map(o => {
        const on = o.key === active
        return (
          <button
            key={o.key}
            type="button"
            aria-pressed={on}
            onClick={() => onChange(o.key)}
            className={cn(
              'shrink-0 flex items-center gap-2 whitespace-nowrap h-8 touch:h-10 px-3 rounded-md text-sm font-medium focus-ring',
              'transition-[background-color,color,box-shadow] duration-base ease-out',
              on ? 'bg-surface-float text-ink-primary shadow-card' : 'text-ink-secondary mouse:hover:text-ink-primary mouse:hover:bg-surface/50',
            )}
          >
            <span>{o.label}</span>
            {o.count !== undefined && <span className="text-xs font-normal text-ink-caption data-readout">{o.count}</span>}
          </button>
        )
      })}
    </div>
  )
  if (wrap) return <div className={className}>{row}</div>
  return <HScroll focusable={false} className={className}>{row}</HScroll>
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
