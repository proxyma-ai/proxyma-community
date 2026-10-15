/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/MetaList.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import type { ReactNode } from 'react'
import { cn } from './cn'

interface Props {
  items: ReactNode[]
  className?: string
  /** `dot` (the default) joins with a middle dot; `gap` only spaces them, for a line of badges. */
  separator?: 'dot' | 'gap'
}

/**
 * A metadata line - facts about a thing, joined by a middle dot in disabled ink ("12 items, synced
 * 3 min ago"). Falsy items are dropped, so a caller can inline conditions. The dot was typed by
 * hand three ways (`&middot;`, a literal non-ASCII dot in source, " - "), in three inks.
 */
export function MetaList({ items, className, separator = 'dot' }: Props) {
  const visible = items.filter(Boolean)
  return (
    <div className={cn(separator === 'gap' && 'flex flex-wrap items-center gap-x-3 gap-y-1', className)}>
      {visible.map((item, i) => (
        <span key={i} className="inline-flex items-center whitespace-nowrap">
          {item}
          {separator === 'dot' && i < visible.length - 1 && (
            <span className="text-ink-disabled" aria-hidden="true">&nbsp;&middot;&nbsp;</span>
          )}
        </span>
      ))}
    </div>
  )
}
