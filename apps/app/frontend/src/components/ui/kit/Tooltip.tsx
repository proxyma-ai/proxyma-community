/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/Tooltip.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { cn } from './cn'
import { usePresence } from './motion'

interface Props {
  label: string
  children: React.ReactElement
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
}

// Space kept between the bubble and the viewport edge.
const EDGE = 8

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
 * Portal-rendered so a tooltip on a control inside an `overflow: hidden` or
 * `position: relative` container is not clipped by it. Positioned from the trigger's
 * measured rect at show time rather than by CSS anchoring, which no target browser has, then
 * nudged back inside the viewport once its own width is known - a tooltip on a control near the
 * screen edge used to run off it.
 */
export function Tooltip({ label, children, side = 'top', delay = 400, fullWidth = false }: Props) {
  const [visible, setVisible] = useState(false)
  const [pos, setPos] = useState<{ x: number; y: number } | null>(null)
  const [shift, setShift] = useState(0)
  const triggerRef = useRef<HTMLSpanElement | null>(null)
  const bubbleRef = useRef<HTMLSpanElement | null>(null)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  // Fades out where it stood rather than vanishing; it never takes the pointer, so nothing to guard.
  const { mounted, exiting } = usePresence(visible)

  const open = useCallback(() => {
    timerRef.current = setTimeout(() => {
      const el = triggerRef.current
      if (!el) return
      const rect = el.getBoundingClientRect()
      setShift(0)
      setPos({
        x: rect.left + rect.width / 2,
        y: side === 'top' ? rect.top - 6 : rect.bottom + 6,
      })
      setVisible(true)
    }, delay)
  }, [delay, side])

  const onHover = useCallback(() => {
    if (canHover()) open()
  }, [open])

  const onFocus = useCallback((e: React.FocusEvent<HTMLSpanElement>) => {
    const target = e.target as HTMLElement
    let keyboard = false
    try { keyboard = target.matches(':focus-visible') } catch { keyboard = false }
    if (keyboard) open()
  }, [open])

  const hide = useCallback(() => {
    if (timerRef.current) clearTimeout(timerRef.current)
    timerRef.current = null
    setVisible(false)
  }, [])

  useLayoutEffect(() => {
    if (!visible || !pos || !bubbleRef.current) return
    const r = bubbleRef.current.getBoundingClientRect()
    const vw = window.innerWidth
    if (r.left < EDGE) setShift(s => s + (EDGE - r.left))
    else if (r.right > vw - EDGE) setShift(s => s - (r.right - (vw - EDGE)))
  }, [visible, pos])

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
        onMouseEnter={onHover}
        onMouseLeave={hide}
        onFocus={onFocus}
        onBlur={hide}
        onPointerDown={hide}
        className={fullWidth ? 'flex flex-col w-full' : 'inline-flex'}
      >
        {children}
      </span>
      {mounted &&
        pos &&
        createPortal(
          <span
            ref={bubbleRef}
            role="tooltip"
            className={cn(
              'fixed z-tooltip pointer-events-none px-2 py-1 rounded-md text-xs font-ui surface-float text-ink-secondary border border-line/45 shadow-sm max-w-[min(300px,calc(100vw-16px))]',
              exiting ? 'animate-fade-out' : 'animate-tooltip-in',
            )}
            style={{
              left: pos.x + shift,
              top: pos.y,
              transform: side === 'top' ? 'translate(-50%, -100%)' : 'translate(-50%, 0)',
            }}
          >
            {label}
          </span>,
          document.body,
        )}
    </>
  )
}
