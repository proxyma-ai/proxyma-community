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
import { scrollVerdict, type ScrollState } from './autoHide'

// The bar steps aside below `lg` only - the width from which every header shows its whole
// navigation on the bar and has the height to spare.
const WIDE = '(min-width: 1024px)'
// A touch that starts in the bar itself, in a dialog or in a menu is not the reader moving through
// the page.
const NOT_THE_PAGE = 'header, [role="dialog"], [role="menu"], [data-dialog-panel]'
// A menu open in the bar: it hangs from the bar, so the bar stays.
const OPEN_MENU = 'header [aria-expanded="true"]'


/**
 * Whether a header that sits IN THE FLOW above a scrolling region - and the dock that leaves with it - should be out of
 * the way: true while the reader scrolls on down the page, false as soon as they scroll back or come near its top, AS
 * THE SITE'S HEADER DOES (the maintainer, 2026-11-17: "scroll down the page auto hide the header ... the header in app
 * must have similar design and behavior as the landing site", "As well as the menu dock"). Until then only a finger's
 * swipe moved it, so a wheel, a trackpad or the keys never did.
 *
 * <p>The page scrolls INSIDE the shell here, and a bar that leaves resizes the region being scrolled (autoHide.ts) - so
 * two guards keep the scroll position an honest signal: only a scroll the READER makes counts (a wheel, a touch or a
 * key just before it - a chat thread following its answer down is not the reader), and for a moment after the bar
 * moves the scroll it causes is taken as the new starting point, never as a turn. `resetKey` brings the bar back when
 * it changes: pass the route, so a new page starts with its header.
 */
export function useHideOnSwipe(resetKey?: unknown): boolean {
  const [hidden, setHidden] = useState(false)
  const shown = useRef(false)

  useEffect(() => { shown.current = false; setHidden(false) }, [resetKey])

  useEffect(() => {
    const wide = window.matchMedia(WIDE)
    const positions = new WeakMap<Element, ScrollState>()
    let lastInput = -Infinity
    let settleUntil = 0

    const onInput = (e: Event) => {
      if (e instanceof KeyboardEvent && !SCROLL_KEYS.has(e.key)) return
      lastInput = performance.now()
    }
    const onScroll = (e: Event) => {
      if (wide.matches) return
      const el = e.target === document ? document.scrollingElement : e.target
      if (!(el instanceof HTMLElement) || el.closest(NOT_THE_PAGE)) return
      // Only a region that scrolls up and down: a sideways row, a code block's line, is not the page moving.
      if (el.scrollHeight <= el.clientHeight + 1) return
      const now = performance.now()
      const y = el.scrollTop
      const before = positions.get(el) ?? { hidden: shown.current, lastY: y }
      // Not the reader, or the bar's own move settling: where the page is now is where the next decision starts.
      if (now - lastInput > INPUT_MS || now < settleUntil) {
        positions.set(el, { hidden: shown.current, lastY: y })
        return
      }
      const next = scrollVerdict({ hidden: shown.current, lastY: before.lastY }, y)
      positions.set(el, next)
      if (next.hidden === shown.current) return
      if (next.hidden && document.querySelector(OPEN_MENU)) return
      shown.current = next.hidden
      settleUntil = now + SETTLE_MS
      setHidden(next.hidden)
    }
    // A window dragged wide, or a tablet turned on its side: the bar belongs there.
    const onWide = () => { if (wide.matches) { shown.current = false; setHidden(false) } }

    const passive = { passive: true, capture: true } as const
    document.addEventListener('wheel', onInput, passive)
    document.addEventListener('touchmove', onInput, passive)
    document.addEventListener('keydown', onInput, passive)
    document.addEventListener('scroll', onScroll, passive)
    wide.addEventListener('change', onWide)
    return () => {
      document.removeEventListener('wheel', onInput, passive)
      document.removeEventListener('touchmove', onInput, passive)
      document.removeEventListener('keydown', onInput, passive)
      document.removeEventListener('scroll', onScroll, passive)
      wide.removeEventListener('change', onWide)
    }
  }, [])

  return hidden
}

/** A scroll this soon after the reader's wheel, touch or key is theirs. */
const INPUT_MS = 400
/** After the bar moves, the scroll its move causes settles for this long (the kit's slow transition and a frame). */
const SETTLE_MS = 380
/** The keys that scroll a page. */
const SCROLL_KEYS = new Set(['ArrowDown', 'ArrowUp', 'PageDown', 'PageUp', 'Home', 'End', ' '])

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
