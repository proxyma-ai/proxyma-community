/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/Sources.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import type { ReactNode } from 'react'
import { Upload } from 'lucide-react'
import { cn } from './cn'
import type { FileDropHandlers } from './DropArea'
import { MetaList } from './MetaList'
import { Skeleton } from './Skeleton'

/**
 * THE SOURCES LIST'S PARTS - its column grid, its header, a group heading, a row and the row's
 * skeleton - presentational, so the app's Sources page and the product scene on proxyma.ai draw
 * the same list. The app works out a source's state (`sourceState`, `statusParts`) and hands the
 * row its words and marks.
 */

/**
 * The columns, shared by the header, the rows and the skeleton so the three cannot drift: Name takes
 * the rest; Items (right-aligned numbers) 4rem; Last sync 6.5rem, the width of its longest value;
 * Status 15rem, truncated with the whole of it on hover. Below `sm` only the name column exists and
 * each row carries its own one-line meta.
 */
export const SOURCE_LIST_COLUMNS = 'sm:grid sm:grid-cols-[minmax(0,1fr)_4rem_6.5rem_15rem] sm:items-center sm:gap-4'

/**
 * The list's box: A CARD since 2026-10-29 (the depth review) - the card plane, its line and
 * `shadow-card` on the canvas, the rows on its text edge (`px-4 sm:px-6`) with no line between them,
 * and a hovered row's fill reaching 12px past them inside it (`ROW_FILL`). It was no box from
 * 2026-10-28 (the UI review: the header's rule and the columns ran 16px past the page column).
 */
export const SOURCE_LIST_BOX = 'kit-card rounded-lg border surface-card shadow-card px-4 sm:px-6 pb-2'

/** A row's hover fill, 12px past the column on each side - the fill only; the row's text is on the column. */
const ROW_FILL = 'before:absolute before:inset-y-0 before:-inset-x-3 before:rounded-md before:pointer-events-none before:transition-colors before:duration-fast'

/** The column headings - sentence case, hidden on a phone. */
export function SourceListHeader({ columns }: { columns: [name: ReactNode, items: ReactNode, synced: ReactNode, status: ReactNode] }) {
  return (
    <div className={cn('hidden py-2 border-b border-line/40 text-xs font-medium text-ink-caption whitespace-nowrap', SOURCE_LIST_COLUMNS)}>
      <span>{columns[0]}</span>
      <span className="text-right">{columns[1]}</span>
      <span>{columns[2]}</span>
      <span>{columns[3]}</span>
    </div>
  )
}

/** A group's heading in a list - "Jira 4" - on the marks' edge, sentence case, the count a readout. */
export function ListGroupHeading({ label, count, className }: { label: ReactNode; count?: number; className?: string }) {
  return (
    <p className={cn('pt-4 pb-2 text-xs font-medium text-ink-caption', className)}>
      {label}
      {count !== undefined && <span className="ml-1 data-readout">{count}</span>}
    </p>
  )
}

/**
 * ONE SOURCE: its type mark, its name (a link out when it has an address), a quiet fact about who
 * else sees it, and - from `sm` - items, last sync and status in their columns; on a phone, one
 * meta line under the name with the health mark pinned right. The whole row opens the source.
 *
 * A healthy source shows nothing in Status (a green check on seven of eight drowned out the one
 * error); `healthyMark` appears only while the row is pointed at or focused. A row that is a drop target says
 * "Drop here" in place of its name.
 */
export function SourceRow({
  mark, name, nameLink, badges, tag, items, synced, status, healthMark, healthyMark, phoneMeta,
  onOpen, busy, drop, dropLabel, ariaLabel,
}: {
  mark: ReactNode
  name: ReactNode
  /** Renders the name as a link out (the app's `ExternalLink`, stopping the row's click). */
  nameLink?: (className: string) => ReactNode
  /** Marks after the name - "processed first". */
  badges?: ReactNode
  /** "shared", "shared with you". */
  tag?: ReactNode
  items: ReactNode
  synced: ReactNode
  /** The status words; absent when healthy. */
  status?: ReactNode
  /** The mark that says something is not fine. */
  healthMark?: ReactNode
  /** The quiet check of a healthy source, shown on hover. */
  healthyMark?: ReactNode
  /** The phone's meta line, already joined ("12 items, 3 min ago"). */
  phoneMeta: ReactNode[]
  onOpen?: () => void
  /** Being deleted: dimmed and inert. */
  busy?: boolean
  /** Drop handling, for a folder that takes dropped files. */
  drop?: { active: boolean; handlers: FileDropHandlers }
  dropLabel?: ReactNode
  ariaLabel?: string
}) {
  const nameCls = 'text-sm font-medium min-w-0 text-ink-primary truncate'
  return (
    <div
      {...(drop?.handlers ?? {})}
      className={cn(
        'group relative block py-3 transition-colors',
        onOpen && cn(ROW_FILL, 'mouse:hover:before:bg-ink-caption/5'),
        busy && 'opacity-60 pointer-events-none',
        drop?.active && 'dropzone-active',
        SOURCE_LIST_COLUMNS,
      )}
    >
      {/* THE ROW OPENS THROUGH ONE BUTTON STRETCHED UNDER IT (since 2026-10-14). The row was a
          role="button" div holding the name's link and marks - interactive content inside a
          button, which assistive technology flattens or misreports. The link and the marks sit
          above this button (`relative z-raised`), so each keeps its own press; anywhere else on
          the row is the button. Its ring is inset: the list's box clips an offset one. */}
      {onOpen && (
        <button
          type="button"
          onClick={onOpen}
          aria-label={ariaLabel}
          className="absolute inset-0 w-full cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-solid"
        >
          {!ariaLabel && <span className="sr-only">{name}</span>}
        </button>
      )}
      <div className="flex items-center gap-3 min-w-0">
        <span className="shrink-0 flex items-center text-brand-legible [&>svg]:w-4 [&>svg]:h-4">{mark}</span>
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2 min-w-0">
            {drop?.active ? (
              <span className="flex items-center gap-2 text-sm font-medium text-success">
                <Upload size={16} aria-hidden="true" /> {dropLabel}
              </span>
            ) : nameLink ? <span className="relative z-raised min-w-0 flex">{nameLink(cn(nameCls, 'mouse:hover:text-brand-legible'))}</span> : <span className={cn(nameCls, 'pointer-events-none')}>{name}</span>}
            {badges && <span className="relative z-raised flex items-center gap-2">{badges}</span>}
            {tag && <span className="shrink-0 text-xs text-ink-caption">{tag}</span>}
            <span className="relative z-raised sm:hidden shrink-0 ml-auto flex items-center">{healthMark}</span>
          </div>
          <MetaList items={phoneMeta} className="sm:hidden mt-0.5 text-xs text-ink-caption truncate" />
        </div>
      </div>
      <span className="hidden sm:block text-sm text-ink-secondary data-readout text-right">{items}</span>
      <span className="hidden sm:block text-xs text-ink-caption truncate">{synced}</span>
      <span className="hidden sm:flex items-center gap-2 min-w-0 text-xs text-ink-caption">
        {healthMark && status && <span className="shrink-0 flex items-center">{healthMark}</span>}
        {status && <span className="min-w-0 truncate">{status}</span>}
        {healthyMark && (
          <span className="opacity-0 mouse:group-hover:opacity-100 group-focus-within:opacity-100 transition-opacity flex items-center">{healthyMark}</span>
        )}
      </span>
    </div>
  )
}

/** A row's skeleton, on the same columns and padding as the row. */
export function SourceRowSkeleton() {
  return (
    <div className={cn('py-3', SOURCE_LIST_COLUMNS)}>
      <div className="flex items-center gap-3"><Skeleton className="h-4 w-4 rounded-full" /><Skeleton className="h-3.5 w-40" /></div>
      <Skeleton className="hidden sm:block h-3 w-8 ml-auto" />
      <Skeleton className="hidden sm:block h-3 w-16" />
      <Skeleton className="hidden sm:block h-3 w-24" />
    </div>
  )
}

/**
 * ONE ITEM INSIDE A SOURCE (a document, a page, an issue) or ONE FILE IN AN UPLOAD: a status mark,
 * the name (linked when it has an address), a meta line in caption ink - it was in disabled ink,
 * which is for what nobody needs to read - an error in full under it, and actions at the right.
 */
export function ItemRow({ statusMark, title, titleLink, meta, metaTruncate, error, trailing, actions, struck, className }: {
  statusMark?: ReactNode
  title: ReactNode
  titleLink?: (className: string) => ReactNode
  meta?: ReactNode[]
  /**
   * One line of meta, cut with an ellipsis - for a VIRTUALIZED list, which sizes a row before it
   * draws it and cannot have one wrap to two.
   */
  metaTruncate?: boolean
  error?: ReactNode
  /** A control in the row - an upload's Skip/Overwrite choice, a progress bar. */
  trailing?: ReactNode
  actions?: ReactNode
  /** A file that will not be uploaded. */
  struck?: boolean
  className?: string
}) {
  const titleCls = cn('text-sm leading-5 text-ink-primary truncate', struck && 'line-through text-ink-disabled')
  return (
    <div className={cn('group flex items-start gap-3 py-2', className)}>
      {statusMark && <span className="shrink-0 mt-0.5 flex items-center">{statusMark}</span>}
      <div className="min-w-0 flex-1">
        {/* `flex w-fit`, not `block`: a linked title ends in the external-link glyph, and Tailwind's
            preflight makes every svg `display: block`, so in a block anchor the glyph took a line
            of its own under the title. As a flex row it sits right after the (truncated) name. */}
        {titleLink ? titleLink(cn(titleCls, 'flex w-fit max-w-full mouse:hover:text-brand-legible')) : <p className={titleCls}>{title}</p>}
        {meta && meta.length > 0 && <MetaList items={meta} className={cn('text-xs text-ink-caption', metaTruncate && 'truncate')} />}
        {error && <p className="mt-0.5 text-xs text-danger break-words">{error}</p>}
      </div>
      {trailing && <div className="shrink-0">{trailing}</div>}
      {actions && <div className="shrink-0 flex items-center gap-1">{actions}</div>}
    </div>
  )
}

/** An `ItemRow` loading: its mark, a title line over a meta line, on the row's own padding. */
export function ItemRowSkeleton({ className }: { className?: string }) {
  return (
    <div className={cn('flex items-start gap-3 py-2', className)} aria-hidden="true">
      <Skeleton className="h-4 w-4 rounded-full mt-0.5 shrink-0" />
      <div className="min-w-0 flex-1 space-y-2">
        <Skeleton className="h-3.5 w-3/5" />
        <Skeleton className="h-3 w-2/5" />
      </div>
    </div>
  )
}
