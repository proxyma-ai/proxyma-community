/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/pageWindow.ts
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

/**
 * WHICH PAGE NUMBERS A PAGER SHOWS (2026-10-12, maintainer's request: "for a large data list, use
 * page numbers and next/prev buttons").
 *
 * Every page while there are few; past that the first, the last, and the pages around the current
 * one, with a gap (null) where pages are left out - `1 ... 4 5 6 ... 20`. The row keeps the same
 * number of slots wherever the current page is, so the buttons do not jump about under the pointer
 * while somebody steps through with Next. Plain `.ts` with no imports, so `node --test` reaches it.
 *
 * @param page   0-based current page
 * @param pages  how many pages there are
 * @param around how many pages either side of the current one stay visible
 * @returns 0-based page numbers in order, with null for each gap
 */
export function pageWindow(page: number, pages: number, around = 1): (number | null)[] {
  if (pages <= 0) return []
  const current = Math.min(Math.max(0, page), pages - 1)
  // First, last, the current page and `around` either side, plus one slot per possible gap.
  const slots = 2 * around + 5
  if (pages <= slots) return Array.from({ length: pages }, (_, i) => i)

  // Near an end the window slides inward rather than shrinking, so the count of slots holds.
  const span = slots - 2                       // everything but the first and the last
  let start = current - around - 1
  let end = current + around + 1
  if (start < 1) { start = 1; end = span }
  if (end > pages - 2) { end = pages - 2; start = pages - 1 - span }

  const out: (number | null)[] = [0]
  for (let i = start; i <= end; i++) {
    if ((i === start && i > 1) || (i === end && i < pages - 2)) out.push(null)
    else out.push(i)
  }
  out.push(pages - 1)
  return out
}

/**
 * HOW MANY RECORDS A LIST SHOWS AT ONCE, in all four apps (2026-10-14, maintainer's request: "in
 * every list, show max 20 items each page"). One number, so no list drifts to its own: a list paged
 * in the browser slices by it (`pageSlice`, `usePagedList`), a list paged by the server asks for it
 * as `size`, and the kit `ListPager` appears only once a list holds more than this.
 */
export const LIST_PAGE_SIZE = 20

/** How many pages `total` records fill - 0 for none, so a pager with no pages never shows. */
export function pageCount(total: number, size: number = LIST_PAGE_SIZE): number {
  if (!(total > 0) || !(size > 0)) return 0
  return Math.ceil(total / size)
}

/**
 * A 0-based page kept inside `pages` - the last page when the list shrank under the reader (a row
 * deleted on the last page, a refresh that brought fewer), the first when there are none.
 */
export function clampPage(page: number, pages: number): number {
  if (!(pages > 0) || !(page > 0)) return 0
  return Math.min(Math.floor(page), pages - 1)
}

/** The records on one page of a list paged in the browser, the page clamped first. */
export function pageSlice<T>(items: readonly T[], page: number, size: number = LIST_PAGE_SIZE): T[] {
  const current = clampPage(page, pageCount(items.length, size))
  return items.slice(current * size, current * size + size)
}
