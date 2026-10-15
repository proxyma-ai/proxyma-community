/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/usePaging.ts
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import { useState } from 'react'
import { clampPage, LIST_PAGE_SIZE, pageCount, pageSlice } from './pageWindow'

/**
 * THE PAGE A PAGED LIST IS ON, back to the first whenever what it lists changes - a filter, a
 * search, a period, a tab. Held beside the key rather than reset by an effect, so a changed filter
 * never shows, or asks the server for, its page 7 even for one render. Anything else - a refetch, a
 * row edited, the same list arriving again - keeps the page the reader is on.
 *
 * `key` is anything JSON can write; pass what the list is OF, never the records themselves.
 */
export function usePageFor(key: unknown): { page: number; setPage: (page: number) => void } {
  const k = JSON.stringify(key ?? null)
  const [state, setState] = useState({ key: k, page: 0 })
  return {
    page: state.key === k ? state.page : 0,
    setPage: page => setState({ key: k, page: Math.max(0, Math.floor(page) || 0) }),
  }
}

/**
 * A LIST PAGED IN THE BROWSER - for a list the server sends whole because something else on the page
 * needs all of it (a picker, a count, a client-side search). Twenty at a time (`LIST_PAGE_SIZE`),
 * the page kept across a refresh and clamped when the list shrinks under the reader, back to the
 * first when `resetKey` (the search, the filter) changes. Hand `page`, `pages` and `setPage` to
 * `ListPager`, which shows only past one page.
 */
export function usePagedList<T>(items: readonly T[], resetKey?: unknown, size: number = LIST_PAGE_SIZE): {
  page: number
  pages: number
  setPage: (page: number) => void
  /** The records on the current page. */
  pageItems: T[]
  total: number
} {
  const { page, setPage } = usePageFor(resetKey)
  const pages = pageCount(items.length, size)
  const current = clampPage(page, pages)
  return { page: current, pages, setPage, pageItems: pageSlice(items, current, size), total: items.length }
}
