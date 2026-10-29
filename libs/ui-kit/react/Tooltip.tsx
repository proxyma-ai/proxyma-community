'use client'

import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { cn } from './cn'
import { usePresence } from './motion'
import { controlAnchor, pointerAnchor, tooltipPlace, type TipAnchor, type TipPlace } from './tooltipPlace'

interface Props {
  label: string
  children: React.ReactElement
  /**
   * Where the tooltip goes when it is opened from the KEYBOARD, at the control. Opened by the
   * mouse it always tries above the pointer first (tooltipPlace.ts).
   */
  side?: 'top' | 'bottom'
  delay?: number
  /**
   * The trigger wrapper is `inline-flex` (shrink-to-fit) by default, which is right for a
   * compact trigger like an icon button. A full-width child - a fieldset whose inputs are
   * w-full - needs the wrapper itself to stretch, or the child collapses toward its
   * narrowest intrinsic width.
   *
   * Must be `flex-col`, not a bare row `flex`: a flex row's main-axis sizing does not
   * stretch a single item to fill the container (that is what flex-grow is for, and this
   * wrapper never sets it). Only the cross-axis `align-items: stretch` default does, which
   * for a column direction is horizontal - exactly what makes the child span full width.
   */
  fullWidth?: boolean
  /** Classes for the trigger wrapper - a trigger placed absolutely (a card on a canvas) needs `contents`. */
  className?: string
}

const useIsoLayoutEffect = typeof window === 'undefined' ? useEffect : useLayoutEffect

// A tooltip is a MOUSE affordance. On a touch screen a tap fires mouseenter and focus, so a
// tooltip opened by either appears on every tap, covers the menu or dialog the tap just opened,
// and stays until the next tap elsewhere. It opens on hover only where there is a real pointer
// that hovers, and on focus only when the focus came from the keyboard (:focus-visible).
function canHover(): boolean {
  try {
    return window.matchMedia('(hover: hover) and (pointer: fine)').matches
  } catch {
    return true
  }
}

/**
 * The bubble itself: portaled to the body, so a tooltip inside an `overflow: hidden` box is not
 * clipped by it, and placed by tooltipPlace.ts once its own size is known - measured before it
 * paints, so it never shows in the wrong place first.
 */
function TooltipBubble({ label, anchor, prefer, exiting }: {
  label: string
  anchor: TipAnchor
  prefer: 'top' | 'bottom'
  exiting: boolean
}) {
  const ref = useRef<HTMLSpanElement | null>(null)
  const [at, setAt] = useState<TipPlace | null>(null)
  useIsoLayoutEffect(() => {
    const el = ref.current
    if (!el) return
    const r = el.getBoundingClientRect()
    setAt(tooltipPlace(anchor, { w: r.width, h: r.height }, { w: window.innerWidth, h: window.innerHeight }, prefer))
  }, [anchor, prefer, label])
  return createPortal(
    <span
      ref={ref}
      role="tooltip"
      className={cn(
        'fixed z-tooltip pointer-events-none px-2 py-1 rounded-md text-xs font-ui surface-float text-ink-secondary border shadow-float max-w-[min(300px,calc(100vw-16px))] whitespace-pre-line',
        exiting ? 'animate-fade-out' : 'animate-tooltip-in',
      )}
      // Its place is measured from the pointer and the viewport, which no class holds. Hidden until
      // it is placed, which happens before the first paint.
      style={at ? { left: at.left, top: at.top } : { left: 0, top: 0, visibility: 'hidden' }}
    >
      {label}
    </span>,
    document.body,
  )
}

/**
 * A control's tooltip (maintainer's rule, 2026-10-27: every control has one, it stays inside the
 * viewport, and it shows above the mouse pointer). Opened by the mouse after `delay`, it is placed
 * above where the POINTER is - below it only where there is no room above; opened from the
 * keyboard, at the control, on `side`. It says the same thing either way, and hides on a press,
 * on leaving, and on Escape.
 */
export function Tooltip({ label, children, side = 'top', delay = 400, fullWidth = false, className }: Props) {
  const [visible, setVisible] = useState(false)
  const [anchor, setAnchor] = useState<TipAnchor | null>(null)
  const triggerRef = useRef<HTMLSpanElement | null>(null)
  const pointer = useRef<{ x: number; y: number } | null>(null)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  // Fades out where it stood rather than vanishing; it never takes the pointer, so nothing to guard.
  const { mounted, exiting } = usePresence(visible)

  const open = useCallback((byPointer: boolean) => {
    if (timerRef.current) clearTimeout(timerRef.current)
    timerRef.current = setTimeout(() => {
      const el = triggerRef.current
      if (!el) return
      const p = pointer.current
      // A wrapper with `contents` has no box of its own; its child has.
      const box = (el.getBoundingClientRect().width === 0 && el.firstElementChild ? el.firstElementChild : el).getBoundingClientRect()
      setAnchor(byPointer && p ? pointerAnchor(p.x, p.y) : controlAnchor(box))
      setVisible(true)
    }, delay)
  }, [delay])

  const onEnter = useCallback((e: React.MouseEvent<HTMLSpanElement>) => {
    pointer.current = { x: e.clientX, y: e.clientY }
    if (canHover()) open(true)
  }, [open])

  // The pointer's place is kept as it moves, so the tooltip opens above where it rests.
  const onMove = useCallback((e: React.MouseEvent<HTMLSpanElement>) => {
    pointer.current = { x: e.clientX, y: e.clientY }
  }, [])

  const onFocus = useCallback((e: React.FocusEvent<HTMLSpanElement>) => {
    const target = e.target as HTMLElement
    let keyboard = false
    try { keyboard = target.matches(':focus-visible') } catch { keyboard = false }
    if (keyboard) open(false)
  }, [open])

  const hide = useCallback(() => {
    if (timerRef.current) clearTimeout(timerRef.current)
    timerRef.current = null
    setVisible(false)
  }, [])

  useEffect(
    () => () => {
      if (timerRef.current) clearTimeout(timerRef.current)
    },
    [],
  )

  // Escape dismisses a shown tooltip (WCAG 1.4.13) - and only the tooltip: the key is not marked
  // handled, so a menu or dialog it sits in still gets it.
  useEffect(() => {
    if (!visible) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') hide() }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [visible, hide])

  return (
    <>
      <span
        ref={triggerRef}
        onMouseEnter={onEnter}
        onMouseMove={onMove}
        onMouseLeave={hide}
        onFocus={onFocus}
        onBlur={hide}
        onPointerDown={hide}
        className={className ?? (fullWidth ? 'flex flex-col w-full' : 'inline-flex')}
      >
        {children}
      </span>
      {mounted && anchor && label && <TooltipBubble label={label} anchor={anchor} prefer={side} exiting={exiting} />}
    </>
  )
}

/**
 * A tooltip for something no element can wrap - a line drawn in SVG, a port on a canvas: the caller
 * says what is under the pointer (`label`, or null for nothing) and where the pointer is, and it
 * opens above that place after `delay`, as `Tooltip` does for a mouse.
 */
export function PointerTooltip({ label, x, y, delay = 400 }: { label: string | null; x: number; y: number; delay?: number }) {
  const [shown, setShown] = useState<{ label: string; anchor: TipAnchor } | null>(null)
  const where = useRef({ x, y })
  where.current = { x, y }
  const { mounted, exiting } = usePresence(shown !== null && label !== null)
  const last = useRef(shown)
  if (shown) last.current = shown
  useEffect(() => {
    setShown(null)
    if (!label || !canHover()) return
    const timer = setTimeout(() => setShown({ label, anchor: pointerAnchor(where.current.x, where.current.y) }), delay)
    return () => clearTimeout(timer)
  }, [label, delay])
  const tip = shown ?? last.current
  if (!mounted || !tip) return null
  return <TooltipBubble label={tip.label} anchor={tip.anchor} prefer="top" exiting={exiting} />
}
