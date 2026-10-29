/**
 * WHERE A TOOLTIP GOES (maintainer's request, 2026-10-27: "the tooltip must be viewport aware, and
 * always try to show it above the mouse pointer"). Opened by a mouse, it is anchored at the
 * POINTER, not at the middle of the control - on a wide control the middle can be far from where
 * the reader is looking - and sits above it; only where there is no room above does it go below,
 * clear of the cursor's arrow. Opened from the keyboard, it is anchored at the control, on the
 * side the caller prefers. Either way it is kept inside the viewport, `EDGE` from every side.
 *
 * Plain .ts so `node --test` reads it.
 */

/** Room kept between a tooltip and the viewport's edge. */
export const TOOLTIP_EDGE = 8
/** Above the pointer: room between the bubble and the pointer's tip. */
export const ABOVE_POINTER = 10
/** Below the pointer: room for the cursor's arrow, which hangs about 20px under its tip. */
export const BELOW_POINTER = 24
/** From a control's edge, opened by the keyboard. */
export const FROM_CONTROL = 6

/** What a tooltip is placed against: a point (the pointer) or a box (the control). */
export interface TipAnchor {
  x: number
  /** Where "above" is measured from: the pointer's tip, or the control's top. */
  top: number
  /** Where "below" is measured from: the pointer's tip, or the control's bottom. */
  bottom: number
  /** A pointer leaves room for the cursor under it; a control does not. */
  pointer: boolean
}

export interface TipPlace { left: number; top: number; side: 'top' | 'bottom' }

export function pointerAnchor(x: number, y: number): TipAnchor {
  return { x, top: y, bottom: y, pointer: true }
}

export function controlAnchor(r: { left: number; top: number; width: number; bottom: number }): TipAnchor {
  return { x: r.left + r.width / 2, top: r.top, bottom: r.bottom, pointer: false }
}

/**
 * The bubble's top-left corner, in viewport pixels. Above unless it does not fit there and does
 * below (or `prefer` is "bottom" and it fits there); when it fits on neither side, the side with
 * more room, held inside the viewport. Centered on the anchor across, then slid back inside.
 */
export function tooltipPlace(anchor: TipAnchor, bubble: { w: number; h: number }, viewport: { w: number; h: number },
  prefer: 'top' | 'bottom' = 'top'): TipPlace {
  const gapAbove = anchor.pointer ? ABOVE_POINTER : FROM_CONTROL
  const gapBelow = anchor.pointer ? BELOW_POINTER : FROM_CONTROL
  const aboveTop = anchor.top - gapAbove - bubble.h
  const belowTop = anchor.bottom + gapBelow
  const fitsAbove = aboveTop >= TOOLTIP_EDGE
  const fitsBelow = belowTop + bubble.h <= viewport.h - TOOLTIP_EDGE
  // A pointer always tries above first; a control its caller's side.
  const first = anchor.pointer ? 'top' : prefer
  let side: 'top' | 'bottom'
  if (first === 'top') side = fitsAbove || (!fitsBelow && anchor.top >= viewport.h - anchor.bottom) ? 'top' : 'bottom'
  else side = fitsBelow || (!fitsAbove && viewport.h - anchor.bottom > anchor.top) ? 'bottom' : 'top'
  const rawTop = side === 'top' ? aboveTop : belowTop
  const top = clamp(rawTop, TOOLTIP_EDGE, viewport.h - TOOLTIP_EDGE - bubble.h)
  const left = clamp(anchor.x - bubble.w / 2, TOOLTIP_EDGE, viewport.w - TOOLTIP_EDGE - bubble.w)
  return { left, top, side }
}

function clamp(v: number, min: number, max: number): number {
  // A bubble wider or taller than the viewport starts at the edge.
  return max < min ? min : Math.min(max, Math.max(min, v))
}
