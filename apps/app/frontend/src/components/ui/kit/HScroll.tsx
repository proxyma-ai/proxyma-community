/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/HScroll.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import { useEffect, useRef, useState, type HTMLAttributes, type ReactNode } from 'react'
import { cn } from './cn'
import { useWheelScrollX } from './useWheelScrollX'

export interface HScrollProps extends HTMLAttributes<HTMLDivElement> {
  children: ReactNode
  /**
   * Whether a vertical mouse wheel scrolls this region sideways (`useWheelScrollX`). Leave it on
   * for a row, a command or a table that sits in a page; turn it off for two-dimensional content
   * inside a region that scrolls vertically - a spreadsheet preview - where the wheel has to keep
   * moving rows. Trackpad, shift+wheel, touch and the keyboard scroll it either way.
   */
  wheel?: boolean
  /**
   * Whether the region takes keyboard focus while it overflows, so the arrow keys can scroll it.
   * Off for a row of buttons - a tab strip - where tabbing to a button already scrolls it into
   * view and one more tab stop would only be in the way.
   */
  focusable?: boolean
}

/**
 * A region that scrolls SIDEWAYS without showing a scrollbar - a command line, a table, a tab row.
 *
 * <p>THE MAINTAINER'S RULE, 2026-09-30: no horizontal scrollbars anywhere. A 6px bar under a
 * one-line command or a table is chrome that says nothing the content cannot say itself, and on
 * Windows it is the one scrollbar people notice. Vertical scrollbars stay - the global thin one.
 *
 * <p>So the bar is hidden (`scrollbar-hide`) and three things replace it, all here so no call site
 * can forget one:
 *
 * <ul>
 *   <li>a mouse wheel scrolls it sideways (`useWheelScrollX`, smoothly, falling through to the
 *       page at either end) - the same behavior the tab rows had;</li>
 *   <li>while it overflows it is a tab stop, so the arrow keys scroll it (`focusable`);</li>
 *   <li>an EDGE FADE on whichever side content is cut off (`.hscroll-fade`, driven by the
 *       `data-cut-start` / `data-cut-end` attributes set below). Without a bar, a table cut at a
 *       column boundary reads as a table with fewer columns. The fade is a MASK rather than a
 *       gradient overlay, so it works on any background - a card, a code block, a dialog - without
 *       knowing which color to fade to.</li>
 * </ul>
 *
 * <p>Tables keep `overflow-x-auto` - this is it - as `.claude/rules/frontend.md` requires: inside a
 * clipping card, a table that cannot scroll hides its last columns entirely.
 */
export function HScroll({
  children, className, wheel = true, focusable = true, ...rest
}: HScrollProps) {
  const ref = useRef<HTMLDivElement>(null)
  const [cut, setCut] = useState({ start: false, end: false })
  useWheelScrollX(ref, wheel)

  useEffect(() => {
    const el = ref.current
    if (!el) return
    const measure = () => {
      const max = el.scrollWidth - el.clientWidth
      // One pixel of slack: scrollLeft is fractional on a scaled display and never quite reaches max.
      const start = max > 1 && el.scrollLeft > 1
      const end = max > 1 && el.scrollLeft < max - 1
      setCut(c => (c.start === start && c.end === end ? c : { start, end }))
    }
    measure()
    el.addEventListener('scroll', measure, { passive: true })
    // The box and its content both decide whether it overflows: a window resize changes the first,
    // a table's rows arriving change the second.
    const observer = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(measure) : null
    if (observer) {
      observer.observe(el)
      for (const child of Array.from(el.children)) observer.observe(child)
    }
    return () => {
      el.removeEventListener('scroll', measure)
      observer?.disconnect()
    }
  }, [children])

  const overflowing = cut.start || cut.end
  return (
    <div
      ref={ref}
      tabIndex={focusable && overflowing ? 0 : undefined}
      data-cut-start={cut.start || undefined}
      data-cut-end={cut.end || undefined}
      className={cn(
        'overflow-x-auto scrollbar-hide hscroll-fade',
        // Inset, because the edge fade is a mask and would erase a ring drawn outside the box.
        focusable && 'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-solid',
        className,
      )}
      {...rest}
    >
      {children}
    </div>
  )
}
