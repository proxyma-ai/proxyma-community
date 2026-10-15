/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/SubmenuRow.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import type { ReactNode } from 'react'
import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Check, ChevronRight } from 'lucide-react'
import { cn } from './cn'
import { menuRow } from './menu'
import { exitingProps, usePresence } from './motion'
import { focusTrigger, useMenuFocus } from './useMenuFocus'

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
 * <p>Placed to the LEFT of the menu, because every menu that carries it is anchored to the right
 * edge of the header. Where there is no room there - a phone, whose menu nearly fills the width -
 * it opens under the row instead, over the menu, which still reads as a menu of its own.
 */

/** Width of a submenu. Wide enough for the longest language or appearance name and its check. */
const SUBMENU_WIDTH = 176
/** Gap between the menu and the submenu, and the least distance kept from the viewport edge. */
const SUBMENU_GAP = 4
const VIEWPORT_MARGIN = 8

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
    const row = rowRef.current?.getBoundingClientRect()
    const height = submenuRef.current?.offsetHeight ?? 0
    if (!row) return

    let left = row.left - SUBMENU_WIDTH - SUBMENU_GAP
    let top = row.top - SUBMENU_GAP
    if (left < VIEWPORT_MARGIN) {
      left = Math.max(VIEWPORT_MARGIN, row.right - SUBMENU_WIDTH)
      top = row.bottom + SUBMENU_GAP
    }
    top = Math.max(VIEWPORT_MARGIN, Math.min(top, window.innerHeight - height - VIEWPORT_MARGIN))
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
    document.addEventListener('pointerdown', onPointerDown)
    window.addEventListener('resize', close)
    window.addEventListener('scroll', close, true)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown)
      window.removeEventListener('resize', close)
      window.removeEventListener('scroll', close, true)
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
        <span className="flex items-center gap-2.5">
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
          {...exitingProps(exiting)}
          onPointerDown={e => e.stopPropagation()}
          onMouseDown={e => e.stopPropagation()}
          onKeyDown={onSubmenuKeyDown}
          style={{ top: position?.top ?? 0, left: position?.left ?? 0, width: SUBMENU_WIDTH }}
          className={cn(
            // No vertical padding: the panel is rounded and clips, so the first and last rows take
            // its corners instead of floating square inside them. Same rule as MenuGroup.
            'fixed z-float surface-float border border-line/45 rounded-lg shadow-card overflow-hidden animate-dropdown',
            !position && 'invisible',
            exiting && 'animate-pop-out',
          )}
        >
          {options.map(o => {
            const isCurrent = o.key === current
            const rowClass = menuRow(isCurrent ? 'active' : 'default', 'justify-between')
            const content = (
              <>
                <span className="flex items-center gap-2.5">
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
