import type { ReactNode } from 'react'
import { Check, X } from 'lucide-react'
import { cn } from './cn'
import { Spinner } from './Spinner'
import { TEXT } from './typeScale'

export type StepState = 'done' | 'current' | 'running' | 'pending' | 'failed'

export interface Step {
  key: string
  label: ReactNode
  /** A line under the label - what the step does, or what went wrong. */
  detail?: ReactNode
  state: StepState
}

/**
 * WHERE A PROCESS IS - the steps of a backup or restore, a first-run setup, a key's activation: a
 * numbered list whose marks say which steps are done (a tick in the brand's ink of work done),
 * which one is now (the number on the brand fill, or the Spinner while it runs), which failed (a
 * cross in danger ink) and which are still to come (the number in an outline). `vertical` (the
 * default) gives each step its detail line; `horizontal` is the compact row over a wizard's body,
 * labels only, joined by a hairline. The current step is `aria-current="step"`.
 *
 * Steps are instructions to READ, not a process, in `StepList`; this is the process. The backup
 * phases, the workflow's steps and the trial key's activation drew their own.
 */
export function Stepper({ steps, orientation = 'vertical', label, className }: {
  steps: readonly Step[]
  orientation?: 'vertical' | 'horizontal'
  /** The list's accessible name ("Restore progress"). */
  label?: string
  className?: string
}) {
  if (orientation === 'horizontal') {
    return (
      <ol aria-label={label} className={cn('flex items-center gap-2 min-w-0', className)}>
        {steps.map((s, i) => (
          <li key={s.key} aria-current={s.state === 'current' || s.state === 'running' ? 'step' : undefined} className="flex items-center gap-2 min-w-0">
            {i > 0 && <span aria-hidden="true" className={cn('h-px w-6 shrink-0', s.state === 'pending' ? 'bg-line' : 'bg-brand-accent')} />}
            <StepMark index={i} state={s.state} />
            <span className={cn('text-xs truncate', s.state === 'pending' ? 'text-ink-caption' : 'text-ink-primary', s.state === 'current' && 'font-medium')}>{s.label}</span>
          </li>
        ))}
      </ol>
    )
  }
  return (
    <ol aria-label={label} className={cn('space-y-3', className)}>
      {steps.map((s, i) => (
        <li key={s.key} aria-current={s.state === 'current' || s.state === 'running' ? 'step' : undefined} className="flex items-start gap-3 animate-fade-in">
          <StepMark index={i} state={s.state} />
          <div className="min-w-0 pt-0.5">
            <p className={cn(TEXT.label, s.state === 'pending' && 'text-ink-caption font-normal', s.state === 'failed' && 'text-danger')}>{s.label}</p>
            {s.detail && <div className={cn(TEXT.caption, 'mt-0.5')}>{s.detail}</div>}
          </div>
        </li>
      ))}
    </ol>
  )
}

/** One step's mark: 20px, round - a number, a tick, a cross or the Spinner. */
function StepMark({ index, state }: { index: number; state: StepState }) {
  const box = 'w-5 h-5 shrink-0 rounded-full flex items-center justify-center text-2xs font-semibold data-readout transition-colors'
  if (state === 'done') return <span aria-hidden="true" className={cn(box, 'bg-brand-pale text-brand-legible')}><Check size={12} strokeWidth={3} /></span>
  if (state === 'failed') return <span aria-hidden="true" className={cn(box, 'bg-danger/10 text-danger')}><X size={12} strokeWidth={3} /></span>
  if (state === 'running') return <span aria-hidden="true" className={cn(box, 'text-brand-legible')}><Spinner size={16} /></span>
  if (state === 'current') return <span aria-hidden="true" className={cn(box, 'bg-brand-solid text-brand-on-solid')}>{index + 1}</span>
  return <span aria-hidden="true" className={cn(box, 'border border-line text-ink-caption')}>{index + 1}</span>
}
