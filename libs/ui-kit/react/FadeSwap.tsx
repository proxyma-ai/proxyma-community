'use client'

import type { ReactNode } from 'react'
import { useRef } from 'react'
import { useFadeOnChange } from './motion'

/**
 * CONTENT THAT CHANGES WITH A KEY - a tab's panel, a step of a wizard, a routed page: the new
 * content fades in when `swapKey` changes instead of replacing the old in one frame. Nothing under
 * it is remounted (see `useFadeOnChange`), so a page keeps its state when only its key moves. The
 * wrapper is a plain block; `className` gives it the layout its parent expects (`flex-1 min-h-0`).
 */
export function FadeSwap({ swapKey, children, className }: { swapKey: unknown; children: ReactNode; className?: string }) {
  const ref = useRef<HTMLDivElement>(null)
  useFadeOnChange(ref, swapKey)
  return <div ref={ref} className={className}>{children}</div>
}
