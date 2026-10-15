/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/working.ts
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

/**
 * HOW SOMETHING STILL AT WORK SAYS SO - decided by its SHAPE (maintainer's rule, 2026-10-14: "for
 * non-icon only elements e.g. tool call, sub agents, etc. change it to horizontal wave effect
 * instead of blinking").
 *
 * - AN ICON ALONE BLINKS: an icon button (the agents button, Stop while stopping), a lone state
 *   mark, a status dot - the product's one 1Hz rhythm (`animate-pulse`).
 * - ANYTHING WITH WORDS WAVES: a tool call's row, the thinking and waiting lines, a sub-agent's
 *   name, a running step. A soft band of a second ink sweeps left to right across the words
 *   (`.text-wave` in proxyma-primitives.css), and the mark beside them holds still - the row is
 *   one thing at work, not a blinking icon beside a wave. Every row of a kind blinked before,
 *   words and all, and a thread of parallel calls flickered.
 *
 * Under reduced motion neither moves: the words keep their own ink, and the mark's colour (caption
 * while it works, the brand's once done) and the time beside it say how it stands.
 *
 * Plain .ts with no imports, so `node --test` runs it.
 */
export type WorkingShape = 'icon' | 'text'

/** The blink, for an icon alone. */
export const WORKING_BLINK = 'animate-pulse'
/** The wave, for words - on the element that carries their ink. */
export const WORKING_WAVE = 'text-wave'

/** The class that says `shape` is at work, or nothing while it is not. */
export function workingMotion(shape: WorkingShape, working: boolean): string {
  if (!working) return ''
  return shape === 'icon' ? WORKING_BLINK : WORKING_WAVE
}
