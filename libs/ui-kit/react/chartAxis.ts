/**
 * WHICH LABELS A COLUMN CHART'S AXIS CAN CARRY (0.28.0 feedback: "don't leave this empty").
 *
 * The axis used to print three labels - the first, the middle and the last column - so a 30-day
 * chart read "Sep 6 ... Sep 21 ... Oct 5" with nothing under the 27 columns between. It now has a
 * label under every column that has room for one, and thins to every 2nd, 3rd, 7th... column only
 * when the columns are too narrow for their labels.
 *
 * TWO ROWS: the column's own label (the day of the month, or the month) and, under it, the GROUP it
 * belongs to (the month, or the year), written once where the group starts. "Sep 6" beside a "7"
 * needs four times the room the "7" does, so a month written into the day row would thin the whole
 * row to every other day to make room for one label; in a row of its own it costs nothing.
 *
 * Widths are ESTIMATED from the character count (`charWidth` per character, at the axis's size - ColumnChart passes 7 for its 12px),
 * not measured: the decision has to be made before the labels are drawn, and a label is a few
 * digits. Plain `.ts` with no imports, so `node --test` reaches it.
 */

/** The steps the axis thins through: every column, every 2nd, 3rd, week, fortnight... */
export const AXIS_STEPS = [1, 2, 3, 7, 14, 30, 61, 91, 182, 365]

export interface AxisOptions {
  /** Pixels per character at the axis's size. */
  charWidth?: number
  /** The least space between two labels. */
  gap?: number
  steps?: number[]
}

const DEFAULTS = { charWidth: 6, gap: 6 }

/** One label to draw: which column it belongs to, its text, and its left edge in pixels. */
export interface AxisLabel {
  index: number
  label: string
  left: number
}

/** A label's horizontal extent, centred under its column and kept inside the plot. */
function extent(index: number, text: string, columnWidth: number, plotWidth: number, charWidth: number): [number, number] {
  const w = text.length * charWidth
  const centre = (index + 0.5) * columnWidth
  const left = Math.max(0, Math.min(centre - w / 2, plotWidth - w))
  return [left, left + w]
}

/**
 * The columns that get a label in the first row: every `step`-th from the first, with the smallest
 * step at which no two labels touch. A step that leaves the last column unlabelled still counts as
 * fitting - the last column is not special, and forcing it in would break the rhythm.
 */
export function axisTicks(labels: string[], plotWidth: number, opts: AxisOptions = {}): AxisLabel[] {
  const count = labels.length
  if (count === 0) return []
  const charWidth = opts.charWidth ?? DEFAULTS.charWidth
  const gap = opts.gap ?? DEFAULTS.gap
  const steps = opts.steps ?? AXIS_STEPS
  const columnWidth = plotWidth / count
  const place = (i: number): AxisLabel => ({ index: i, label: labels[i], left: extent(i, labels[i], columnWidth, plotWidth, charWidth)[0] })
  for (const step of steps) {
    const shown: number[] = []
    for (let i = 0; i < count; i += step) shown.push(i)
    let fits = true
    for (let k = 1; k < shown.length && fits; k++) {
      const [, rightA] = extent(shown[k - 1], labels[shown[k - 1]], columnWidth, plotWidth, charWidth)
      const [leftB] = extent(shown[k], labels[shown[k]], columnWidth, plotWidth, charWidth)
      if (rightA + gap > leftB) fits = false
    }
    if (fits) return shown.map(place)
  }
  return [place(0)]
}

/**
 * The second row: each group's name where its first column is, left-aligned on that column. A
 * group too short for its name (the three days of September at the start of a window that is
 * nearly all October) gives way to the group after it rather than overprinting it.
 */
export function axisGroups(groups: string[], plotWidth: number, opts: AxisOptions = {}): AxisLabel[] {
  const count = groups.length
  if (count === 0) return []
  const charWidth = opts.charWidth ?? DEFAULTS.charWidth
  const gap = opts.gap ?? DEFAULTS.gap
  const columnWidth = plotWidth / count
  const starts: { index: number; label: string }[] = []
  for (let i = 0; i < count; i++) {
    if (groups[i] && (i === 0 || groups[i] !== groups[i - 1])) starts.push({ index: i, label: groups[i] })
  }
  // From the last group back, so a cramped group gives way to the one after it.
  const kept: AxisLabel[] = []
  let nextLeft = Infinity
  for (let k = starts.length - 1; k >= 0; k--) {
    const w = starts[k].label.length * charWidth
    // Kept inside the plot: a group starting at the last column ends where the plot does.
    const left = Math.max(0, Math.min(starts[k].index * columnWidth, plotWidth - w))
    if (left + w + gap <= nextLeft) {
      kept.unshift({ ...starts[k], left })
      nextLeft = left
    }
  }
  return kept
}
