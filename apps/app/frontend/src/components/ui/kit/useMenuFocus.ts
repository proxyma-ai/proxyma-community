/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/useMenuFocus.ts
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import { useCallback, useEffect, useRef, type KeyboardEvent as ReactKeyboardEvent, type RefObject } from 'react'
import { createTypeahead, isNavKey, isTypeaheadKey, moveIndex, typeaheadIndex } from './listNav'

/**
 * THE KEYBOARD OF EVERY MENU - `Popover` menus and `ActionMenu`, `MenuSurface` (the header's account
 * and phone menus), `SubmenuRow`'s submenu and `IconMenu`. The menu-button pattern (WAI-ARIA APG):
 *
 * - OPENED FROM THE KEYBOARD, focus moves onto the chosen row (a checked one) or the first row.
 *   Opened by a pointer, focus stays on the trigger - and an arrow key from there enters the menu.
 *   A text field that opens a list as you type (the composer's mentions) is never robbed of focus.
 * - Inside: arrows (wrapping), Home and End, typeahead; Space acts on a row that is a link.
 * - Escape and Tab close it and give focus back to the trigger, and so does choosing a row.
 *
 * The rows are found, not registered: anything with a menu or option role, or a link or button,
 * inside the panel - the rows of these menus are buttons in one app, router links in another and
 * anchors on the site, and none of them can be asked to call a hook. The decisions are `listNav.ts`.
 *
 * Returns the panel's `onKeyDown`. Keys it handles are stopped there, because a submenu is a PORTAL
 * whose events still bubble through the React tree into the menu that holds its row.
 */
export interface MenuFocusOptions {
  open: boolean
  /** The panel is laid out and visible - a portaled panel is measured before it is shown. */
  ready?: boolean
  /** The trigger: where focus returns, and where an arrow key enters the menu from. */
  triggerRef: RefObject<HTMLElement | null>
  /** Closes the menu. */
  close: () => void
  /** False for a list the page drives itself from a text field. */
  enabled?: boolean
}

const ITEM = '[role="menuitem"],[role="menuitemradio"],[role="menuitemcheckbox"],[role="option"],a[href],button'
const CHOSEN = '[aria-checked="true"],[aria-current="true"],[aria-current="page"],[aria-selected="true"]'

/** The rows of a menu panel that can take focus, in order. */
export function menuItems(panel: HTMLElement): HTMLElement[] {
  const seen = new Set<HTMLElement>()
  return Array.from(panel.querySelectorAll<HTMLElement>(ITEM)).filter(el => {
    if (seen.has(el)) return false
    seen.add(el)
    // A row inside a row (an action button inside a link row) is reached by Tab, not by arrows.
    const outer = el.parentElement?.closest(ITEM)
    if (outer && panel.contains(outer)) return false
    if ((el as HTMLButtonElement).disabled || el.getAttribute('aria-disabled') === 'true') return false
    if (el.closest('[inert]')) return false
    return el.getClientRects().length > 0
  })
}

/** Whether focus on `el` came from the keyboard and is not a text field typing into the list. */
function keyboardFocused(el: Element | null): boolean {
  if (!(el instanceof HTMLElement)) return false
  if (el.isContentEditable || el instanceof HTMLTextAreaElement) return false
  if (el instanceof HTMLInputElement && !['button', 'submit', 'checkbox', 'radio'].includes(el.type)) return false
  try { return el.matches(':focus-visible') } catch { return false }
}

function focusRow(el: HTMLElement | undefined) {
  el?.focus({ preventScroll: false })
}

/**
 * Focus back on the trigger - or on the control inside it, where the anchor is a wrapper (a row
 * whose submenu hangs off a div around it).
 */
export function focusTrigger(trigger: HTMLElement | null) {
  if (!trigger?.isConnected) return
  const target = trigger.matches('button, a[href], input, [tabindex]:not([tabindex="-1"])')
    ? trigger
    : trigger.querySelector<HTMLElement>('button, a[href], input, [tabindex]:not([tabindex="-1"])')
  ;(target ?? trigger).focus({ preventScroll: true })
}

export function useMenuFocus(
  panelRef: RefObject<HTMLElement | null>,
  { open, ready = true, triggerRef, close, enabled = true }: MenuFocusOptions,
): (e: ReactKeyboardEvent<HTMLElement>) => void {
  const typeahead = useRef(createTypeahead())
  const entered = useRef(false)
  const closeRef = useRef(close)
  closeRef.current = close

  // Into the menu, once, when it opened from the keyboard.
  useEffect(() => {
    if (!open) { entered.current = false; return }
    if (!enabled || !ready || entered.current) return
    entered.current = true
    typeahead.current.reset()
    const panel = panelRef.current
    const trigger = triggerRef.current
    const active = document.activeElement
    if (!panel || !trigger || !active || !trigger.contains(active) || !keyboardFocused(active)) return
    const rows = menuItems(panel)
    focusRow(rows.find(r => r.matches(CHOSEN)) ?? rows[0])
  }, [open, ready, enabled, panelRef, triggerRef])

  // Whether focus has been inside the panel while it was open - so a menu closed by a pick returns
  // focus to its trigger, and one closed by a press elsewhere (which put focus there) does not.
  const hadFocus = useRef(false)

  // Back to the trigger when it closes with focus inside it, or with focus dropped to the body
  // because the row that had it went away (a pick, the exit making the panel inert).
  useEffect(() => {
    if (open || !enabled) return
    const had = hadFocus.current
    hadFocus.current = false
    if (!had) return
    const trigger = triggerRef.current
    const active = document.activeElement
    const ours = !active || active === document.body || (panelRef.current?.contains(active) ?? false)
    if (ours) focusTrigger(trigger)
  }, [open, enabled, panelRef, triggerRef])

  useEffect(() => {
    if (!open || !enabled) return
    const panel = panelRef.current
    if (!panel) return
    const onFocusIn = () => { hadFocus.current = true }
    panel.addEventListener('focusin', onFocusIn)
    return () => panel.removeEventListener('focusin', onFocusIn)
  }, [open, enabled, ready, panelRef])

  // An arrow key on the trigger of an open menu enters it - the pointer opened it, the keyboard
  // goes on. On the document, so it needs nothing from the trigger; a handler nearer the focus that
  // took the key (a text field's own list navigation) has marked it and is left alone.
  useEffect(() => {
    if (!open || !enabled) return
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.defaultPrevented || !isNavKey(e.key) || e.key.startsWith('Page')) return
      const trigger = triggerRef.current
      const panel = panelRef.current
      const active = document.activeElement
      if (!trigger || !panel || !active || !trigger.contains(active) || panel.contains(active)) return
      if (active instanceof HTMLElement && (active.isContentEditable || active instanceof HTMLTextAreaElement || (active instanceof HTMLInputElement && active.type === 'text'))) return
      const rows = menuItems(panel)
      if (rows.length === 0) return
      e.preventDefault()
      focusRow(rows[moveIndex(-1, rows.length, e.key)])
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [open, enabled, panelRef, triggerRef])

  return useCallback((e: ReactKeyboardEvent<HTMLElement>) => {
    if (!enabled) return
    const panel = panelRef.current
    if (!panel || e.defaultPrevented) return
    const rows = menuItems(panel)
    const active = document.activeElement
    const current = rows.findIndex(r => r === active || r.contains(active))

    if (isNavKey(e.key)) {
      e.preventDefault()
      e.stopPropagation()
      focusRow(rows[moveIndex(current, rows.length, e.key)])
      return
    }
    if (e.key === 'Escape' || e.key === 'Tab') {
      e.preventDefault()
      e.stopPropagation()
      closeRef.current()
      focusTrigger(triggerRef.current)
      return
    }
    if (e.key === ' ' && current >= 0 && rows[current].tagName === 'A') {
      // A button acts on Space by itself; a link does not.
      e.preventDefault()
      e.stopPropagation()
      rows[current].click()
      return
    }
    if (isTypeaheadKey(e.key, e) && !(active instanceof HTMLInputElement)) {
      const query = typeahead.current.push(e.key, performance.now())
      const at = typeaheadIndex(rows.map(r => r.textContent ?? ''), current, query)
      if (at >= 0) {
        e.preventDefault()
        e.stopPropagation()
        focusRow(rows[at])
      }
    }
  }, [enabled, panelRef, triggerRef])
}
