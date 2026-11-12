'use client'

import { useEffect, useRef, type RefObject } from 'react'
// The speed is the token's, `--mark-turn` (markTurn.ts): every mark turns at it.
import { IDLE_DEG_PER_S } from './markTurn'

/**
 * THE MARK TURNING IN THE HEADER AND THE SIDEBAR - at the speed every mark turns at
 * (IDLE_DEG_PER_S, the token `--mark-turn`). It was the same requestAnimationFrame loop written
 * three times: the app's header and both consoles' (the app's also carried a "boost" that nothing
 * ever set).
 *
 * Every other mark turns with the kit's `.mark-turn` class, which costs no code; this loop stays for
 * the sidebars, which draw their mark in a different element folded and open - the angle is kept
 * here, so folding does not snap the mark back to upright. A `Logo` given an `iconRef` leaves the
 * class off for that reason (an animation would override the transform written here).
 *
 * The rotation is written to the element's style each frame rather than through React state, so a
 * turning mark re-renders nothing. Still under `prefers-reduced-motion`, as `.mark-turn` is.
 */
export function useIdleRotation(ref: RefObject<Element | null>): void {
  const deg = useRef(0)
  const last = useRef<number | null>(null)

  useEffect(() => {
    if (typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return
    let raf: number
    const tick = (now: number) => {
      const dt = last.current === null ? 0 : (now - last.current) / 1000
      last.current = now
      deg.current += IDLE_DEG_PER_S * dt
      const el = ref.current as HTMLElement | SVGElement | null
      if (el) el.style.transform = `rotate(${deg.current}deg)`
      raf = requestAnimationFrame(tick)
    }
    raf = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(raf)
  }, [ref])
}
