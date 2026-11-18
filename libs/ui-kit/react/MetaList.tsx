import type { ReactNode } from 'react'
import { cn } from './cn'

interface Props {
  items: ReactNode[]
  className?: string
  /** `dot` (the default) joins with a middle dot; `gap` only spaces them, for a line of badges. */
  separator?: 'dot' | 'gap'
  /**
   * A header's facts ON A PHONE ONE A LINE, with no dots (the maintainer, 2026-11-17: "Metadata should be placed line by
   * line" - a source's "5 items - synced 59m ago - shared" broke mid-way). Wider, the line as always.
   */
  stackOnPhone?: boolean
}

/**
 * A metadata line - facts about a thing, joined by a middle dot in disabled ink ("12 items, synced
 * 3 min ago"). Falsy items are dropped, so a caller can inline conditions. The dot was typed by
 * hand three ways (`&middot;`, a literal non-ASCII dot in source, " - "), in three inks.
 */
export function MetaList({ items, className, separator = 'dot', stackOnPhone = false }: Props) {
  const visible = items.filter(Boolean)
  return (
    <div className={cn(separator === 'gap' && 'flex flex-wrap items-center gap-x-3 gap-y-1', className)}>
      {visible.map((item, i) => (
        <span key={i} className={cn('inline-flex items-center whitespace-nowrap', stackOnPhone && '[@media(max-width:639px)]:flex')}>
          {item}
          {separator === 'dot' && i < visible.length - 1 && (
            <span className={cn('text-ink-disabled', stackOnPhone && '[@media(max-width:639px)]:hidden')} aria-hidden="true">&nbsp;&middot;&nbsp;</span>
          )}
        </span>
      ))}
    </div>
  )
}
