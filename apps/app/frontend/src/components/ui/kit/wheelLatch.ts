/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/wheelLatch.ts
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

/**
 * WHERE A WHEEL EVENT OVER A SIDEWAYS ROW GOES - the decision, with no DOM in it.
 *
 * <p>useWheelScrollX turns vertical wheel movement over a row that scrolls sideways (a tab strip, a
 * wide table in the chat) into horizontal scroll. At either end of the row the wheel used to fall
 * through to the page at once, so a burst of notches that carried the row to its edge carried on
 * scrolling the THREAD - the reader, reading a table, lost their place (the 0.27.0 feedback).
 *
 * <p>So a wheel gesture is LATCHED to the row it started on, the way a desktop OS latches a scroll
 * gesture to one scroller: while the notches keep coming, the row keeps them, even at its edge.
 * A pause of LATCH_MS ends the gesture, and a new one that starts at the edge goes to the page - so
 * the page can still be scrolled past a wide table with the pointer over it, which swallowing the
 * wheel outright would not allow.
 *
 * <p>A SIDEWAYS gesture (a trackpad swipe, a tilt wheel) never moves the page: the row takes its
 * horizontal part and its small vertical drift is dropped. Left to the browser, that drift chained
 * to the thread and nudged it up and down while the reader swiped along a table.
 */

/** Lines are what Firefox reports for a notched wheel; a line is about this many pixels. */
export const LINE_PX = 16
/** A gesture ends after this long with no wheel event. Notches in one turn arrive well inside it. */
export const LATCH_MS = 250

export interface WheelInput {
  deltaX: number
  deltaY: number
  /** 0 pixels, 1 lines, as WheelEvent.deltaMode. */
  deltaMode: number
  ctrlKey: boolean
  /** Where the row is heading: an easing target if one is running, else its scrollLeft. */
  from: number
  /** scrollWidth - clientWidth; 0 or less when the row does not overflow. */
  max: number
  /** The event's time, and the time the current gesture is latched until. */
  now: number
  latchedUntil: number
}

export type WheelVerdict =
  /** Not the row's: the page (or the browser) handles it. */
  | { kind: 'page' }
  /** The row's, but it is at its edge: swallowed so the page does not move, the gesture goes on. */
  | { kind: 'hold'; latchedUntil: number }
  /** Scroll the row to `to`; `ease` for a wheel notch, a direct jump for a trackpad's own pixels. */
  | { kind: 'scroll'; to: number; ease: boolean; latchedUntil: number }

export function wheelVerdict(e: WheelInput): WheelVerdict {
  if (e.ctrlKey) return { kind: 'page' } // pinch and ctrl+wheel zoom
  if (e.max <= 0) return { kind: 'page' } // not overflowing: an ordinary block
  const scale = e.deltaMode === 1 ? LINE_PX : 1
  const sideways = Math.abs(e.deltaX) > Math.abs(e.deltaY)
  const delta = (sideways ? e.deltaX : e.deltaY) * scale
  if (delta === 0) return { kind: 'page' }
  const latched = e.now < e.latchedUntil
  const atEdge = (delta < 0 && e.from <= 0) || (delta > 0 && e.from >= e.max - 1)
  const latchedUntil = e.now + LATCH_MS
  if (atEdge) {
    // A sideways gesture has nowhere else to go - the thread does not scroll sideways, and its
    // vertical drift is not the reader asking to move the page.
    if (sideways || latched) return { kind: 'hold', latchedUntil }
    return { kind: 'page' }
  }
  const to = Math.max(0, Math.min(e.max, e.from + delta))
  return { kind: 'scroll', to, ease: !sideways, latchedUntil }
}
