// HOW MUCH THE COMPOSER'S CONTROLS ROW SAYS (the maintainer's feedback, 2026-11-02: "In narrow width,
// icon only could be used. Tooltip to show more detail."). The same composer sits in the chat page, a
// key account's panel and a Constellations agent's chat, so the decision is taken from the COMPOSER'S
// OWN room, never the viewport: a 360px panel on a wide screen is narrow.
//
// Two controls carry words - the permission mode ("Ask") and the agent's provider and model, the
// longest. Each folds to its icon, its words moving to its tooltip, in that order: the model first,
// then the mode. They share the row's left side with the + menu; the right side (the agents map,
// alarms, the rings, Send) is icons already, and what it takes is what the left side does not have.
//
// Pure and import-free, so `node --test` reaches it (composerFit.test.ts). The widths are measured by
// useComposerFit: each control's full and folded form drawn off-screen, the room and the + menu as
// they stand.

/** Everything in words; the model folded to its icon; both folded. */
export type ComposerFit = 'full' | 'modelIcon' | 'icons'

export interface ComposerWidths {
  /** The start of the controls row - the room the controls share. 0 while unmeasured. */
  room: number
  /** The other controls in that room (the + menu), with the gaps between them. */
  others: number
  /** The gap between two controls. */
  gap: number
  modeFull: number
  modeIcon: number
  /** 0 for both where the model is not shown (a reader who may not see the agent's settings). */
  modelFull: number
  modelIcon: number
}

const ORDER: ComposerFit[] = ['full', 'modelIcon', 'icons']

/** The width a level needs: the others, then each control in its form at that level and its gap. */
export function neededWidth(w: ComposerWidths, fit: ComposerFit): number {
  const mode = fit === 'icons' ? w.modeIcon : w.modeFull
  const hasModel = w.modelFull > 0 || w.modelIcon > 0
  const model = fit === 'full' ? w.modelFull : w.modelIcon
  return w.others + w.gap + mode + (hasModel ? w.gap + model : 0)
}

/**
 * The most the row can say in its room: the first level that fits, or icons alone when none does.
 * Unmeasured (no room yet - a panel still opening, a hidden tab) keeps the words: there is nothing
 * to decide from, and the next measurement decides. Half a pixel of slack, since widths are
 * fractional and a label that fits exactly must not fold on a rounding.
 */
export function composerFit(w: ComposerWidths): ComposerFit {
  if (!(w.room > 0)) return 'full'
  for (const fit of ORDER) {
    if (neededWidth(w, fit) <= w.room + 0.5) return fit
  }
  return 'icons'
}
