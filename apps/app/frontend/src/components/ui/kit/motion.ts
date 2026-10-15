/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/motion.ts
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import { useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react'
import { applyAppearance, type ResolvedAppearance } from './appearance'

/**
 * THE KIT'S MOTION HELPERS - dependency-free, because the kit runs in four apps and only one of
 * them has framer-motion (maintainer, 2026-10-10: "all and every UI transition must have a smooth
 * transition effect"). The durations and curves are the tokens in proxyma-tokens.css; what moves
 * is CSS (proxyma-primitives.css, "MOTION"). This file only decides WHEN:
 *
 * - `usePresence` keeps a panel the component owns (a menu, a dropdown, a tooltip) mounted while it
 *   animates out.
 * - `useExitGhost` plays an exit for a dialog its CALLER unmounts at once (`{open && <Dialog/>}`,
 *   the shape of every dialog in four apps): on unmount, an inert copy fades out in its place.
 * - `useFadeOnChange` / `FadeSwap` fade new content in when a key changes (a tab, a route) without
 *   remounting anything.
 * - `installMotion` turns transitions on after the first frames, so nothing animates into place
 *   while a page loads; `applyAppearanceSmoothly` crossfades a light/dark switch.
 *
 * REDUCED MOTION is honored in each: nothing lingers, nothing moves.
 */

/** The tokens in milliseconds, for timers. Mirrors --motion-* in proxyma-tokens.css - change both. */
export const MOTION_MS = { fast: 120, base: 200, slow: 300 } as const

/** On <html> once transitions may run - set by `installMotion`, absent while the page loads. */
export const MOTION_READY_ATTR = 'data-motion-ready'

const useIsoLayoutEffect = typeof window === 'undefined' ? useEffect : useLayoutEffect

export function prefersReducedMotion(): boolean {
  try {
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches
  } catch {
    return false
  }
}

/** Whether the page has finished loading - `installMotion` has let transitions run. */
export function motionReady(): boolean {
  return typeof document !== 'undefined' && document.documentElement.hasAttribute(MOTION_READY_ATTR)
}

/** Whether an exit or a fade should play now: motion is allowed and the page has finished loading. */
function motionAllowed(): boolean {
  return motionReady() && !prefersReducedMotion()
}

/**
 * Turns transitions on for the page, two frames after it first paints. Until then the primitives
 * hold every transition at none, so the first paint, the pre-paint theme script and whatever the
 * app settles in its first effects (a stored preference, a measured header) land in place rather
 * than easing there. Call once, at startup, in each app.
 */
export function installMotion(): void {
  if (typeof window === 'undefined') return
  const root = document.documentElement
  if (root.hasAttribute(MOTION_READY_ATTR)) return
  requestAnimationFrame(() => requestAnimationFrame(() => root.setAttribute(MOTION_READY_ATTR, '')))
}

/**
 * Runs `change` with every transition held off, then lets them run again - for a change that must
 * land at once everywhere (the appearance), where one control easing its color behind a page that
 * has already switched reads as lag. The forced style read is what makes the new values current
 * before transitions return; without it they would ease from the old ones a frame later.
 */
export function withoutTransitions(change: () => void): void {
  const root = document.documentElement
  const ready = root.hasAttribute(MOTION_READY_ATTR)
  root.removeAttribute(MOTION_READY_ATTR)
  change()
  void root.offsetWidth
  if (ready) root.setAttribute(MOTION_READY_ATTR, '')
}

interface ViewTransitionDocument {
  startViewTransition?: (update: () => void) => { ready?: Promise<unknown>; finished?: Promise<unknown>; updateCallbackDone?: Promise<unknown> } | undefined
}

/**
 * `applyAppearance` on the document, as a short crossfade of the whole page where the browser can
 * draw one (the View Transitions API), and at once everywhere else. ONE crossfade rather than every
 * surface easing its own colors: forty elements changing at their own pace read as a flicker.
 * The first application at startup should stay plain `applyAppearance` - there is nothing to fade
 * from.
 */
export function applyAppearanceSmoothly(resolved: ResolvedAppearance): void {
  const root = document.documentElement
  const same = root.getAttribute('data-mode') === resolved.mode && root.getAttribute('data-shade') === resolved.shade
  const apply = () => withoutTransitions(() => applyAppearance(root, resolved))
  const doc = document as unknown as ViewTransitionDocument
  if (same || !motionAllowed() || typeof doc.startViewTransition !== 'function') { apply(); return }
  const transition = doc.startViewTransition.call(document, apply)
  // A crossfade a second switch interrupts is SKIPPED, and its promises reject with an AbortError
  // nobody needs to hear about - the page has the newer appearance either way.
  const quiet = () => undefined
  transition?.ready?.catch(quiet)
  transition?.finished?.catch(quiet)
  transition?.updateCallbackDone?.catch(quiet)
}

/**
 * Keeps something mounted while it animates out.
 *
 * `mounted` is whether to render it at all; `exiting` is true for the `exitMs` it spends leaving,
 * when it should carry an exit class (`animate-pop-out`, `animate-fade-out`) and take no pointer
 * events - a closing menu must not catch the click meant for what is under it. Opening again
 * mid-exit simply keeps it. Under reduced motion it unmounts at once, before paint.
 */
export function usePresence(open: boolean, exitMs: number = MOTION_MS.fast): { mounted: boolean; exiting: boolean } {
  const [present, setPresent] = useState(open)
  useIsoLayoutEffect(() => {
    if (open) { setPresent(true); return }
    if (!present) return
    if (!motionAllowed()) { setPresent(false); return }
    const timer = setTimeout(() => setPresent(false), exitMs)
    return () => clearTimeout(timer)
  }, [open, present, exitMs])
  return { mounted: open || present, exiting: !open && present }
}

/** React 18's types have no `inert`; spread this onto a panel that is leaving. */
export function exitingProps(exiting: boolean): Record<string, string | boolean> {
  return exiting ? { inert: '', 'aria-hidden': true } : {}
}

const MEDIA = 'iframe, video, audio, object, embed'

/**
 * An inert, positioned copy of `node` as it looks NOW: scroll positions, field values and canvas
 * pixels carried over (a clone keeps none of them), ids and names dropped (a second element with
 * the same id or radio group would answer for the real one), and media replaced by an empty box
 * of the same size - a cloned frame would load its page again.
 */
function snapshot(node: HTMLElement): { ghost: HTMLElement; restore: () => void } {
  const ghost = node.cloneNode(true) as HTMLElement
  const from = [node, ...Array.from(node.querySelectorAll<HTMLElement>('*'))]
  const to = [ghost, ...Array.from(ghost.querySelectorAll<HTMLElement>('*'))]
  const scrolls: Array<[HTMLElement, number, number]> = []
  from.forEach((src, i) => {
    const dst = to[i]
    if (!dst) return
    dst.removeAttribute('id')
    if (src.scrollTop || src.scrollLeft) scrolls.push([dst, src.scrollTop, src.scrollLeft])
    if (src instanceof HTMLInputElement && dst instanceof HTMLInputElement) {
      dst.removeAttribute('name')
      if (src.type !== 'file') dst.value = src.value
      dst.checked = src.checked
    } else if (src instanceof HTMLTextAreaElement && dst instanceof HTMLTextAreaElement) {
      dst.value = src.value
    } else if (src instanceof HTMLSelectElement && dst instanceof HTMLSelectElement) {
      dst.removeAttribute('name')
      dst.selectedIndex = src.selectedIndex
    } else if (src instanceof HTMLCanvasElement && dst instanceof HTMLCanvasElement) {
      try { dst.getContext('2d')?.drawImage(src, 0, 0) } catch { /* a WebGL or tainted canvas: left blank */ }
    } else if (src instanceof HTMLImageElement && src.currentSrc.startsWith('blob:')) {
      // A blob URL is usually revoked by the time the copy would load it, so the copy is a picture.
      const pic = document.createElement('canvas')
      pic.width = src.naturalWidth
      pic.height = src.naturalHeight
      pic.className = src.getAttribute('class') ?? ''
      pic.style.width = `${src.offsetWidth}px`
      pic.style.height = `${src.offsetHeight}px`
      for (const a of Array.from(src.attributes)) if (a.name.startsWith('data-motion')) pic.setAttribute(a.name, a.value)
      try { pic.getContext('2d')?.drawImage(src, 0, 0) } catch { /* not decoded: an empty box */ }
      dst.replaceWith(pic)
    } else if (src.matches(MEDIA)) {
      const box = document.createElement('div')
      box.className = src.getAttribute('class') ?? ''
      box.style.width = `${src.offsetWidth}px`
      box.style.height = `${src.offsetHeight}px`
      dst.replaceWith(box)
    }
  })
  ghost.setAttribute('data-ghost', '')
  ghost.setAttribute('aria-hidden', 'true')
  ghost.setAttribute('inert', '')
  return {
    ghost,
    restore: () => scrolls.forEach(([el, top, left]) => { el.scrollTop = top; el.scrollLeft = left }),
  }
}

/**
 * AN EXIT FOR SOMETHING ITS CALLER UNMOUNTS AT ONCE - a dialog, a status screen. Every dialog in
 * four apps is opened as `{open && <Dialog/>}`, so the component is simply gone when it closes; a
 * presence flag would need every one of those call sites rewritten to keep it mounted. Instead, as
 * it unmounts, an inert COPY of `ref`'s node (a `position: fixed` overlay) is put back into the body
 * and fades out: the scrim to nothing, the panel (`data-motion-panel`) shrinking slightly as it
 * goes. The copy is a picture - no React, no handlers, no pointer events, hidden from assistive
 * technology - and is removed when the exit ends.
 *
 * The snapshot is taken in the layout cleanup, while the DOM is still in the page (scroll positions
 * read 0 once it is not), and inserted only if the node is really gone a microtask later, which is
 * what tells a real unmount from React's StrictMode rehearsal. Reduced motion: no copy at all.
 */
export function useExitGhost(ref: RefObject<HTMLElement>, exitMs: number = MOTION_MS.fast): void {
  useIsoLayoutEffect(() => {
    const node = ref.current
    if (!node) return
    return () => {
      if (!motionAllowed()) return
      const { ghost, restore } = snapshot(node)
      queueMicrotask(() => {
        if (node.isConnected) return
        document.body.appendChild(ghost)
        restore()
        setTimeout(() => ghost.remove(), exitMs + 40)
      })
    }
  }, [])
}

/**
 * Fades `ref`'s node in whenever `key` changes after the first render - a route, a tab - WITHOUT
 * remounting it, so nothing under it loses its state. Opacity only: a transform would make the
 * node the containing block of every `position: fixed` thing inside it for the duration.
 */
export function useFadeOnChange(ref: RefObject<HTMLElement>, key: unknown, durationMs: number = MOTION_MS.base): void {
  const first = useRef(true)
  useIsoLayoutEffect(() => {
    if (first.current) { first.current = false; return }
    const el = ref.current
    if (!el || typeof el.animate !== 'function' || !motionAllowed()) return
    // The curve is the token's (--ease-out), read where it is defined rather than written twice.
    const easing = getComputedStyle(document.documentElement).getPropertyValue('--ease-out').trim() || 'cubic-bezier(0.2, 0, 0, 1)'
    el.animate([{ opacity: 0 }, { opacity: 1 }], { duration: durationMs, easing })
  }, [key])
}
