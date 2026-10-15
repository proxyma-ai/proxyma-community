'use client'

import type { ReactNode } from 'react'
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Check, ChevronRight, Lock, MoreHorizontal } from 'lucide-react'
import { cn } from './cn'
import { iconButton, type IconButtonSize } from './iconButton'
import { place, type Place, type Placement } from './floating'
import { MenuGroup, menuRow, type MenuTone } from './menu'
import { exitingProps, usePresence } from './motion'
import { useMenuFocus } from './useMenuFocus'

export type { Placement } from './floating'

/** One layer for every floating panel, above dialogs (a menu opened inside a dialog must be on top). */
export const FLOATING_Z = 'z-float'

/** The panel of every popover and menu - `MENU_PANEL`'s surface, `border-line/45`, `rounded-lg`. */
export const POPOVER_PANEL = 'surface-float border border-line/45 rounded-lg shadow-card overflow-hidden animate-dropdown'

export interface PopoverProps {
  /** The anchor. Give it `aria-expanded={open}` and `aria-haspopup` as fits. */
  trigger: (args: { open: boolean; toggle: () => void; ref: (el: HTMLElement | null) => void }) => ReactNode
  /** The panel's content; `close` dismisses it. */
  children: (close: () => void) => ReactNode
  placement?: Placement
  /** Panel width class - `w-56` by default, the menus' width. */
  widthClass?: string
  panelClassName?: string
  role?: 'menu' | 'dialog' | 'listbox'
  ariaLabel?: string
  /** Controlled open state, for a panel the page opens by itself (a mention list as you type). */
  open?: boolean
  onOpenChange?: (open: boolean) => void
  /** Close on a scroll outside the panel - the anchor has moved away. On for menus in lists. */
  closeOnScroll?: boolean
  /**
   * The menus' keyboard (`useMenuFocus`) - on for `role="menu"`, off otherwise. Off for a list a
   * text field drives itself while the person types (the composer's mentions).
   */
  menuKeys?: boolean
}

/**
 * A FLOATING PANEL ANCHORED TO A TRIGGER, portaled to the body. The kit's `MenuSurface` positions
 * its panel absolutely under the trigger, which is right in a header and wrong in anything that
 * clips or scrolls - a dialog's list, a composer at the bottom of the window. Five panels in chat
 * and three in Sources were built by hand for that reason, each with its own outside-click and
 * none with the same Escape contract. This is the one: outside pointer and Escape close it, Escape
 * is marked handled (`preventDefault`) so the dialog beneath stays open, and it flips to fit.
 *
 * A `menu` (the default role) has the menus' keyboard, `useMenuFocus`: opened from the keyboard,
 * focus moves onto the checked row or the first; the arrows, Home/End and typing move between
 * rows; Escape and Tab close it and return focus to the trigger. A panel the page drives from a
 * text field (the composer's mention list, whose editor keeps the arrows) passes `menuKeys={false}`.
 */
export function Popover({
  trigger, children, placement = 'bottom-end', widthClass = 'w-56', panelClassName, role = 'menu', ariaLabel,
  open: controlled, onOpenChange, closeOnScroll = true, menuKeys,
}: PopoverProps) {
  const [own, setOwn] = useState(false)
  const open = controlled ?? own
  const setOpen = useCallback((v: boolean) => { if (controlled === undefined) setOwn(v); onOpenChange?.(v) }, [controlled, onOpenChange])
  const [pos, setPos] = useState<Place | null>(null)
  const anchorRef = useRef<HTMLElement | null>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  // Kept for its exit, where it stays where it was: the position is dropped only once it is gone.
  const { mounted, exiting } = usePresence(open)

  const measure = useCallback(() => {
    const a = anchorRef.current
    const p = panelRef.current
    if (!a) return
    setPos(place(a.getBoundingClientRect(), { width: p?.offsetWidth ?? 224, height: p?.offsetHeight ?? 0 }, placement))
  }, [placement])

  useLayoutEffect(() => {
    if (open) measure()
    else if (!mounted) setPos(null)
  }, [open, mounted, measure])

  useEffect(() => {
    if (!open) return
    const onPointerDown = (e: PointerEvent) => {
      const t = e.target as Node
      if (anchorRef.current?.contains(t) || panelRef.current?.contains(t)) return
      setOpen(false)
    }
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !e.defaultPrevented) { e.preventDefault(); setOpen(false); anchorRef.current?.focus() }
    }
    // Focusing a half-hidden trigger scrolls it into view; that scroll must not shut the panel the
    // same click opened.
    const openedAt = performance.now()
    const onScroll = (e: Event) => {
      if (panelRef.current?.contains(e.target as Node)) return
      if (!closeOnScroll || performance.now() - openedAt < 300) { measure(); return }
      setOpen(false)
    }
    document.addEventListener('pointerdown', onPointerDown)
    document.addEventListener('keydown', onKeyDown)
    window.addEventListener('scroll', onScroll, true)
    window.addEventListener('resize', measure)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown)
      window.removeEventListener('scroll', onScroll, true)
      window.removeEventListener('resize', measure)
    }
  }, [open, measure, setOpen, closeOnScroll])

  const onMenuKeyDown = useMenuFocus(panelRef, {
    open,
    ready: pos !== null,
    triggerRef: anchorRef,
    close: () => setOpen(false),
    enabled: menuKeys ?? role === 'menu',
  })

  return (
    <>
      {trigger({ open, toggle: () => setOpen(!open), ref: el => { anchorRef.current = el } })}
      {mounted && createPortal(
        <div
          ref={panelRef}
          role={role}
          aria-label={ariaLabel}
          {...exitingProps(exiting)}
          // Which way it opened, so it grows out of (and shrinks back into) its anchor's side.
          data-side={pos?.bottom !== undefined ? 'top' : 'bottom'}
          style={pos ?? { visibility: 'hidden', top: 0, left: 0 }}
          // The scroll comes AFTER POPOVER_PANEL: its `overflow-hidden` is a later `overflow`, which
          // twMerge lets drop an earlier `overflow-y-auto`, and every capped panel then clipped its
          // rows - a long @ list could be neither scrolled nor arrowed through (2026-10-07).
          className={cn('fixed max-w-[calc(100vw-1rem)] max-h-[min(24rem,var(--float-room,24rem))]', FLOATING_Z, POPOVER_PANEL, 'overflow-y-auto', widthClass, panelClassName, exiting && 'animate-pop-out')}
          onClick={e => e.stopPropagation()}
          onKeyDown={onMenuKeyDown}
        >
          {children(() => setOpen(false))}
        </div>,
        document.body,
      )}
    </>
  )
}

/**
 * A menu row with a glyph, a title, an optional description line, and a trailing mark - a check
 * for the chosen one, a chevron for a submenu, a lock for something the plan does not include.
 * Built on `menuRow()`, so it is every other menu's row. `tone="brand"` is "Create new...".
 */
export function MenuRow({ icon, title, description, trailing, tone = 'default', onSelect, disabled, role = 'menuitem' }: {
  icon?: ReactNode
  title: ReactNode
  description?: ReactNode
  trailing?: 'check' | 'chevron' | 'lock' | ReactNode
  tone?: MenuTone | 'brand' | 'locked'
  onSelect: () => void
  disabled?: boolean
  role?: 'menuitem' | 'menuitemradio' | 'option'
}) {
  const kitTone: MenuTone = tone === 'brand' || tone === 'locked' ? 'default' : tone
  return (
    <button
      type="button"
      role={role}
      aria-checked={role === 'menuitemradio' ? trailing === 'check' : undefined}
      disabled={disabled}
      onClick={onSelect}
      className={cn(
        menuRow(kitTone),
        'disabled:opacity-50 disabled:pointer-events-none',
        description && 'items-start',
        tone === 'brand' && 'text-brand-legible mouse:hover:text-brand-legible',
        tone === 'locked' && 'text-ink-disabled',
      )}
    >
      {icon && <span className={cn('shrink-0 flex items-center text-ink-caption [&>svg]:w-4 [&>svg]:h-4', description && 'mt-0.5', tone === 'brand' && 'text-brand-legible')}>{icon}</span>}
      <span className="flex-1 min-w-0">
        <span className="block truncate">{title}</span>
        {description && <span className="block text-xs text-ink-caption whitespace-normal leading-relaxed">{description}</span>}
      </span>
      {trailing === 'check' ? <Check size={14} className="shrink-0 text-brand-legible" aria-hidden="true" />
        : trailing === 'chevron' ? <ChevronRight size={14} className="shrink-0 text-ink-caption" aria-hidden="true" />
          : trailing === 'lock' ? <Lock size={12} className="shrink-0 text-ink-disabled" aria-hidden="true" />
            : trailing}
    </button>
  )
}

/** The line a menu shows when it has no rows ("No skills yet"). */
export function MenuEmpty({ children }: { children: ReactNode }) {
  return <p className="px-4 py-2.5 text-xs text-ink-caption">{children}</p>
}

export interface ActionMenuItem {
  label: string
  icon?: ReactNode
  onSelect: () => void
  danger?: boolean
  disabled?: boolean
}

/** Groups of rows, divided by hairlines. A falsy entry is dropped, so call sites can inline conditions. */
export type ActionMenuGroups = Array<Array<ActionMenuItem | false | null | undefined>>

/**
 * THE "MORE" MENU - a row's or a dialog header's overflow: an icon-only trigger (`MoreHorizontal`)
 * and a Popover of grouped rows. It was the source dialog's own, the only hand-made menu that got
 * placement and Escape right, so it is everybody's now.
 */
export function ActionMenu({ label, groups, size = 'md', placement = 'bottom-end' }: {
  /** The trigger's accessible name, translated. */
  label: string
  groups: ActionMenuGroups
  size?: IconButtonSize
  placement?: Placement
}) {
  const visible = groups.map(g => g.filter((i): i is ActionMenuItem => !!i)).filter(g => g.length > 0)
  if (visible.length === 0) return null
  return (
    <Popover
      placement={placement}
      ariaLabel={label}
      trigger={({ open, toggle, ref }) => (
        <button
          ref={ref}
          type="button"
          aria-label={label}
          aria-haspopup="menu"
          aria-expanded={open}
          onClick={e => { e.stopPropagation(); toggle() }}
          className={open ? cn(iconButton(size, null), 'text-ink-primary') : iconButton(size)}
        >
          <MoreHorizontal size={16} aria-hidden="true" />
        </button>
      )}
    >
      {close => visible.map((group, gi) => (
        <MenuGroup key={gi}>
          {group.map(item => (
            <MenuRow
              key={item.label}
              icon={item.icon}
              title={item.label}
              tone={item.danger ? 'danger' : 'default'}
              disabled={item.disabled}
              onSelect={() => { close(); item.onSelect() }}
            />
          ))}
        </MenuGroup>
      ))}
    </Popover>
  )
}
