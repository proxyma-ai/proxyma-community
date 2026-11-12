'use client'

import type { ReactNode } from 'react'
import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Check, ChevronRight } from 'lucide-react'
import { cn } from './cn'
import { menuRow } from './menu'
import { exitingProps, usePresence } from './motion'
import { focusTrigger, useMenuFocus } from './useMenuFocus'
import { EDGE } from './placement'

/**
 * One menu row - the current value and a chevron - that opens a small submenu listing every choice.
 *
 * <p>IN THE KIT SINCE 2026-10-02. It was the application's own (PreferenceMenuRows.tsx), and the
 * landing site's header carried the same two preferences as two separate icon menus on the bar;
 * the maintainer asked for the site to have the app's small menu. One row shape for both is this.
 *
 * <p>Language was one row PER language until 2026-09-15, which is two rows now and a row for every
 * language ever added, in a menu that already runs to eleven. The maintainer asked for the
 * cascading shape desktop menus use.
 *
 * <p>THE SUBMENU IS PORTALED TO THE BODY, and has to be. Every menu panel it can open from clips
 * its contents - MENU_PANEL scrolls, so a phone on its side can reach the last row, and the app's
 * own panel is `overflow-hidden` for its rounded corners - and `position: fixed` does not escape
 * either, because the dropdown animation sets a transform, which makes the panel the containing
 * block for fixed descendants. A submenu drawn inside the panel is cut off at its edge.
 *
 * <p>Portaling breaks one thing, and it is handled: the menus close on a press OUTSIDE their own
 * root, and the portal is outside it in the DOM. So the submenu stops its own pointerdown and
 * mousedown from reaching the document - React listens on a portal's container, so the stop
 * happens before the menus' document listeners run. Without it, choosing an item would close
 * the menu on the way down and unmount the option before the click landed.
 *
 * <p>Placed BESIDE THE MENU'S PANEL, not the row: to its LEFT, because every menu that carries it is
 * anchored to the right edge of the header, else to its right, 4px from the panel's edge either way
 * (the kit's gap between a floating panel and its anchor, placement.ts). Its FIRST ROW is level with
 * the row that opened it. Where neither side has room - a phone, whose menu nearly fills the width -
 * it opens under the row instead, over the menu, which still reads as a menu of its own. It was
 * measured from the ROW until 2026-11-02: once the rows were inset 4px from the panel (2026-10-30)
 * that put the submenu's edge on the panel's, with no gap, and its first row 5px above the row (the
 * maintainer: "Incorrect margins in this list").
 */

/** Width of a submenu. Wide enough for the longest language or appearance name and its check. */
const SUBMENU_WIDTH = 176
/** Gap between the menu and the submenu - placement.ts's, between any floating panel and its anchor. */
const SUBMENU_GAP = 4

export interface SubmenuOption<K extends string> {
  key: K
  label: ReactNode
  icon?: ReactNode
  lang?: string
}

/** What `renderOption` is handed: the option, whether it is the current one, and the row to draw. */
export interface SubmenuOptionRender<K extends string> {
  option: SubmenuOption<K>
  current: boolean
  /** The row's classes - geometry and tone - for whatever element the caller renders. */
  className: string
  /** The row's content: icon, label, and the check on the current one. */
  children: ReactNode
  /** Closes the submenu. */
  close: () => void
}

export interface SubmenuRowProps<K extends string> {
  /** The row's own glyph. */
  icon: ReactNode
  /** The current choice, as the row shows it. */
  value: ReactNode
  /** Already translated. Names the submenu and prefixes the row's accessible name. */
  title: string
  /** The current choice, as plain text for the row's accessible name. */
  valueText: string
  valueLang?: string
  options: readonly SubmenuOption<K>[]
  current: K
  /** A choice was made. Not called for an option drawn by `renderOption`, which acts for itself. */
  onPick?: (key: K) => void
  /** Whether a pick also closes the submenu. */
  closeOnPick: boolean
  /**
   * Draws one option as something other than a button - the landing site's languages are ANCHORS,
   * because there a language is a URL, and a link can be opened in a new tab or followed by a
   * crawler where a button cannot.
   */
  renderOption?: (render: SubmenuOptionRender<K>) => ReactNode
  className?: string
}

export function SubmenuRow<K extends string>({
  icon, value, title, valueText, valueLang, options, current, onPick, closeOnPick, renderOption, className,
}: SubmenuRowProps<K>) {
  const [open, setOpen] = useState(false)
  const [position, setPosition] = useState<{ top: number; left: number } | null>(null)
  const rowRef = useRef<HTMLButtonElement>(null)
  const submenuRef = useRef<HTMLDivElement>(null)
  // Kept for its exit, in place: the position is dropped once it is gone.
  const { mounted, exiting } = usePresence(open)

  // Measured after the submenu renders (hidden, at no position) so its real height decides whether
  // it fits, rather than a row count that stops being right the day a row changes height.
  useLayoutEffect(() => {
    if (!open) { if (!mounted) setPosition(null); return }
    const rowEl = rowRef.current
    const submenu = submenuRef.current
    if (!rowEl || !submenu) return
    const row = rowEl.getBoundingClientRect()
    // The menu's panel: what the submenu stands beside. A row outside any marked panel stands for it.
    const panel = rowEl.closest('[data-menu-panel]')?.getBoundingClientRect() ?? row
    const height = submenu.offsetHeight
    // From the submenu's outer edge to its first row - its border and its padding, read from layout
    // (offsetTop ignores the opening animation's transform), so the two rows line up whatever they are.
    const firstRow = submenu.querySelector<HTMLElement>('[role^="menuitem"]')
    const inset = submenu.clientTop + (firstRow?.offsetTop ?? 0)

    const vw = window.innerWidth
    const roomLeft = panel.left - SUBMENU_GAP - EDGE
    const roomRight = vw - panel.right - SUBMENU_GAP - EDGE
    let left: number
    let top = row.top - inset
    if (roomLeft >= SUBMENU_WIDTH) left = panel.left - SUBMENU_GAP - SUBMENU_WIDTH
    else if (roomRight >= SUBMENU_WIDTH) left = panel.right + SUBMENU_GAP
    else {
      left = Math.min(Math.max(EDGE, row.right - SUBMENU_WIDTH), vw - SUBMENU_WIDTH - EDGE)
      top = row.bottom + SUBMENU_GAP
    }
    top = Math.max(EDGE, Math.min(top, window.innerHeight - height - EDGE))
    setPosition({ top, left })
  }, [open, mounted])

  // A press anywhere but the row or the submenu closes the submenu - including another row of the
  // same menu, which the menu itself would not treat as outside. Scrolling or resizing closes it
  // too, rather than leaving it fixed in place while the row it belongs to moves away.
  useEffect(() => {
    if (!open) return
    const onPointerDown = (e: PointerEvent) => {
      const target = e.target as Node
      if (rowRef.current?.contains(target) || submenuRef.current?.contains(target)) return
      setOpen(false)
    }
    const close = () => setOpen(false)
    // A scroll inside a floating panel - this submenu or the menu it hangs from - moves nothing it
    // is anchored to, so only a scroll elsewhere closes it (as the kit's Popover does).
    const onScroll = (e: Event) => {
      if (e.target instanceof Element && e.target.closest('[data-floating-panel]')) return
      setOpen(false)
    }
    document.addEventListener('pointerdown', onPointerDown)
    window.addEventListener('resize', close)
    window.addEventListener('scroll', onScroll, true)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown)
      window.removeEventListener('resize', close)
      window.removeEventListener('scroll', onScroll, true)
    }
  }, [open])

  // The menus' keyboard: opened from the keyboard, focus moves onto the current choice; arrows and
  // typing move through the choices; Escape, Tab or the arrow back toward the row close it and give
  // focus back to the row - the menu that holds the row stays open.
  const onMenuKeyDown = useMenuFocus(submenuRef, { open, ready: position !== null, triggerRef: rowRef, close: () => setOpen(false) })
  const onSubmenuKeyDown = (e: React.KeyboardEvent<HTMLElement>) => {
    if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
      e.preventDefault()
      e.stopPropagation()
      setOpen(false)
      focusTrigger(rowRef.current)
      return
    }
    onMenuKeyDown(e)
  }

  return (
    <>
      <button
        ref={rowRef}
        type="button"
        onClick={() => setOpen(o => !o)}
        // The arrow toward the submenu opens it, as in a desktop menu bar.
        onKeyDown={e => {
          if ((e.key === 'ArrowLeft' || e.key === 'ArrowRight') && !open) { e.preventDefault(); e.stopPropagation(); setOpen(true) }
        }}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={`${title}: ${valueText}`}
        className={cn(className ?? menuRow(), 'justify-between')}
      >
        <span className="flex items-center gap-2">
          {icon}
          <span lang={valueLang}>{value}</span>
        </span>
        <ChevronRight size={14} className="shrink-0" aria-hidden="true" />
      </button>

      {mounted && createPortal(
        <div
          ref={submenuRef}
          role="menu"
          aria-label={title}
          data-floating-panel=""
          {...exitingProps(exiting)}
          onPointerDown={e => e.stopPropagation()}
          onMouseDown={e => e.stopPropagation()}
          onKeyDown={onSubmenuKeyDown}
          style={{ top: position?.top ?? 0, left: position?.left ?? 0, width: SUBMENU_WIDTH }}
          className={cn(
            // 4px above the first row and below the last, as MenuGroup gives the menu's: the rows are
            // rounded and inset 4px from the sides (menuRow), so the selected one sits in the panel
            // as the menu's own does.
            'fixed z-float surface-float border rounded-lg shadow-float overflow-hidden animate-dropdown py-1',
            !position && 'invisible',
            exiting && 'animate-pop-out',
          )}
        >
          {options.map(o => {
            const isCurrent = o.key === current
            const rowClass = menuRow(isCurrent ? 'active' : 'default', 'justify-between')
            const content = (
              <>
                <span className="flex items-center gap-2">
                  {o.icon}
                  {o.label}
                </span>
                {isCurrent && <Check size={14} aria-hidden="true" />}
              </>
            )
            if (renderOption) {
              return (
                <span key={o.key} className="contents">
                  {renderOption({ option: o, current: isCurrent, className: rowClass, children: content, close: () => setOpen(false) })}
                </span>
              )
            }
            return (
              <button
                key={o.key}
                type="button"
                lang={o.lang}
                role="menuitemradio"
                aria-checked={isCurrent}
                onClick={() => { onPick?.(o.key); if (closeOnPick) setOpen(false) }}
                className={rowClass}
              >
                {content}
              </button>
            )
          })}
        </div>,
        document.body,
      )}
    </>
  )
}
