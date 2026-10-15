/**
 * WHERE A FLOATING PANEL GOES, as arithmetic - plain, with no React, so `node --test` reaches it.
 * `useFloating` and `Popover` measure and call it; see floating.ts.
 */

/** How close a floating panel may come to the window edge. */
export const EDGE = 8

export type Placement = 'bottom-start' | 'bottom-end' | 'top-start' | 'top-end' | 'right-start' | 'left-start'

export interface Place {
  top?: number; bottom?: number; left?: number; right?: number; width?: number
  /** The height the window leaves the panel on the side it opened; a panel's height cap reads it as var(--float-room). */
  '--float-room'?: string
}

/**
 * Where a panel goes beside its anchor, in viewport coordinates. It FLIPS to the other side when
 * the preferred one cannot hold it and the other is roomier - never merely because the other is
 * larger - and is clamped inside the window.
 */
export function place(anchor: DOMRect, panel: { width: number; height: number }, placement: Placement): Place {
  const vw = window.innerWidth
  const vh = window.innerHeight
  // BESIDE the anchor, its top on the anchor's top - a submenu opening out of its row. It flips to
  // the other side when this one cannot hold it, and moves up to stay inside the window.
  if (placement === 'right-start' || placement === 'left-start') {
    const roomRight = vw - anchor.right - EDGE
    const roomLeft = anchor.left - EDGE
    const right = placement === 'right-start'
      ? !(roomRight < panel.width + 4 && roomLeft > roomRight)
      : (roomLeft < panel.width + 4 && roomRight > roomLeft)
    const top = Math.max(EDGE, Math.min(anchor.top, vh - panel.height - EDGE))
    const room = `${Math.max(0, vh - 2 * EDGE)}px`
    return right ? { top, left: anchor.right + 4, '--float-room': room } : { top, right: vw - anchor.left + 4, '--float-room': room }
  }
  const wantTop = placement.startsWith('top')
  const below = vh - anchor.bottom - EDGE
  const above = anchor.top - EDGE
  const up = wantTop ? !(above < panel.height + 4 && below > above) : (below < panel.height + 4 && above > below)
  const end = placement.endsWith('end')
  const out: Place = up ? { bottom: vh - anchor.top + 4 } : { top: anchor.bottom + 4 }
  // What the side it opened on can hold: a fixed cap measured against the whole window ran a panel
  // under a low anchor - the Connect menu below a trial banner - off the bottom of the screen.
  out['--float-room'] = `${Math.max(0, (up ? above : below) - 4)}px`
  if (end) out.right = Math.max(EDGE, vw - anchor.right)
  else out.left = Math.min(Math.max(EDGE, anchor.left), Math.max(EDGE, vw - panel.width - EDGE))
  return out
}
