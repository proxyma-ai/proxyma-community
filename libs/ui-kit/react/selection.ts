/**
 * A list's multi-select, as pure functions so `node --test` can reach them - the telemetry
 * console's feedback list and the app's Settings > Memory (with SelectBox beside it).
 *
 * Selection is a set of ids rather than of row indexes: a row that leaves the page after an
 * action must not hand its checkmark to whichever row slid into its place.
 */

/**
 * Applies one checkbox click.
 *
 * With shift held and an anchor still on the page, every row between the anchor and the clicked
 * row takes the clicked row's NEW state - the file-manager convention, where a shift-click can
 * clear a run as well as fill one. Without either, only the clicked row changes.
 */
export function applyClick(
  selected: ReadonlySet<string>,
  pageIds: readonly string[],
  anchor: string | null,
  target: string,
  checked: boolean,
  shift: boolean,
): Set<string> {
  const next = new Set(selected)
  const to = pageIds.indexOf(target)
  const from = shift && anchor !== null ? pageIds.indexOf(anchor) : -1
  const ids = from >= 0 && to >= 0
    ? pageIds.slice(Math.min(from, to), Math.max(from, to) + 1)
    : [target]
  for (const id of ids) {
    if (checked) next.add(id)
    else next.delete(id)
  }
  return next
}

export type HeaderState = 'none' | 'some' | 'all'

/** What the header checkbox shows for the rows on this page. */
export function headerState(selected: ReadonlySet<string>, pageIds: readonly string[]): HeaderState {
  let on = 0
  for (const id of pageIds) if (selected.has(id)) on++
  if (on === 0) return 'none'
  return on === pageIds.length ? 'all' : 'some'
}

/**
 * The header checkbox's click: a page that is fully selected clears, anything else fills. Rows
 * selected on no longer visible pages are kept either way - "select all matching" can hold ids
 * this page does not show.
 */
export function toggleAllOnPage(selected: ReadonlySet<string>, pageIds: readonly string[]): Set<string> {
  const next = new Set(selected)
  if (headerState(selected, pageIds) === 'all') {
    for (const id of pageIds) next.delete(id)
  } else {
    for (const id of pageIds) next.add(id)
  }
  return next
}
