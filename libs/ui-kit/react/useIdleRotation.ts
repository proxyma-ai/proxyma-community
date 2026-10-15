'use client'

import { useEffect, useRef, type RefObject } from 'react'

/** The mark's idle turn: 30 degrees a second, one turn in 12 seconds, in all four apps. */
export const IDLE_DEG_PER_S = 30

/**
 * THE MARK TURNING IN THE HEADER - one of the two things in the design system that move without
 * being asked. It was the same requestAnimationFrame loop written three times: the app's header
 * and both consoles' (the app's also carried a "boost" that nothing ever set).
 *
 * The rotation is written to the element's style each frame rather than through React state, so a
 * turning mark re-renders nothing. Still under `prefers-reduced-motion`, as the site's footer mark
 * is (`motion-safe:`).
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
