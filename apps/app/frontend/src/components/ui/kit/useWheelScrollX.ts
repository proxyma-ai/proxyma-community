/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/useWheelScrollX.ts
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import { useEffect, type RefObject } from 'react'
import { wheelVerdict } from './wheelLatch'

// The share of the remaining distance covered each frame. About 150ms to settle on a 60Hz screen,
// short enough that the row still feels attached to the wheel.
const EASE = 0.22

/**
 * Lets a mouse wheel scroll a row that scrolls SIDEWAYS - a tab strip on a narrow window.
 *
 * A wheel only moves vertically, so a sideways row with its scrollbar hidden could not be scrolled
 * with a mouse at all: the tabs past the edge were reachable by touch and trackpad and by nothing
 * else. This turns vertical wheel movement over the row into horizontal scroll.
 *
 * SMOOTHLY, since 2026-09-26 (maintainer's request). Each notch used to jump the row by its whole
 * delta. Now a notch moves a TARGET and the row eases toward it frame by frame, so a burst of
 * notches reads as one glide rather than a stutter. Deliberately not `scrollBy({behavior:
 * 'smooth'})`: each call restarts the browser's animation from wherever the last one had got to,
 * which is the stutter again at wheel speed. Reduced motion jumps, as before.
 *
 * It stays out of the way everywhere else. When the element is not overflowing sideways - the
 * same rail laid out as a column from md up - the wheel scrolls it normally. At either end of the
 * row a NEW wheel gesture falls through to the page, but one that carried the row to its edge stays
 * with the row until it pauses, and a sideways swipe never moves the page (wheelLatch.ts, since the
 * 0.27.0 feedback - a burst of notches along a chat table scrolled the thread at the table's end).
 *
 * `enabled` is false for a region whose content is two-dimensional - a spreadsheet preview inside
 * a dialog that scrolls vertically - where the wheel has to keep scrolling ROWS. Such a region
 * still hides its bar and scrolls sideways by trackpad, shift+wheel, touch and keyboard.
 */
export function useWheelScrollX(ref: RefObject<HTMLElement | null>, enabled = true) {
  useEffect(() => {
    const el = ref.current
    if (!el || !enabled) return
    const reduced = typeof window !== 'undefined'
      && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
    let target: number | null = null
    let frame = 0

    const step = () => {
      frame = 0
      if (target === null) return
      const distance = target - el.scrollLeft
      if (Math.abs(distance) < 0.5) {
        el.scrollLeft = target
        target = null
        return
      }
      const before = el.scrollLeft
      el.scrollLeft = before + distance * EASE
      // The browser rounds scrollLeft to a device pixel, so once a step is under that it rounds
      // away and the row would never move again - nor reach the exit above - leaving the loop
      // spinning and the row short of its target. No progress means snap and stop; that also
      // ends a target the row can no longer reach because it shrank.
      if (el.scrollLeft === before) {
        el.scrollLeft = target
        target = null
        return
      }
      frame = requestAnimationFrame(step)
    }

    let latchedUntil = 0
    const onWheel = (e: WheelEvent) => {
      const verdict = wheelVerdict({
        deltaX: e.deltaX, deltaY: e.deltaY, deltaMode: e.deltaMode, ctrlKey: e.ctrlKey,
        from: target ?? el.scrollLeft, max: el.scrollWidth - el.clientWidth,
        now: e.timeStamp, latchedUntil,
      })
      if (verdict.kind === 'page') return
      e.preventDefault()
      latchedUntil = verdict.latchedUntil
      if (verdict.kind === 'hold') return
      // A trackpad's own pixels are already smooth: applied as they come, ending any glide.
      if (reduced || !verdict.ease) {
        target = null
        el.scrollLeft = verdict.to
        return
      }
      target = verdict.to
      if (!frame) frame = requestAnimationFrame(step)
    }
    // Not passive: preventDefault is what stops the page scrolling at the same time.
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => {
      el.removeEventListener('wheel', onWheel)
      if (frame) cancelAnimationFrame(frame)
    }
  }, [ref, enabled])
}
