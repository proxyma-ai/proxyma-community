'use client'

import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { cn } from './cn'
import { axisGroups, axisTicks } from './chartAxis'

/** The plot width assumed until the chart has been measured - about a Settings column. */
const ASSUMED_WIDTH = 640
/** A character at the axis's 12px (`text-2xs`, the floor since 2026-10-15 - it was 10px). */
const AXIS_CHAR_WIDTH = 7

/**
 * A COLUMN PER PERIOD - cost per day or per month. One bar per value on a baseline, the hovered
 * column in the legible brand ink, and the readout line ABOVE the chart, which the caller writes
 * for the hovered (or peak) column. `rounded-t-sm`: a bar stands on the axis, so only its top is
 * round.
 *
 * EACH COLUMN STANDS BETWEEN TWO DOTTED GUIDES (0.28.0 feedback): a guide on every column
 * BOUNDARY, the plot's two edges included, so a day with no cost is still a visible slot and a bar
 * reads as belonging to the slot it is in. Until then a guide ran up the MIDDLE of each column,
 * through its bar.
 *
 * THE AXIS LABELS EVERY COLUMN IT HAS ROOM FOR (same feedback - it printed the first, middle and
 * last only): `labels` is one per column (the day of the month, the month), `groups` one per
 * column too (the month, the year) and written once where each group starts, on a row of its own.
 * Which labels fit is `chartAxis.ts`, from the plot's measured width - every 2nd, 3rd or 7th label
 * only when the columns are too narrow for all of them.
 *
 * A column may be SPLIT (`segments`, bottom first, summing to its value) - what users were charged
 * under what the platform paid. A split column dims under the pointer instead of changing colour,
 * since its colours are its legend.
 *
 * NOT FOR A MOUSE ONLY (since 2026-10-14): the plot is one tab stop, and the arrow keys, Home and
 * End move the active column as the pointer does; a tap chooses one on a touch screen; and the
 * readout is a polite live region, so what the caller writes for the active column is read out as
 * it changes. It was a role="img" with one name, and a keyboard, a finger or a screen reader got
 * the chart's title and nothing it showed.
 */
export function ColumnChart({ values, active, onActive, readout, labels, groups, ariaLabel, heightClass = 'h-36', className }: {
  values: { key: string; value: number; segments?: { value: number; className: string }[] }[]
  active: number | null
  onActive: (index: number | null) => void
  readout?: ReactNode
  /** One per column: what the axis writes under it, where there is room. */
  labels?: string[]
  /** One per column: the group it belongs to, written once where the group starts. */
  groups?: string[]
  ariaLabel: string
  heightClass?: string
  className?: string
}) {
  const max = Math.max(0, ...values.map(v => v.value))
  const readoutId = useId()
  const onKey = (e: React.KeyboardEvent<HTMLDivElement>) => {
    const last = values.length - 1
    if (last < 0) return
    const from = active ?? last
    const next = e.key === 'ArrowLeft' ? Math.max(0, from - 1)
      : e.key === 'ArrowRight' ? Math.min(last, from + 1)
        : e.key === 'Home' ? 0
          : e.key === 'End' ? last
            : null
    if (next === null) return
    e.preventDefault()
    onActive(next)
  }
  const plotRef = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(0)

  useEffect(() => {
    const el = plotRef.current
    if (!el) return
    setWidth(el.clientWidth)
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(entries => {
      for (const entry of entries) setWidth(entry.contentRect.width)
    })
    observer.observe(el)
    return () => observer.disconnect()
  }, [])

  const plotWidth = width || ASSUMED_WIDTH
  const ticks = labels && labels.length === values.length ? axisTicks(labels, plotWidth, { charWidth: AXIS_CHAR_WIDTH }) : []
  const groupLabels = groups && groups.length === values.length ? axisGroups(groups, plotWidth, { charWidth: AXIS_CHAR_WIDTH }) : []

  return (
    <div className={className}>
      {readout !== undefined && <div id={readoutId} aria-live="polite" className="text-xs text-ink-caption mb-2 data-readout min-h-[1rem]">{readout}</div>}
      <div
        ref={plotRef}
        className={cn('relative flex items-end border-b border-line/60 rounded-sm focus-ring', heightClass)}
        onMouseLeave={() => onActive(null)}
        role="group"
        aria-label={ariaLabel}
        aria-describedby={readout !== undefined ? readoutId : undefined}
        tabIndex={values.length > 0 ? 0 : undefined}
        onKeyDown={onKey}
        onFocus={() => { if (active === null && values.length > 0) onActive(values.length - 1) }}
        onBlur={() => onActive(null)}
      >
        {values.map((v, i) => (
          <div key={v.key} className="relative flex-1 min-w-0 h-full flex items-end px-px" onMouseEnter={() => onActive(i)} onClick={() => onActive(i)} aria-hidden="true">
            {/* The column's left boundary - and, on the last, its right one too. */}
            <span className="absolute inset-y-0 left-0 border-l border-dotted border-line/70" aria-hidden="true" />
            {i === values.length - 1 && (
              <span className="absolute inset-y-0 right-0 border-r border-dotted border-line/70" aria-hidden="true" />
            )}
            {v.segments ? (
              <div
                className={cn('relative w-full flex flex-col-reverse rounded-t-sm overflow-hidden transition-opacity', active === i && 'opacity-80')}
                style={{ height: max > 0 && v.value > 0 ? `${Math.max(2, (v.value / max) * 100)}%` : '0' }}
              >
                {v.segments.map((s, si) => (
                  <div key={si} className={cn('w-full', s.className)} style={{ height: v.value > 0 ? `${(s.value / v.value) * 100}%` : '0' }} />
                ))}
              </div>
            ) : (
              <div
                className={cn('relative w-full rounded-t-sm transition-colors', active === i ? 'bg-brand-legible' : 'bg-brand-accent')}
                style={{ height: max > 0 && v.value > 0 ? `${Math.max(2, (v.value / max) * 100)}%` : '0' }}
              />
            )}
          </div>
        ))}
      </div>
      {ticks.length > 0 && (
        <div className="relative h-4 mt-2 text-2xs text-ink-caption data-readout whitespace-nowrap" aria-hidden="true">
          {ticks.map(tick => {
            // Centred under its column by the browser, not by the width estimate - except where the
            // estimate pushed it in from an edge, where it is pinned to that edge.
            const centre = ((tick.index + 0.5) * plotWidth) / values.length
            const estimate = tick.label.length * AXIS_CHAR_WIDTH
            const position = tick.left <= 0 ? { left: 0 }
              : tick.left + estimate >= plotWidth - 0.5 ? { right: 0 }
              : { left: centre, transform: 'translateX(-50%)' }
            return (
              <span key={tick.index} className={cn('absolute top-0', active === tick.index && 'text-ink-primary')} style={position}>
                {tick.label}
              </span>
            )
          })}
        </div>
      )}
      {groupLabels.length > 0 && (
        <div className="relative h-4 text-2xs text-ink-caption whitespace-nowrap" aria-hidden="true">
          {groupLabels.map(g => (
            <span key={g.index} className="absolute top-0" style={{ left: g.left }}>{g.label}</span>
          ))}
        </div>
      )}
    </div>
  )
}
