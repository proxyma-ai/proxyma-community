import { create } from 'zustand'

/** The product's own first-run tour; an edition's walkthroughs have names of their own ('youtube', 'constellations'). */
export const PRODUCT_TOUR = 'product'

// Thin, dumb state container for the walkthroughs - deliberately has no knowledge of routing, role
// gating, or which step data actually means (see data/tourSteps.ts and components/TourOverlay.tsx in
// features/tour/, which own all of that). Mirrors the same framework-agnostic shape as
// themeStore/toastStore. `tour` names the walkthrough running: the product's, or one of the
// edition's (useEditionWalkthroughs).
interface TourState {
  active: boolean
  stepIndex: number
  tour: string
  start: (tour?: string) => void
  next: () => void
  skip: () => void
  finish: () => void
}

export const useTourStore = create<TourState>((set) => ({
  active: false,
  stepIndex: 0,
  tour: PRODUCT_TOUR,
  start: (tour = PRODUCT_TOUR) => set({ active: true, stepIndex: 0, tour }),
  next: () => set(s => ({ stepIndex: s.stepIndex + 1 })),
  // Skip and finish both end the tour immediately - kept as two actions (rather than one) so the
  // calling component can tell, if it ever needs to, whether the user bailed early or completed
  // every reachable step; the store itself treats them identically.
  skip: () => set({ active: false, stepIndex: 0 }),
  finish: () => set({ active: false, stepIndex: 0 }),
}))
