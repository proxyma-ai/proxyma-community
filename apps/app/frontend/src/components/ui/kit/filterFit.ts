/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/filterFit.ts
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

/**
 * HOW MANY FILTERS FIT ON THEIR LINE (FilterRow): the chips in order, and when they do not all fit, as many as fit
 * beside a "More" chip that holds the rest. Plain .ts, so `node --test` reads it.
 *
 * `widths` are the chips' widths in order, `more` the More chip's, `room` the line's width and `gap` the space
 * between two chips. Returns how many chips stay on the line: all of them when they fit without More; otherwise the
 * most that fit together with More after them - none at all on a line too narrow for even one.
 */
export function fitCount(widths: readonly number[], more: number, room: number, gap: number): number {
  const n = widths.length
  if (n === 0) return 0
  const all = widths.reduce((sum, w) => sum + w, 0) + gap * (n - 1)
  // A pixel of slack: widths are measured in fractions and rounded differently by the layout.
  if (all <= room + 0.5) return n
  let used = more
  let k = 0
  while (k < n && used + widths[k] + gap <= room + 0.5) {
    used += widths[k] + gap
    k++
  }
  return k
}
