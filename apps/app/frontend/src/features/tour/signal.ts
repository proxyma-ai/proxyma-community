/**
 * WHAT A WALKTHROUGH WAITS FOR (2026-11-10, the maintainer: "the user can follow our step and they will do the actions in
 * each step"): a step that waits on something other than a click on its highlighted control - a dialog moving on, a
 * channel's team starting - is told by the page that did it, by name. A step not running ignores it.
 */
export const TOUR_SIGNAL = 'proxyma:tour-signal'

/** Says that `name` happened, for a walkthrough step waiting on it (TourStep.waitFor). */
export function tourSignal(name: string): void {
  if (typeof window === 'undefined') return
  window.dispatchEvent(new CustomEvent<string>(TOUR_SIGNAL, { detail: name }))
}
