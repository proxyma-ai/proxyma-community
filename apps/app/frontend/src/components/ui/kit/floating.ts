/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/floating.ts
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import { useCallback, useEffect, useLayoutEffect, useState, type CSSProperties, type RefObject } from 'react'
import { place, type Place, type Placement } from './placement'

/**
 * WHERE A FLOATING PANEL GOES - every panel anchored to a control and portaled to the body: the
 * kit `Popover` (and so every popover menu), `Combobox`'s suggestions and `DateTimePicker`'s
 * calendar. Portaled because a panel laid out inside its trigger's box is clipped by any dialog or
 * scroller around it, or grows that scroller; fixed, so it has to be measured and re-measured
 * rather than flowed. `Select` keeps its own measure, which also caps its list's height. The
 * arithmetic is placement.ts.
 */

export { EDGE, place, type Place, type Placement } from './placement'

const useIsoLayoutEffect = typeof window === 'undefined' ? useEffect : useLayoutEffect

/**
 * Keeps a portaled panel where `place` says while it is shown: measured before it paints, again on
 * a resize and on a scroll anywhere (capture, so a dialog's own scroller counts). `matchWidth`
 * gives the panel its anchor's width - a field's suggestions. `style` is hidden until measured, so
 * the panel's first frame is never at the window's corner.
 */
export function useFloating(
  anchorRef: RefObject<HTMLElement | null>,
  panelRef: RefObject<HTMLElement | null>,
  { open, mounted, placement = 'bottom-start', matchWidth = false }: {
    open: boolean
    /** Still drawn - open, or leaving. The position is dropped once it is gone. */
    mounted: boolean
    placement?: Placement
    matchWidth?: boolean
  },
): { style: CSSProperties; side: 'top' | 'bottom'; measure: () => void } {
  const [pos, setPos] = useState<Place | null>(null)

  const measure = useCallback(() => {
    const a = anchorRef.current
    if (!a) return
    const p = panelRef.current
    const r = a.getBoundingClientRect()
    const next = place(r, { width: matchWidth ? r.width : p?.offsetWidth ?? 224, height: p?.offsetHeight ?? 0 }, placement)
    if (matchWidth) next.width = r.width
    setPos(next)
  }, [anchorRef, panelRef, placement, matchWidth])

  useIsoLayoutEffect(() => {
    if (open) measure()
    else if (!mounted) setPos(null)
  }, [open, mounted, measure])

  useEffect(() => {
    if (!open) return
    window.addEventListener('resize', measure)
    window.addEventListener('scroll', measure, true)
    return () => {
      window.removeEventListener('resize', measure)
      window.removeEventListener('scroll', measure, true)
    }
  }, [open, measure])

  return {
    style: pos ?? { visibility: 'hidden', top: 0, left: 0 },
    side: pos?.bottom !== undefined ? 'top' : 'bottom',
    measure,
  }
}
