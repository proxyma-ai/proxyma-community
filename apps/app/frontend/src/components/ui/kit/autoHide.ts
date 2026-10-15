/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/autoHide.ts
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

/**
 * WHEN THE HEADER STEPS OUT OF THE WAY ON A PHONE - the decisions, with no DOM in them.
 *
 * <p>Since 2026-10-02 (maintainer's request): a phone gives the bar an eighth of its height, and
 * a reader moving down a page does not need it until they turn back. Swiping on gives the room to
 * the content; turning back brings the bar back at once. The hooks around these are in
 * useAutoHide.ts; the arithmetic is here so `node --test` can reach it.
 *
 * <p>Two inputs, because the four surfaces scroll in two ways. The application and the consoles
 * scroll INSIDE a fixed shell, where a header that leaves resizes the very region being scrolled -
 * so its scroll position cannot be the signal (the resize moves it, which reads as the reader
 * turning back, which shows the bar, which resizes it again). There the signal is the finger. The
 * landing site scrolls the document under a fixed bar, nothing resizes, and the scroll position is
 * the plain and sufficient signal.
 */

/** A finger has to travel this far before it counts as a direction rather than a resting touch. */
export const SWIPE_STEP = 12

/**
 * What a finger that has moved `dy` pixels since the last decision says about the bar: true to
 * hide it, false to show it, null while it has not moved far enough to say. `dy` is negative when
 * the finger moves UP the screen, which is the reader moving on down the page.
 */
export function swipeVerdict(dy: number): boolean | null {
  if (dy <= -SWIPE_STEP) return true
  if (dy >= SWIPE_STEP) return false
  return null
}

/** Scroll jitter under this many pixels is not a change of direction. */
export const SCROLL_STEP = 8
/** The bar never leaves while the page is within this many pixels of its top. */
export const SCROLL_TOP_ZONE = 96

export interface ScrollState {
  hidden: boolean
  /** The scroll position the last decision was taken at. */
  lastY: number
}

/** The next state of a bar over a scrolling document, given where the document has scrolled to. */
export function scrollVerdict(state: ScrollState, y: number): ScrollState {
  const dy = y - state.lastY
  if (Math.abs(dy) <= SCROLL_STEP) return state
  return { hidden: dy > 0 && y > SCROLL_TOP_ZONE, lastY: y }
}
