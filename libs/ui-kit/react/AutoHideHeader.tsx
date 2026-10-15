'use client'

import { useEffect, useRef, type ReactNode } from 'react'
import { cn } from './cn'
import { useHideOnSwipe } from './useAutoHide'

export interface AutoHideHeaderProps {
  /** The header - an `AppHeader`, whose height the margins below are. */
  children: ReactNode
  /** Brings the bar back when it changes. Pass the route, so every page starts with its header. */
  resetKey?: unknown
}

/**
 * An `AppHeader` in the flow of a shell whose content scrolls inside it, stepping out of the way
 * on a phone while the reader moves down the page - see autoHide.ts.
 *
 * <p>A MARGIN, not a transform: the shell is a flex column, so pulling the bar up by its own
 * height hands exactly that height to the region under it. A transform would move the paint and
 * leave the hole. The margins are `AppHeader`'s two heights (h-14, short:h-11) and have to change
 * with them.
 *
 * <p>`inert` while it is away, so nothing in a bar nobody can see takes focus.
 */
export function AutoHideHeader({ children, resetKey }: AutoHideHeaderProps) {
  const hidden = useHideOnSwipe(resetKey)
  const ref = useRef<HTMLDivElement>(null)
  // Set on the node rather than as a prop: React 18's types do not know `inert` and React 19's
  // want a boolean, and this file is compiled under both.
  useEffect(() => { ref.current?.toggleAttribute('inert', hidden) }, [hidden])
  return (
    <div
      ref={ref}
      className={cn(
        'shrink-0 transition-[margin-top] duration-slow ease-out',
        hidden && '-mt-14 short:-mt-11',
      )}
    >
      {children}
    </div>
  )
}
