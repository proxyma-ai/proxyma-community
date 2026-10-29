/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/Meter.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import type { ReactNode } from 'react'
import { cn } from './cn'
import { EmptyState } from './States'

/**
 * THE BAR - one recipe for every progress bar, quota meter and share bar in four apps.
 *
 * Seven existed: tracks in `line/50`, `line/40`, `line/30` and `ink-caption/15`, fills in
 * `brand-solid` with a gradient and a shimmer, `brand-accent` and `brand-solid`, at three heights.
 * The token rules name `brand-accent` as THE decorative colour for "meter fills", and `RatioMeter`
 * already followed them, so: track `bg-line/40`, fill `bg-brand-accent`, `h-1.5` (`h-1` for `xs`),
 * and colour arrives only with something to say (warning, danger).
 *
 * The width is the one inline style the kit allows itself: a measured share is not a class.
 */

export type MeterTone = 'accent' | 'warning' | 'danger' | 'success' | 'muted'

const FILL: Record<MeterTone, string> = {
  accent: 'bg-brand-accent',
  warning: 'bg-warning',
  danger: 'bg-danger',
  success: 'bg-success',
  muted: 'bg-ink-caption/40',
}

const HEIGHT = { xs: 'h-1', sm: 'h-1.5', md: 'h-2' }

/**
 * A determinate or indeterminate bar. Without `value` it is indeterminate: the shimmer
 * (`.progress-wave`) on a full track, for a total not yet known.
 */
export function ProgressBar({ value, tone = 'accent', size = 'sm', label, className }: {
  /** 0-100. Absent means indeterminate. */
  value?: number
  tone?: MeterTone
  size?: keyof typeof HEIGHT
  /** The bar's accessible name ("Upload progress"). */
  label?: string
  className?: string
}) {
  const indeterminate = value === undefined
  const pct = indeterminate ? 100 : Math.max(0, Math.min(100, value))
  return (
    <div
      role="progressbar"
      aria-label={label}
      aria-valuemin={indeterminate ? undefined : 0}
      aria-valuemax={indeterminate ? undefined : 100}
      aria-valuenow={indeterminate ? undefined : Math.round(pct)}
      className={cn('w-full rounded-full overflow-hidden bg-line/40', HEIGHT[size], className)}
    >
      <div
        className={cn('h-full rounded-full transition-[width] duration-slow ease-in-out', FILL[tone], indeterminate && 'progress-wave opacity-60')}
        style={{ width: `${pct}%` }}
      />
    </div>
  )
}

export type MeterTier = 'ok' | 'warn' | 'danger'

/** Traffic-light thresholds, one rule for every quota: warning from 80%, danger from 90%. */
export function meterTier(pct: number): MeterTier {
  if (pct >= 90) return 'danger'
  if (pct >= 80) return 'warn'
  return 'ok'
}

export const METER_TEXT: Record<MeterTier, string> = {
  ok: 'text-ink-secondary',
  warn: 'text-warning',
  danger: 'text-danger',
}

/**
 * Used against a limit: the bar, then a line under it with the readout on the left (or a caption)
 * and the percentage on the right. `limit` null or 0 is unlimited - no fill, `unlimitedLabel` in
 * place of the percentage. Every figure arrives formatted (`format`), because a kit component
 * cannot know whether it is counting bytes, dollars or tokens.
 */
export function Meter({ used, limit, format = String, label, caption, percentOnly, unlimitedLabel, compact, className }: {
  used: number
  limit: number | null
  format?: (n: number) => string
  /** The meter's own name, over the bar ("Storage"). Omit when a row names it. */
  label?: ReactNode
  /** Fills the left slot under the bar when the readout is hidden ("Resets 1 November"). */
  caption?: ReactNode
  /** Hide the used/limit readout - for a viewer who may see the share but not the amounts. */
  percentOnly?: boolean
  unlimitedLabel?: string
  compact?: boolean
  className?: string
}) {
  const unlimited = limit === null || limit <= 0
  const pct = unlimited ? 0 : Math.min(100, (used / limit) * 100)
  const tier = meterTier(pct)
  return (
    <div className={cn('flex flex-col gap-1', compact ? 'w-28' : 'w-full', className)}>
      {label && <div className="text-xs font-medium text-ink-caption">{label}</div>}
      <ProgressBar value={unlimited ? 0 : pct} tone={tier === 'ok' ? 'accent' : tier === 'warn' ? 'warning' : 'danger'} size={compact ? 'sm' : 'md'} />
      <div className={cn('flex items-center gap-3 text-xs', percentOnly && !caption ? 'justify-end' : 'justify-between')}>
        {!percentOnly ? (
          <span className={cn('data-readout', unlimited ? 'text-ink-secondary' : METER_TEXT[tier])}>
            {format(used)}{!unlimited && ` / ${format(limit as number)}`}
          </span>
        ) : caption ? (
          <span className="text-ink-caption truncate">{caption}</span>
        ) : null}
        {unlimited
          ? unlimitedLabel && <span className="text-ink-caption">{unlimitedLabel}</span>
          : <span className={cn('data-readout', tier === 'ok' ? 'text-ink-caption' : METER_TEXT[tier])}>{pct.toFixed(0)}%</span>}
      </div>
    </div>
  )
}

/**
 * One bar split into shares - storage by kind, spend by service. `className` on each segment is
 * its fill (a chart token); `label` is its accessible name.
 */
export function StackedBar({ segments, size = 'md', label, className }: {
  segments: { value: number; className: string; label: string }[]
  size?: keyof typeof HEIGHT
  label?: string
  className?: string
}) {
  const total = segments.reduce((s, x) => s + Math.max(0, x.value), 0)
  return (
    <div role="img" aria-label={label} className={cn('flex w-full rounded-full overflow-hidden bg-line/40', HEIGHT[size], className)}>
      {total > 0 && segments.map(s => (
        <div key={s.label} title={s.label} className={cn('h-full transition-[width] duration-slow ease-in-out', s.className)} style={{ width: `${(Math.max(0, s.value) / total) * 100}%` }} />
      ))}
    </div>
  )
}

/** A swatch and its label (and value) - the legend under a chart or a stacked bar. */
export function LegendSwatch({ swatchClass, label, value }: { swatchClass: string; label: ReactNode; value?: ReactNode }) {
  return (
    <span className="inline-flex items-center gap-2 text-xs text-ink-caption">
      <span className={cn('w-2.5 h-2.5 rounded-sm shrink-0', swatchClass)} aria-hidden="true" />
      <span>{label}</span>
      {value !== undefined && <span className="data-readout text-ink-secondary">{value}</span>}
    </span>
  )
}

/**
 * A ring that fills with a percentage - 16px, inside an icon button (the composer's context use).
 * `currentColor`, so the button's own ink is the ring's.
 */
export function UsageRing({ value, size = 16, strokeWidth = 2, className }: { value: number; size?: number; strokeWidth?: number; className?: string }) {
  const r = (size - strokeWidth) / 2
  const c = 2 * Math.PI * r
  const pct = Math.max(0, Math.min(100, value))
  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} className={cn('shrink-0 -rotate-90', className)} aria-hidden="true">
      <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="currentColor" strokeOpacity={0.2} strokeWidth={strokeWidth} />
      <circle
        cx={size / 2} cy={size / 2} r={r} fill="none" stroke="currentColor" strokeWidth={strokeWidth}
        strokeDasharray={c} strokeDashoffset={c * (1 - pct / 100)} strokeLinecap="round"
        className="transition-[stroke-dashoffset] duration-slow ease-in-out"
      />
    </svg>
  )
}

/**
 * ONE PIECE OF PROGRESS WITH ITS WORDS - an upload, a download, a re-index, a quota: the label at
 * the left, the figures and the percent at the right, the bar under both, and an action (Cancel)
 * at the end of the line. Four of these were built by hand round a `ProgressBar` (the upload
 * dialog, a source's settings, Updates, the composer's quota); `Meter` is the same thing against a
 * LIMIT with its thresholds.
 */
export function ProgressRow({ label, figures, value, tone = 'accent', action, className }: {
  label: ReactNode
  /** "12 of 40 files", "1.2 of 3.4 GB" - in the readout face. */
  figures?: ReactNode
  /** 0-100; absent is indeterminate (and no percent is shown). */
  value?: number
  tone?: MeterTone
  action?: ReactNode
  className?: string
}) {
  const pct = value === undefined ? null : Math.round(Math.max(0, Math.min(100, value)))
  return (
    <div className={cn('min-w-0', className)}>
      <div className="flex items-baseline justify-between gap-3 mb-2 text-xs">
        <span className="min-w-0 truncate text-ink-secondary">{label}</span>
        <span className="shrink-0 flex items-baseline gap-3">
          {figures && <span className="data-readout text-ink-caption">{figures}</span>}
          {pct !== null && <span className="data-readout text-ink-primary">{pct}%</span>}
          {action}
        </span>
      </div>
      <ProgressBar value={value} tone={tone} label={typeof label === 'string' ? label : undefined} />
    </div>
  )
}

export interface BarListRow {
  key: string
  label: string
  value: number
  /** The value as written (`1,284`, `$12.40`); the number itself when absent. */
  figures?: ReactNode
  /** The grouped remainder ("Other"): caption ink and the muted bar, so it never reads as a peer. */
  muted?: boolean
  /** A mark before the label - a source type, a provider. */
  mark?: ReactNode
  /** A caption line under the label - what the figure is made of ("Sync 12, model 3"). */
  detail?: ReactNode
  /**
   * Opens the row's details - the label becomes a button (a dashboard's ranking that leads to the
   * page listing that item). The caller navigates; the kit knows no router.
   */
  onSelect?: () => void
}

/**
 * A RANKED LIST WITH BARS - top features, top services, top versions: per row the label (cut, its
 * whole text in its `title`), a bar scaled to the LARGEST row so a ranking stays
 * readable when one row takes most of it, the figure, and its share of the total. The bars are
 * hidden from assistive technology: they restate the figures beside them, which are read as text.
 * Three of these were hand-built (usage, the call log, the telemetry console's versions).
 *
 * A row may carry a caption line (`detail`) and open its details (`onSelect`) - the telemetry
 * console's diagnosis, where each cause, connector or stage leads to the table listing it.
 */
export function BarList({ rows, label, share = true, emptyText, className }: {
  rows: readonly BarListRow[]
  /** The list's accessible name. */
  label: string
  /** The share of the total after each figure. */
  share?: boolean
  /** What an empty list says - an EmptyState sentence. */
  emptyText?: ReactNode
  className?: string
}) {
  if (rows.length === 0) return emptyText ? <EmptyState className="py-2">{emptyText}</EmptyState> : null
  const max = Math.max(1, ...rows.map(r => r.value))
  const total = rows.reduce((sum, r) => sum + r.value, 0) || 1
  const pct = (v: number) => {
    const p = (v / total) * 100
    return p > 0 && p < 1 ? '<1%' : `${Math.round(p)}%`
  }
  return (
    // ONE grid for the whole list, each row a subgrid of it: the figure column is as wide as the
    // WIDEST figure, so every bar starts and ends at the same x. A grid per row sized its `auto`
    // column to that row's own figure, so "141" and "89" moved the bar's track by a few pixels and
    // two bars of the same value were drawn at different lengths.
    <ol aria-label={label} className={cn('grid gap-x-3 gap-y-2 text-xs', share ? 'grid-cols-[minmax(0,3fr)_minmax(0,2fr)_auto_2.75rem]' : 'grid-cols-[minmax(0,3fr)_minmax(0,2fr)_auto]', className)}>
      {rows.map(r => (
        <li key={r.key} className="grid grid-cols-subgrid col-span-full items-center">
          <span className={cn('min-w-0 flex items-center gap-2', r.muted ? 'text-ink-caption' : 'text-ink-secondary')}>
            {r.mark && <span className="shrink-0 flex items-center [&>svg]:w-3.5 [&>svg]:h-3.5" aria-hidden="true">{r.mark}</span>}
            <span className="min-w-0 flex flex-col">
              {r.onSelect ? (
                // A button that reads as the row's name: its ink, underlined under a mouse.
                <button type="button" onClick={r.onSelect} title={r.label}
                        className="truncate text-left underline-offset-[3px] mouse:hover:underline mouse:hover:text-ink-primary focus-ring rounded-sm">
                  {r.label}
                </button>
              ) : (
                <span className="truncate" title={r.label}>{r.label}</span>
              )}
              {r.detail && <span className="truncate text-ink-caption">{r.detail}</span>}
            </span>
          </span>
          <span aria-hidden="true"><ProgressBar value={(r.value / max) * 100} tone={r.muted ? 'muted' : 'accent'} /></span>
          <span className="data-readout text-right text-ink-primary">{r.figures ?? r.value.toLocaleString()}</span>
          {share && <span className="data-readout text-right text-ink-caption">{pct(r.value)}</span>}
        </li>
      ))}
    </ol>
  )
}
