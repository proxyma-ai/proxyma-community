/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/chartTheme.ts
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

/**
 * THE CHART THEME FOR RECHARTS - plain objects an app spreads into `<XAxis>`, `<YAxis>`,
 * `<CartesianGrid>` and `<Tooltip>`, so every chart the app draws with recharts reads its colours
 * from the tokens and looks like the kit's own `ColumnChart`.
 *
 * Four files styled their charts one by one: a local TICK in each, a `tick={{ fontSize: 11 }}` that
 * kept recharts' own #666 ink (about 3.3:1 on the Proxyma canvas, at 11px), grids dimmed with
 * `opacity-20` on recharts' default stroke. The kit cannot depend on recharts (four apps, one of
 * which has it), so these are plain values - recharts takes SVG presentation props, and a CSS
 * variable is a valid SVG paint. Plain .ts, no imports.
 */

/** Ink and lines, as SVG paints that follow the appearance. */
export const CHART_INK = {
  /** Tick labels and the legend: caption ink. */
  tick: 'rgb(var(--ink-caption))',
  /** The axis line and the grid: the hairline. */
  line: 'rgb(var(--line))',
  /** One series with nothing to tell apart: the brand's decorative accent, as ColumnChart draws. */
  brand: 'rgb(var(--brand-accent))',
  /** The hovered or chosen mark. */
  brandActive: 'rgb(var(--brand-legible))',
} as const

/**
 * The categorical series, in their validated order (proxyma-tokens.css: do not re-order to taste),
 * then the remainder. For entities told apart by colour - providers, services, tiers.
 */
export const CHART_SERIES = [
  'rgb(var(--chart-series-1))',
  'rgb(var(--chart-series-2))',
  'rgb(var(--chart-series-3))',
  'rgb(var(--chart-series-4))',
] as const
export const CHART_SERIES_OTHER = 'rgb(var(--chart-series-other))'

/** The axis's labels: 12px in caption ink, the UI face - the type scale's floor (`text-2xs`); 11px until
 * the 2026-10-15 readability pass. */
export const CHART_TICK = { fontSize: 12, fill: CHART_INK.tick } as const

/** The plot's margin inside its box; the card's own padding is the space around it. */
export const CHART_MARGIN = { top: 4, right: 4, bottom: 0, left: 0 } as const

/** A time or category axis along the bottom. */
export const CHART_X_AXIS = {
  tick: CHART_TICK,
  tickLine: false,
  axisLine: { stroke: CHART_INK.line },
  minTickGap: 24,
  height: 20,
} as const

/** Roughly one digit of a 12px tick label, and the gap kept between the labels and the plot. */
const DIGIT_PX = 8
const LABEL_GAP_PX = 8

/**
 * A count axis from zero, its width fitted to the largest label recharts will draw - the top of
 * the "nice" domain, which can be one digit longer than the data's own maximum (95 is drawn on an
 * axis that ends at 100). recharts' own fixed 60px floated every small plot in the middle of its
 * card.
 */
export function chartYAxis(values: readonly number[]) {
  const max = Math.max(0, ...values)
  const digits = String(Math.ceil(max * 1.25) || 1).length
  return {
    tick: CHART_TICK,
    tickLine: false,
    axisLine: false,
    allowDecimals: false,
    width: Math.max(2, digits) * DIGIT_PX + LABEL_GAP_PX,
  } as const
}

/** Horizontal guides only, dotted, in the hairline - the ColumnChart's guides. */
export const CHART_GRID = { stroke: CHART_INK.line, strokeDasharray: '2 4', vertical: false } as const

/**
 * The hover readout: the kit's floating panel. recharts draws its own white box as an INLINE style
 * on the content element, so the content is made transparent and the panel goes on the wrapper,
 * which takes a class. The cursor band under the pointer is the faint caption tint.
 */
export const CHART_TOOLTIP = {
  contentStyle: { fontSize: 13, borderRadius: 10, backgroundColor: 'transparent', border: 'none' },
  wrapperClassName: 'surface-float border rounded-lg shadow-float text-ink-secondary',
  cursor: { fill: 'rgb(var(--ink-caption) / 0.08)' },
} as const
