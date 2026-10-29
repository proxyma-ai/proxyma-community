/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/Avatar.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import { Bot } from 'lucide-react'
import { cn } from './cn'
import { Readout } from './Text'
import { Tooltip } from './Tooltip'

/** Up to two initials: the first letters of the first and last words of a name. */
export function initials(name: string | null | undefined): string {
  const words = (name ?? '').trim().split(/\s+/).filter(Boolean)
  if (words.length === 0) return '?'
  const first = words[0][0] ?? ''
  const last = words.length > 1 ? words[words.length - 1][0] ?? '' : ''
  return (first + last).toUpperCase()
}

export type AvatarSize = 'sm' | 'md'

/**
 * A PERSON, OR THE AGENT, AS A CIRCLE - who is here, who wrote a comment, who changed a record.
 * A person is their initials on the brand's pale fill; the AGENT is the robot in a dashed ring, so
 * what the agent proposed never reads as something a person decided. `rounded-full` because a face
 * is round by nature. 24px (`sm`) in a row, 32px (`md`) on its own. It is decorative: the name is
 * always written beside it, or given to `AvatarStack` as the tooltip.
 *
 * It was the key-account pages' own (`Bits.tsx`), with the agent's circle drawn a second time by
 * hand in the activity log.
 */
export function Avatar({ name, kind = 'person', size = 'md', className }: {
  name?: string | null
  kind?: 'person' | 'agent'
  size?: AvatarSize
  className?: string
}) {
  const box = size === 'sm' ? 'w-6 h-6 text-2xs' : 'w-8 h-8 text-xs'
  if (kind === 'agent') {
    return (
      <span aria-hidden="true" className={cn('inline-flex shrink-0 items-center justify-center rounded-full border border-dashed border-brand-solid/60 text-brand-legible bg-surface', box, className)}>
        <Bot size={size === 'sm' ? 12 : 16} />
      </span>
    )
  }
  return (
    <span aria-hidden="true" className={cn('inline-flex shrink-0 items-center justify-center rounded-full bg-surface-alt text-brand-legible font-semibold border border-surface', box, className)}>
      {initials(name)}
    </span>
  )
}

/**
 * SEVERAL PEOPLE AT ONCE - the faces overlapping, the first `max` shown and "+N" for the rest, each
 * face a tab stop with its own tooltip ("Lan is on Moves"). The whole stack is named by `label`
 * ("Also here: Lan, Minh, Quan").
 */
export function AvatarStack({ people, max = 4, label, size = 'md', className }: {
  people: readonly { key: string; name?: string | null; tooltip?: string; kind?: 'person' | 'agent' }[]
  max?: number
  /** The stack's accessible name, translated. */
  label: string
  size?: AvatarSize
  className?: string
}) {
  const shown = people.slice(0, max)
  const more = people.length - shown.length
  return (
    <div role="group" aria-label={label} className={cn('flex items-center', className)}>
      {shown.map((p, i) => {
        const face = (
          <span tabIndex={p.tooltip ? 0 : undefined} className={cn('rounded-full focus-ring animate-fade-in', i > 0 && '-ml-2')}>
            <Avatar name={p.name} kind={p.kind} size={size} />
          </span>
        )
        return p.tooltip ? <Tooltip key={p.key} label={p.tooltip} side="bottom">{face}</Tooltip> : <span key={p.key} className="contents">{face}</span>
      })}
      {more > 0 && <Readout tone="caption" className="ml-2 text-xs animate-fade-in">+{more}</Readout>}
    </div>
  )
}
