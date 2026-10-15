/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/useModalLayer.ts
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import { useEffect, useRef, useState, type RefObject } from 'react'
import { createModalStack, keyAction, tabTarget, type EnterTarget } from './modalStack'

export interface ModalLayerOptions {
  /**
   * Asked to close by Escape. Omit it for a dialog that can never be dismissed - it still traps
   * focus and still swallows Escape, so the key cannot close whatever lies beneath it.
   */
  onRequestClose?: () => void
  /** False while closing must be refused - a save in flight. Defaults to true. */
  closable?: boolean
  /**
   * Enter submits the dialog, for simple confirmations. Never fires from a textarea, a
   * contenteditable region, a button (which Enter already clicks), or an input inside a form.
   */
  onSubmit?: () => void
  /**
   * Where focus lands on open, when no element inside carries `data-autofocus` and nothing inside
   * already took focus through `autoFocus`. `panel` (the default) focuses the dialog itself, so a
   * screen reader announces it and the first Tab reaches its first control; `first-field` focuses
   * the first text field, for a form that is opened in order to be typed into.
   */
  initialFocus?: 'panel' | 'first-field'
  /**
   * Ranks this layer above every layer of a lower level, whatever order they opened in - see
   * createModalStack. Leave it at 0 unless the dialog is drawn above everything else.
   */
  level?: number
}

interface Layer {
  panel: RefObject<HTMLElement>
  options: { current: ModalLayerOptions }
}

const FOCUSABLE = [
  'a[href]',
  'area[href]',
  'button:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  'iframe',
  'summary',
  '[contenteditable=""]',
  '[contenteditable="true"]',
  '[tabindex]:not([tabindex="-1"])',
].join(',')

const FIELD = 'input:not([disabled]):not([readonly]):not([type="hidden"]):not([type="checkbox"]):not([type="radio"]):not([type="button"]):not([type="submit"]),textarea:not([disabled]):not([readonly]),[contenteditable="true"]'

// One stack and one listener for the whole page - in a consumer, the module is the singleton.
const stack = createModalStack<Layer>(layer => layer.options.current.level ?? 0)

function focusables(panel: HTMLElement): HTMLElement[] {
  return Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(
    el => el.tabIndex >= 0 && el.getClientRects().length > 0,
  )
}

function enterTarget(el: Element | null): EnterTarget {
  if (!(el instanceof HTMLElement)) return 'other'
  if (el.isContentEditable || el instanceof HTMLTextAreaElement) return 'multiline'
  if (el instanceof HTMLSelectElement) return 'choice'
  if (el instanceof HTMLButtonElement || el instanceof HTMLAnchorElement || el.tagName === 'SUMMARY') return 'activator'
  if (el.getAttribute('role') === 'button' || el.getAttribute('role') === 'link') return 'activator'
  if (el instanceof HTMLInputElement) {
    if (['button', 'submit', 'reset', 'image', 'file'].includes(el.type)) return 'activator'
    return el.form ? 'form' : 'field'
  }
  return 'other'
}

// WINDOW, BUBBLE PHASE - the last listener any keydown reaches. Everything nearer the focus runs
// first: React's own handlers at the root, a menu's or a Select's document listener. Each of those
// that consumes Escape calls preventDefault(), and keyAction() reads that as "already handled", so
// Escape inside an open Select closes the Select and leaves the dialog alone. A dialog listening on
// the document in the capture phase (RestartingModal) stops the event before it gets here at all.
function onKeyDown(e: KeyboardEvent) {
  const layer = stack.top()
  const panel = layer?.panel.current
  if (!layer || !panel) return
  const opts = layer.options.current

  if (e.key === 'Tab') {
    if (e.defaultPrevented) return
    const active = document.activeElement
    // Focus inside a portaled popover that belongs to this dialog (an open Select's search box)
    // is outside the panel's DOM but not outside the dialog. Leave it alone; the popover closes
    // on its own when focus moves on.
    if (active && active !== document.body && active !== panel && !panel.contains(active)) return
    const list = focusables(panel)
    const index = active && active !== panel ? list.indexOf(active as HTMLElement) : -1
    const next = tabTarget(list.length, index, e.shiftKey)
    if (next === null) return
    e.preventDefault()
    if (next === 'panel') panel.focus()
    else list[next].focus()
    return
  }

  const action = keyAction({
    key: e.key,
    defaultPrevented: e.defaultPrevented,
    isComposing: e.isComposing,
    shiftKey: e.shiftKey,
    ctrlKey: e.ctrlKey,
    altKey: e.altKey,
    metaKey: e.metaKey,
    repeat: e.repeat,
    closable: opts.closable !== false && !!opts.onRequestClose,
    hasSubmit: !!opts.onSubmit,
    target: enterTarget(document.activeElement),
  })
  if (action === 'none') return
  e.preventDefault()
  if (action === 'close') opts.onRequestClose?.()
  else if (action === 'submit') opts.onSubmit?.()
}

function register(layer: Layer) {
  if (stack.size() === 0) window.addEventListener('keydown', onKeyDown)
  stack.push(layer)
}

function unregister(layer: Layer) {
  stack.remove(layer)
  if (stack.size() === 0) window.removeEventListener('keydown', onKeyDown)
}

/**
 * Makes the element behind `panelRef` a dialog layer: Escape reaches only the TOPMOST dialog,
 * Tab and Shift+Tab cycle inside it, focus moves in on open and returns to whatever had it on
 * close. Mount the component only while the dialog is open - the layer lives exactly as long
 * as the component does.
 *
 * The panel should carry `tabIndex={-1}` so it can hold focus itself when nothing inside can.
 */
export function useModalLayer(panelRef: RefObject<HTMLElement>, options: ModalLayerOptions = {}) {
  const optionsRef = useRef(options)
  optionsRef.current = options

  // Captured during the FIRST RENDER, before any child's `autoFocus` has moved focus into the
  // dialog - by the time an effect runs, document.activeElement is already inside it and the
  // element that opened the dialog is lost.
  const [opener] = useState<Element | null>(() =>
    typeof document === 'undefined' ? null : document.activeElement,
  )

  // A passive effect rather than a layout one: the landing site renders this on a server, where
  // useLayoutEffect warns on every render. Focus moving one frame after the first paint is not
  // something a person can see.
  useEffect(() => {
    const layer: Layer = { panel: panelRef, options: optionsRef }
    register(layer)

    const panel = panelRef.current
    // Only the layer that receives the keys takes the focus. A dialog opening UNDER a higher-level
    // one (Settings, opened by the onboarding tour that stays on top of it) leaves focus alone.
    if (panel && stack.top() === layer && !panel.contains(document.activeElement)) {
      const marked = panel.querySelector<HTMLElement>('[data-autofocus]')
      const field = optionsRef.current.initialFocus === 'first-field'
        ? Array.from(panel.querySelectorAll<HTMLElement>(FIELD)).find(el => el.getClientRects().length > 0)
        : undefined
      ;(marked ?? field ?? panel).focus({ preventScroll: true })
    }

    return () => {
      unregister(layer)
      // Give focus back only if it is still ours to give: inside this dialog, or dropped to the
      // body because the dialog's DOM just went away. If the user has since focused something
      // else deliberately, that wins.
      const active = document.activeElement
      const ours = !active || active === document.body || (panel?.contains(active) ?? false)
      if (ours && opener instanceof HTMLElement && opener.isConnected) {
        opener.focus({ preventScroll: true })
      }
    }
    // The layer is the component's lifetime; option changes are read through the ref.
  }, [])
}
