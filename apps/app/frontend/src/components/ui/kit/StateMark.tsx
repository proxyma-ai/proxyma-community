/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/StateMark.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import type { ComponentType } from 'react'
import { AlertCircle, AlertTriangle, CheckCircle2, Clock, Eye, FilePen, FileX, PauseCircle, XCircle } from 'lucide-react'
import { cn } from './cn'
import { Spinner } from './Spinner'
import { Tooltip } from './Tooltip'
import { workingMotion } from './working'

export type WorkState = 'running' | 'done' | 'failed' | 'partial' | 'stopped' | 'pending'

const WORK_TONE: Record<WorkState, string> = {
  // Blinking as well, unless it stands beside words that wave (`blink`, and working.ts).
  running: 'text-ink-caption',
  // "It worked" is the product's own colour, not green - the maintainer's call for tool rows on
  // 2026-10-03, and now for every piece of work: a step, a sub-agent, an upload, a copy.
  done: 'text-brand-legible',
  failed: 'text-danger',
  partial: 'text-warning',
  stopped: 'text-ink-disabled',
  pending: 'text-ink-disabled',
}

/**
 * ONE MARK FOR A PIECE OF WORK: what it is (the glyph) and how it stands (the colour) - a tool
 * call, a sub-agent, a workflow step, an upload.
 *
 * It replaced a spinner, a check or a cross standing IN FRONT of the glyph - two marks a row, the
 * first saying only what the second can say by its colour. The agents dialog and the sub-agent
 * panel coloured the same stopped agent red in one and grey in the other; both read this now.
 * Colour is never the only signal: the row says the same in words beside it.
 */
export function StateMark({ icon: Icon, state, size = 14, blink = true, className }: {
  icon: ComponentType<{ size?: number | string; className?: string }>
  state: WorkState
  /** The glyph; the slot is always 16px so a row's words do not shift. */
  size?: 12 | 14 | 16
  /**
   * Whether it blinks while running - an icon alone does. A mark beside words that WAVE holds still
   * (`false`): the kit's tool row, activity line and sub-agent panel (working.ts).
   */
  blink?: boolean
  className?: string
}) {
  return (
    <span className={cn('w-4 h-4 flex items-center justify-center shrink-0', className)}>
      <Icon size={size} className={cn(WORK_TONE[state], workingMotion('icon', blink && state === 'running'))} aria-hidden="true" />
    </span>
  )
}

export type HealthState =
  | 'ok' | 'ok-graph' | 'working' | 'graph' | 'failed' | 'warning' | 'paused' | 'queued' | 'none'
  // What a sync has noticed about an item but not yet acted on.
  | 'detected' | 'modified' | 'deleted'

/**
 * THE HEALTH GLYPH of a thing that is indexed, synced or stored - a source, a document, a backup.
 *
 * Filled circles in their tone (`fill-*\/20`), the kit `Spinner` while working and its `spokes`
 * for the knowledge graph (a different silhouette, so graph work is not mistaken for embedding),
 * one ink for both. Four icon maps drew these before, with a bare check in one, a filled circle in
 * another, and the graph's spokes in two different colours. `label` becomes the tooltip and the
 * accessible name; without it the mark is decorative.
 */
export function StatusMark({ state, label, size = 16 }: { state: HealthState; label?: string; size?: 14 | 16 }) {
  let mark: React.ReactNode
  switch (state) {
    case 'ok': mark = <CheckCircle2 size={size} className="text-success fill-success/20" aria-hidden="true" />; break
    case 'ok-graph': mark = <CheckCircle2 size={size} className="text-brand-legible fill-brand-accent/20" aria-hidden="true" />; break
    case 'working': mark = <Spinner size={size} className="text-brand-legible" />; break
    case 'graph': mark = <Spinner size={size} variant="spokes" className="text-brand-legible" />; break
    case 'failed': mark = <AlertCircle size={size} className="text-danger fill-danger/20" aria-hidden="true" />; break
    case 'warning': mark = <AlertTriangle size={size} className="text-warning" aria-hidden="true" />; break
    case 'paused': mark = <PauseCircle size={size} className="text-warning fill-warning/20" aria-hidden="true" />; break
    case 'queued': mark = <Clock size={size} className="text-ink-caption" aria-hidden="true" />; break
    case 'none': mark = <XCircle size={size} className="text-ink-disabled" aria-hidden="true" />; break
    // Seen, changed and gone at the source - what the next pass will index, re-index or remove.
    // Outline glyphs: nothing has failed, and nothing is finished.
    case 'detected': mark = <Eye size={size} className="text-ink-disabled" aria-hidden="true" />; break
    case 'modified': mark = <FilePen size={size} className="text-warning" aria-hidden="true" />; break
    case 'deleted': mark = <FileX size={size} className="text-ink-disabled" aria-hidden="true" />; break
  }
  const slot = (
    <span role={label ? 'img' : undefined} aria-label={label} className="inline-flex items-center justify-center shrink-0">
      {mark}
    </span>
  )
  return label ? <Tooltip label={label}>{slot}</Tooltip> : slot
}

/**
 * The 6px "an agent is working here" dot - a nav word's, a history row's. It pulses with the
 * product's one working rhythm (`animate-pulse`), as every icon alone does (working.ts).
 */
export function StatusDot({ tone = 'accent', pulse = true, label, className }: {
  tone?: 'accent' | 'warning' | 'danger' | 'muted'
  pulse?: boolean
  label?: string
  className?: string
}) {
  return (
    <span
      role={label ? 'status' : undefined}
      aria-label={label}
      className={cn(
        'inline-block w-1.5 h-1.5 rounded-full shrink-0',
        tone === 'accent' && 'bg-brand-accent',
        tone === 'warning' && 'bg-warning',
        tone === 'danger' && 'bg-danger',
        tone === 'muted' && 'bg-ink-disabled',
        pulse && 'animate-pulse',
        className,
      )}
    />
  )
}

/**
 * WORK GOING ON ELSEWHERE, AS A WAY BACK TO IT - the badge beside a nav word while an agent works
 * under it and you are on another page: the working dot (it blinks: an icon alone) and how many,
 * on a faint brand tint, a button to the work. Colour-only response to the pointer, like every
 * icon control; 44px on glass. Drawn by hand on the header's nav word and again in the phone menu.
 */
export function WorkingBadge({ count, label, onClick, className }: {
  count: number
  /** What pressing it does, in words ("2 conversations still working - go to the latest"). */
  label: string
  onClick: () => void
  className?: string
}) {
  return (
    <Tooltip label={label} side="bottom">
      <button
        type="button"
        onClick={onClick}
        aria-label={label}
        className={cn(
          'inline-flex h-5 min-w-5 items-center justify-center gap-1 rounded-full px-2 touch:h-tap touch:min-w-tap',
          'bg-surface-alt text-brand-legible mouse:hover:text-ink-primary focus-ring',
          className,
        )}
      >
        <StatusDot />
        <span className="data-readout text-2xs font-semibold">{count}</span>
      </button>
    </Tooltip>
  )
}
