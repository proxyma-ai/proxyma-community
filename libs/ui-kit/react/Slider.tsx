import type { ReactNode } from 'react'
import { cn } from './cn'

/**
 * A range input in the kit's colours, with its label and a readout of the value. Native under the
 * hood - a range is keyboard- and touch-complete as it is - with `accent-*` doing the colouring
 * the browser allows. It replaces the knowledge graph's two hand-styled ranges.
 */
export function Slider({ value, min, max, step = 1, onChange, label, readout, id, className }: {
  value: number
  min: number
  max: number
  step?: number
  onChange: (value: number) => void
  label: ReactNode
  /** The value as it should read ("1.5x", "3 hops"); defaults to the number. */
  readout?: ReactNode
  id?: string
  className?: string
}) {
  return (
    <div className={cn('flex flex-col gap-1', className)}>
      <div className="flex items-center justify-between gap-3 text-xs">
        <label htmlFor={id} className="font-medium text-ink-caption">{label}</label>
        <span className="data-readout text-ink-secondary">{readout ?? value}</span>
      </div>
      <input
        id={id}
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        onChange={e => onChange(Number(e.target.value))}
        className="w-full h-4 touch:h-6 accent-brand-solid cursor-pointer focus-ring rounded-full"
      />
    </div>
  )
}

/** A hairline across its container - `border-line/30`, the one opacity a rule between parts has. */
export function Divider({ className, vertical }: { className?: string; vertical?: boolean }) {
  if (vertical) return <span role="separator" aria-orientation="vertical" className={cn('inline-block w-px self-stretch bg-line/30', className)} />
  return <hr className={cn('border-0 border-t border-line/30 my-0', className)} />
}
