import type { ReactNode } from 'react'
import { Circle, CheckCircle2 } from 'lucide-react'
import { cn } from './cn'

/**
 * RULES BEING MET as someone types - a password's length, a digit, a symbol. Each item is a circle
 * that fills with a tick in the success ink when its rule passes (a verdict, so `success`, not the
 * brand ink of work done). It was `PasswordChecklist`, written three times: once in the app and once
 * in each console, the console copies saying "verbatim from the app".
 *
 * Named `RuleChecklist` since 2026-10-14: it was `Checklist`, one letter-case away from the
 * marketing `CheckList` (now `IncludedList`) on a file system that cannot tell the two apart.
 * `labels` says, to a screen reader, whether each rule is met - a tick alone said nothing for a
 * rule not met yet.
 */
export function RuleChecklist({ items, labels, className }: {
  items: { key: string; label: ReactNode; pass: boolean }[]
  /** Read after each rule ("met", "not met yet"), translated. */
  labels?: { met: string; unmet: string }
  className?: string
}) {
  return (
    <ul className={cn('flex flex-col gap-1', className)}>
      {items.map(item => (
        <li key={item.key} className={cn('flex items-center gap-1.5 text-xs transition-colors', item.pass ? 'text-success' : 'text-ink-disabled')}>
          {item.pass
            ? <CheckCircle2 size={16} className="shrink-0" aria-hidden="true" />
            : <Circle size={16} className="shrink-0" aria-hidden="true" />}
          <span>{item.label}</span>
          {labels && <span className="sr-only">{item.pass ? labels.met : labels.unmet}</span>}
        </li>
      ))}
    </ul>
  )
}
