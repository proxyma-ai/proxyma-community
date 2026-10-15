/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/Presence.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import type { ReactNode } from 'react'
import { useEffect, useRef, useState } from 'react'
import { cn } from './cn'
import { MOTION_MS, exitingProps, motionReady, usePresence } from './motion'

/**
 * SOMETHING THAT COMES AND GOES IN THE PAGE - a banner, an error line, a "Saved." note, a form that
 * opens under a button. `{error && <InlineNotice/>}` put it there and took it away in one frame,
 * and everything under it jumped; `<Presence show={!!error}>` eases it open and shut instead.
 *
 * - `collapse` (the default): its height and margins ease with it, so the content below moves up
 *   and down rather than jumping - for anything in the flow. `collapse={false}` only fades, for
 *   something in a row (a footer's status line) whose height nothing below depends on.
 * - It keeps the LAST children it was shown with for the exit, so the call site can write
 *   `{error}` inside without it going blank while it leaves. `children` may be omitted while
 *   hidden: a component that returned `null` returns `<Presence show={false} />` in its place, at
 *   the same position in its tree, and keeps its exit.
 * - Shown from the page's first paint, it is simply there - no entrance while the page loads.
 * - Its clip is on only while it moves, so a dropdown or a focus ring inside is not cut off.
 * - Reduced motion: it appears and goes at once (usePresence).
 */
export function Presence({ show, children, collapse = true, className }: {
  show: boolean
  children?: ReactNode
  collapse?: boolean
  /** On the wrapper - a flex item's `shrink-0`, a row's `mr-auto`. */
  className?: string
}) {
  const { mounted, exiting } = usePresence(show, collapse ? MOTION_MS.base : MOTION_MS.fast)
  const last = useRef<ReactNode>(children)
  if (show) last.current = children
  // There before transitions were allowed: part of the first paint, not an arrival.
  const atLoad = useRef(show && !motionReady())
  if (!show) atLoad.current = false
  if (!mounted) return null
  return (
    <PresenceBody exiting={exiting} enter={!atLoad.current} collapse={collapse} className={className}>
      {show ? children : last.current}
    </PresenceBody>
  )
}

/** Mounted afresh for every appearance, so each one starts clipped and unclips when it lands. */
function PresenceBody({ exiting, enter, collapse, className, children }: {
  exiting: boolean
  enter: boolean
  collapse: boolean
  className?: string
  children: ReactNode
}) {
  const [settled, setSettled] = useState(!enter)
  useEffect(() => {
    if (settled) return
    const timer = setTimeout(() => setSettled(true), MOTION_MS.base)
    return () => clearTimeout(timer)
  }, [settled])
  if (!collapse) {
    return (
      <div {...exitingProps(exiting)} className={cn(exiting ? 'animate-fade-out' : enter && 'animate-fade-in', className)}>
        {children}
      </div>
    )
  }
  return (
    // An explicit `1fr` row: the keyframes name only the closed `0fr`, and from an implicit row
    // (`none`) there is nothing to interpolate - the height would flip at the halfway point.
    <div {...exitingProps(exiting)} className={cn('grid grid-rows-[1fr]', exiting ? 'animate-collapse-out' : enter && 'animate-collapse-in', className)}>
      <div className={cn('min-h-0', (exiting || !settled) && 'overflow-hidden')}>{children}</div>
    </div>
  )
}
