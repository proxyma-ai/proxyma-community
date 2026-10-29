import type { ComponentType, ReactNode } from 'react'
import { AlertCircle, Check } from 'lucide-react'
import { Button } from './Button'
import { cn } from './cn'
import { pageWindow } from './pageWindow'
import { Presence } from './Presence'
import { Spinner } from './Spinner'
import { TEXT } from './typeScale'

/**
 * What a list, table, chart or region shows when it has nothing in it: ONE left-aligned sentence
 * saying what appears here, an optional title over it, and the one action that makes it appear.
 *
 * No icon, nothing centred (2026-09-30, app UI review F12): the app had five empty-state styles - a
 * plus in a grey tile, a speech bubble over centred text, a bordered box - and the consoles kept a
 * centred icon after the app dropped it, because this component never reached the kit. Keep the
 * action to one: an empty state with a menu of options is a page in disguise.
 */
export function EmptyState({ children, title, action, className }: {
  /** The sentence: what appears here, and when. */
  children: ReactNode
  title?: ReactNode
  action?: ReactNode
  className?: string
}) {
  return (
    // The three states fade in: each replaces a skeleton or a list, and arriving in one frame read
    // as a flash.
    <div className={cn('py-4 text-left animate-fade-in', className)}>
      {title && <p className={cn(TEXT.label, 'mb-0.5')}>{title}</p>}
      <p className={cn(TEXT.option, 'text-ink-caption max-w-prose')}>{children}</p>
      {action && <div className="mt-3">{action}</div>}
    </div>
  )
}

/**
 * A region loading something with no shape to imitate (a preview, a graph): the kit Spinner and a
 * caption. A list or a form loads with SKELETONS shaped like what is coming, never this - and
 * never "Loading..." on its own.
 */
export function LoadingState({ label, className, centered }: { label?: string; className?: string; centered?: boolean }) {
  return (
    <div role="status" className={cn('flex items-center gap-2 py-4 text-xs text-ink-caption animate-fade-in', centered && 'justify-center h-full', className)}>
      <Spinner size="sm" />
      {label && <span>{label}</span>}
    </div>
  )
}

/** Something that failed to load: a title, what to know, and the one way to try again. */
export function ErrorState({ title, children, action, className }: { title: ReactNode; children?: ReactNode; action?: ReactNode; className?: string }) {
  return (
    <div role="alert" className={cn('py-4 text-left animate-fade-in', className)}>
      <p className={cn(TEXT.label, 'flex items-center gap-2 text-danger')}>
        <AlertCircle size={14} className="shrink-0" aria-hidden="true" />
        {title}
      </p>
      {children && <div className={cn(TEXT.caption, 'mt-1 max-w-prose')}>{children}</div>}
      {action && <div className="mt-3">{action}</div>}
    </div>
  )
}

export type NoticeTone = 'neutral' | 'info' | 'progress' | 'done' | 'success' | 'warning' | 'danger'

/**
 * ONE LINE OF STATUS - under a field, in a dialog body, in a prompt card's action row: a test
 * result, a save result, "Uploading 3 files", a refusal. The tone picks the glyph (the kit Spinner
 * for `progress`) and the ink. About 25 of these were hand-made at two sizes, with and without an
 * icon. `size="md"` (14px) for a body notice, `sm` (13px) under a field.
 *
 * THE KIT'S TONES, as every part names them (2026-10-14): `neutral` caption ink (the default - a
 * plain note), `info` the brand's ink (as in Callout, Toast and FormAlert; it was caption ink here
 * alone), `success` green for a VERDICT (a test passed), `done` a brand tick and caption words for
 * WORK FINISHED (the maintainer's rule for work done is the brand's ink, not green - "Condensed",
 * "Uploaded"), `warning`, `danger`, and `progress` with the Spinner.
 */
export function InlineNotice({ tone = 'neutral', children, action, size = 'sm', icon: Icon, className }: {
  tone?: NoticeTone
  children: ReactNode
  action?: ReactNode
  size?: 'sm' | 'md'
  icon?: ComponentType<{ size?: number | string; className?: string }>
  className?: string
}) {
  const ink = tone === 'danger' ? 'text-danger'
    : tone === 'warning' ? 'text-warning'
      : tone === 'success' ? 'text-success'
        : tone === 'info' ? 'text-brand-legible'
          : 'text-ink-caption'
  const glyph = size === 'md' ? 16 : 14
  return (
    <div
      role={tone === 'danger' ? 'alert' : 'status'}
      className={cn('flex items-start gap-2 animate-fade-in', size === 'md' ? 'text-sm' : 'text-xs', ink, className)}
    >
      {tone === 'progress'
        ? <Spinner size={glyph} className="mt-0.5" />
        : tone === 'done' && !Icon
          ? <Check size={glyph} className="shrink-0 mt-0.5 text-brand-legible" aria-hidden="true" />
          : Icon && <Icon size={glyph} className={cn('shrink-0 mt-0.5', tone === 'done' && 'text-brand-legible')} aria-hidden="true" />}
      <span className="min-w-0 flex-1">{children}</span>
      {action && <span className="shrink-0">{action}</span>}
    </div>
  )
}

/**
 * The row at the end of a paged list: "Load more" as a text link, or the loading line while the
 * next page comes. `py-3`, one weight - it was `py-3` here and `py-4` there, semibold and regular.
 * `sentinelRef` is for an infinite list that loads on reaching it.
 */
export function LoadMoreRow({ loading, label, loadingLabel, onLoadMore, sentinelRef, className }: {
  loading?: boolean
  label: string
  loadingLabel: string
  onLoadMore?: () => void
  sentinelRef?: (el: HTMLDivElement | null) => void
  className?: string
}) {
  return (
    <div ref={sentinelRef} className={cn('py-3 flex items-center', className)}>
      {loading
        ? <span className="flex items-center gap-2 text-xs text-ink-caption"><Spinner size="sm" />{loadingLabel}</span>
        : onLoadMore && <Button variant="hyperlink" onClick={onLoadMore}>{label}</Button>}
    </div>
  )
}

/**
 * Previous, page x of y, next - with the list's count at the left.
 *
 * `numbered` puts the page numbers between the two buttons from `sm` up - the first, the last and
 * the pages around the current one (`pageWindow`) - for a long list somebody jumps around in; a
 * phone keeps "page x of y", which is all its width holds. `pageLabel` names a number for a screen
 * reader ("Page 3").
 */
export interface PagerProps {
  /** 0-based. */
  page: number
  pages: number
  onPage: (page: number) => void
  countText?: ReactNode
  /** "Page 2 of 7", already formatted. */
  pageText: ReactNode
  previousLabel: string
  nextLabel: string
  numbered?: boolean
  pageLabel?: (page: number) => string
  className?: string
}

export function Pager({ page, pages, onPage, countText, pageText, previousLabel, nextLabel, numbered, pageLabel, className }: PagerProps) {
  return (
    <div className={cn('flex flex-wrap items-center justify-between gap-3 py-3 text-xs', className)}>
      <span className="text-ink-caption data-readout">{countText}</span>
      <div className="flex items-center gap-3">
        <Button variant="secondary" onClick={() => onPage(page - 1)} disabled={page <= 0}>{previousLabel}</Button>
        {numbered && pages > 1 && (
          <span className="hidden sm:flex items-center gap-1">
            {pageWindow(page, pages).map((p, i) => p == null ? (
              <span key={`gap-${i}`} className="px-1 text-ink-caption" aria-hidden="true">...</span>
            ) : (
              <button
                key={p}
                type="button"
                onClick={() => onPage(p)}
                aria-current={p === page ? 'page' : undefined}
                aria-label={pageLabel?.(p)}
                className={cn(
                  'min-w-8 h-8 touch:min-w-11 touch:h-11 px-2 rounded-md data-readout focus-ring',
                  p === page
                    ? 'border border-line bg-surface-alt text-ink-primary font-semibold'
                    : 'text-ink-caption mouse:hover:text-ink-primary',
                )}
              >
                {p + 1}
              </button>
            ))}
          </span>
        )}
        <span className={cn('text-ink-caption data-readout', numbered && pages > 1 && 'sm:hidden')}>{pageText}</span>
        <Button variant="secondary" onClick={() => onPage(page + 1)} disabled={page >= pages - 1}>{nextLabel}</Button>
      </div>
    </div>
  )
}

/**
 * THE PAGER UNDER A LIST OF RECORDS (2026-10-14, maintainer's request: every list shows at most 20
 * a page - `LIST_PAGE_SIZE`). The `Pager`, shown only while there is more than one page, so a list
 * of twenty or fewer carries no pager at all; it eases in and out with `Presence` when a filter or
 * a delete crosses that line. Each app supplies the words through a thin wrapper, as with every
 * kit part that would otherwise need `t()`.
 */
export function ListPager(props: PagerProps) {
  return (
    <Presence show={props.pages > 1}>
      <Pager {...props} />
    </Presence>
  )
}
