/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/FadeSwap.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

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
