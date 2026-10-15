import type { ReactNode } from 'react'
import { ListPager as KitListPager, usePagedList } from '@/components/ui/kit'
import { useTranslation } from '@/i18n'

/**
 * THE PAGER UNDER EVERY LIST OF RECORDS in the app (2026-10-14, maintainer's request: at most 20
 * a page - the kit's `LIST_PAGE_SIZE`). The kit `ListPager` with the words in the reader's
 * language: page numbers between Previous and Next from `sm` up, "Page x of y" on a phone, and
 * nothing at all while the list fits on one page.
 *
 * `pages` null is a KEYSET list - the server hands a cursor, not a count, so there is no page
 * number to jump to: Previous and Next only, Next live while `hasMore` says there is another page.
 */
export function ListPager({ page, pages, onPage, countText, hasMore, className }: {
  /** 0-based. */
  page: number
  /** How many pages, or null for a keyset list (see `hasMore`). */
  pages: number | null
  onPage: (page: number) => void
  /** The list's count at the left - "34 conversations" - already worded. */
  countText?: ReactNode
  /** Keyset lists only: whether a page follows this one. */
  hasMore?: boolean
  className?: string
}) {
  const { t } = useTranslation()
  const keyset = pages == null
  // A keyset list knows only the pages behind it and whether one more follows.
  const known = keyset ? page + (hasMore ? 2 : 1) : pages
  return (
    <KitListPager
      className={className}
      page={page}
      pages={known}
      onPage={onPage}
      numbered={!keyset}
      pageLabel={p => t('Page {page}', { page: p + 1 })}
      countText={countText}
      pageText={keyset
        ? t('Page {page}', { page: page + 1 })
        : t('Page {page} of {pages}', { page: page + 1, pages: Math.max(1, known) })}
      previousLabel={t('Previous')}
      nextLabel={t('Next')}
    />
  )
}

/**
 * A LIST PAGED IN THE BROWSER, as one element: `children` draws the current page's records and the
 * pager follows them. For a list the server sends whole because more than the list reads it (a
 * picker, a checklist, a count), where calling `usePagedList` at the top of the component would
 * mean threading it past early returns. The page is kept across a refresh and clamped when the
 * list shrinks; `resetKey` (a search, a filter) sends it back to the first.
 */
export function PagedItems<T>({ items, resetKey, countText, pagerClassName, children }: {
  items: readonly T[]
  resetKey?: unknown
  countText?: ReactNode
  pagerClassName?: string
  children: (pageItems: T[]) => ReactNode
}) {
  const { page, pages, setPage, pageItems } = usePagedList(items, resetKey)
  return (
    <>
      {children(pageItems)}
      <ListPager className={pagerClassName} page={page} pages={pages} onPage={setPage} countText={countText} />
    </>
  )
}
