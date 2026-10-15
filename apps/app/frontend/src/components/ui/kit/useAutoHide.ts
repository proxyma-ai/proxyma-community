/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/useAutoHide.ts
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import { useEffect, useRef, useState } from 'react'
import { scrollVerdict, swipeVerdict } from './autoHide'

// The bar steps aside below `lg` only - the width from which every header shows its whole
// navigation on the bar and has the height to spare.
const WIDE = '(min-width: 1024px)'
// A touch that starts in the bar itself, in a dialog or in a menu is not the reader moving through
// the page.
const NOT_THE_PAGE = 'header, [role="dialog"], [role="menu"], [data-dialog-panel]'
// A menu open in the bar: it hangs from the bar, so the bar stays.
const OPEN_MENU = 'header [aria-expanded="true"]'

// The OUTERMOST ancestor that really scrolls up and down - the page's own scroller, not a code
// block or a capped panel inside it.
function pageScroller(from: Element): HTMLElement | null {
  let found: HTMLElement | null = null
  for (let el: Element | null = from; el && el !== document.body; el = el.parentElement) {
    if (!(el instanceof HTMLElement) || el.scrollHeight <= el.clientHeight + 1) continue
    const overflowY = getComputedStyle(el).overflowY
    if (overflowY === 'auto' || overflowY === 'scroll') found = el
  }
  return found
}

/**
 * Whether a header that sits IN THE FLOW above a scrolling region should be out of the way: true
 * after the reader swipes on down the page, false again as soon as they swipe back. For a shell
 * whose content scrolls inside it - see autoHide.ts for why the finger is the signal there.
 *
 * <p>`resetKey` brings the bar back when it changes: pass the route, so a new page starts with its
 * header.
 */
export function useHideOnSwipe(resetKey?: unknown): boolean {
  const [hidden, setHidden] = useState(false)

  useEffect(() => { setHidden(false) }, [resetKey])

  useEffect(() => {
    const wide = window.matchMedia(WIDE)
    let startY = 0
    let tracking = false

    const onStart = (e: TouchEvent) => {
      tracking = false
      if (wide.matches || e.touches.length !== 1) return
      const target = e.target instanceof Element ? e.target : null
      // Only a page that scrolls has anything to gain from a bar that leaves.
      if (!target || target.closest(NOT_THE_PAGE) || !pageScroller(target)) return
      startY = e.touches[0].clientY
      tracking = true
    }
    const onMove = (e: TouchEvent) => {
      if (!tracking || e.touches.length !== 1) return
      const y = e.touches[0].clientY
      const verdict = swipeVerdict(y - startY)
      if (verdict === null) return
      startY = y
      if (verdict && document.querySelector(OPEN_MENU)) return
      setHidden(verdict)
    }
    // A window dragged wide, or a tablet turned on its side: the bar belongs there.
    const onWide = () => { if (wide.matches) setHidden(false) }

    document.addEventListener('touchstart', onStart, { passive: true })
    document.addEventListener('touchmove', onMove, { passive: true })
    wide.addEventListener('change', onWide)
    return () => {
      document.removeEventListener('touchstart', onStart)
      document.removeEventListener('touchmove', onMove)
      wide.removeEventListener('change', onWide)
    }
  }, [])

  return hidden
}

/**
 * The same for a header FIXED over a document that scrolls under it: true while the reader is
 * moving down the page, false as they turn back or come near the top. The caller translates the
 * bar away and keeps it still from `lg` up (`lg:translate-y-0`).
 */
export function useHideOnScroll(): boolean {
  const [hidden, setHidden] = useState(false)
  const state = useRef({ hidden: false, lastY: 0 })

  useEffect(() => {
    state.current = { hidden: false, lastY: window.scrollY }
    const onScroll = () => {
      const next = scrollVerdict(state.current, window.scrollY)
      if (next === state.current) return
      if (next.hidden && document.querySelector(OPEN_MENU)) return
      state.current = next
      setHidden(next.hidden)
    }
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => window.removeEventListener('scroll', onScroll)
  }, [])

  return hidden
}
