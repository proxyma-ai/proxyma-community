'use client'

import { createContext, useContext, type ReactNode } from 'react'
import { cn } from './cn'
import { TEXT } from './typeScale'

/**
 * FACTS AS LABEL AND VALUE - a detail dialog's fields, an About page's version line, a tool call's
 * arguments. A `<dl>`, which is what label/value pairs are.
 *
 * Two shapes, because two were in use and both are right for their place:
 * - `stacked` (the default): the label over its value, in a grid of 2 (or 3 from `sm`) - a detail
 *   dialog. It was a `DetailList` written identically in both consoles, and a `Field` before that
 *   with an upper-case label.
 * - `rows`: the label beside its value, one fact a line - a version line, a tool call's arguments
 *   (the app's `ToolArguments`, and the site's approval card drawing the same thing).
 *
 * Values are primary ink and wrap; a missing one is `EmptyValue`, never an empty cell.
 */
/** The list's layout, so its items take it without being told again. */
const LayoutContext = createContext<'stacked' | 'rows'>('stacked')

export function KeyValueList({ children, layout = 'stacked', columns = 2, className }: {
  children: ReactNode
  layout?: 'stacked' | 'rows'
  /** `stacked` only: two across always, or two on a phone and three from `sm`. */
  columns?: 2 | 3
  className?: string
}) {
  return (
    <LayoutContext.Provider value={layout}>
    <dl className={cn(
      layout === 'stacked'
        ? cn('grid grid-cols-2 gap-x-4 gap-y-4', columns === 3 && 'sm:grid-cols-3')
        : 'grid grid-cols-[minmax(0,max-content)_minmax(0,1fr)] gap-x-4 gap-y-2',
      className,
    )}>
      {children}
    </dl>
    </LayoutContext.Provider>
  )
}

/**
 * One fact. `wide` spans the whole row in the stacked grid (a comment, a list, a long identifier);
 * `figures` sets the value in the readout face (a count, a date, an ID).
 */
export function KeyValue({ label, children, wide, figures, block, clamp, layout }: {
  label: ReactNode
  children: ReactNode
  wide?: boolean
  figures?: boolean
  /** `rows`: keep the value's own line breaks (a multi-line argument). */
  block?: boolean
  /** `rows`: cut the value at three lines (a compact preview of a tool call's arguments). */
  clamp?: boolean
  /** The list's own layout by default (it reaches the item through context since 2026-10-14). */
  layout?: 'stacked' | 'rows'
}) {
  const listLayout = useContext(LayoutContext)
  if ((layout ?? listLayout) === 'rows') {
    // `contents`: the pair is grouped for valid markup, and the grid still lays out its two cells.
    return (
      <div className="contents">
        <dt className="text-xs text-ink-caption whitespace-nowrap">{label}</dt>
        <dd className={cn('text-xs text-ink-primary break-words min-w-0', figures && 'data-readout', block && 'whitespace-pre-wrap', clamp && 'line-clamp-3')}>{children}</dd>
      </div>
    )
  }
  return (
    <div className={cn('min-w-0', wide && 'col-span-full')}>
      <dt className={cn(TEXT.eyebrow, 'mb-1')}>{label}</dt>
      <dd className={cn('text-sm text-ink-primary break-words', figures && 'data-readout')}>{children}</dd>
    </div>
  )
}

/** A value that is not there: a dash in disabled ink. */
export function EmptyValue() {
  return <span className="data-readout text-ink-disabled">&mdash;</span>
}

/**
 * A FACT THAT IS ITSELF A LIST - an object inside a tool call's arguments: its label across the
 * row, then its own facts indented under a rule. Only in a `rows` list; nest a `KeyValueList
 * layout="rows"` as its children.
 */
export function KeyValueGroup({ label, children }: { label: ReactNode; children: ReactNode }) {
  return (
    <div className="col-span-2 min-w-0">
      <dt className="text-xs text-ink-caption">{label}</dt>
      <dd className="mt-2 ml-1 pl-3 border-l border-line/40">{children}</dd>
    </div>
  )
}
