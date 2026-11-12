import { CornerLeftUp } from 'lucide-react'
import { Fragment, createContext, useContext, type ReactNode } from 'react'
import { ExternalLink, MetaList, Readout, TEXT, Tooltip, cn } from '@/components/ui/kit'
import { useTranslation } from '@/i18n'
import {
  approvalContextRows, commentCard, foldCommentAge, foldContextLinks,
  type ApprovalContext, type ApprovalContextRow, type CommentCard,
} from '../utils/approvalContext'

/**
 * A person's words quoted in an approval's context: their comment in secondary ink on a plain line, the
 * reply - what would be posted - in primary ink on the brand accent's line, so it cannot be read as the
 * viewer's. Exported so a page drawing a row of its own (the inbox's edited reply) quotes the same way.
 */
export function ApprovalQuote({ accent, children }: { accent?: boolean; children: ReactNode }) {
  return (
    <blockquote className={cn('border-l-2 pl-3', accent ? 'border-brand-accent text-ink-primary' : 'border-line text-ink-secondary')}>
      {children}
    </blockquote>
  )
}

/**
 * A row's value as the card draws it: a link, a quote (the reply in the accent), or plain text, followed
 * on the same line by the row's own links (the viewer's: the video, their comment, a posted reply).
 * Exported so a page drawing a row of its own keeps the links.
 */
export function ApprovalRowValue({ row }: { row: ApprovalContextRow }) {
  const value = row.kind === 'LINK' && row.href ? (
    <ExternalLink href={row.href} labelClassName="break-words">{row.value}</ExternalLink>
  ) : row.kind === 'QUOTE' ? (
    <ApprovalQuote accent={row.key === 'reply'}>{row.value}</ApprovalQuote>
  ) : row.value
  const links = row.links ?? []
  if (links.length === 0) return <>{value}</>
  return (
    <span className="flex flex-wrap items-baseline gap-x-4 gap-y-1">
      {value}
      {links.map(l => (
        <Tooltip key={l.key} label={l.label}>
          <ExternalLink href={l.href} className="min-w-0 max-w-full" labelClassName="inline-block max-w-[32ch] truncate align-bottom">
            {l.label}
          </ExternalLink>
        </Tooltip>
      ))}
    </span>
  )
}

// Whether the grid around an `ApprovalField` stacks every label over its value (a comment's card), or puts
// the labels in a column of their own once it is wide enough (every other card).
const Stacked = createContext(false)

/**
 * THE CARD'S FACTS, label beside value (the Approvals page's alignment review, 2026-11-03: "Should have a
 * better alignment"). One grid: the labels in a column as wide as the longest of them - at least 8rem,
 * which holds every label the page knows in Vietnamese too ("Ban nhap cua tac nhan" is 120px), so cards
 * one under another put their values on one edge; never past 12rem, where a label of a key the page does
 * not know wraps - and every value of a card on one left edge. Laid out by its
 * OWN width, not the window's, since it is drawn in a chat's approval prompt and in the inbox's cards:
 * below 36rem each label stacks over its value. The container is this wrapper - a container query cannot
 * ask the element it styles - and each class is written out whole, so Tailwind finds it in the source.
 *
 * `stacked` (2026-11-05, a comment's card): every label over its value at any width - the draft and the
 * note under the comment's title read top to bottom, with no column of labels to align.
 */
export function ApprovalFields({ className, stacked = false, children }: { className?: string; stacked?: boolean; children: ReactNode }) {
  if (stacked) {
    return (
      <div className={className}>
        <Stacked.Provider value>
          <dl className="grid grid-cols-1 gap-y-1 [&>div:first-child>dt]:pt-0 [&>div:first-child>dd]:pt-0">{children}</dl>
        </Stacked.Provider>
      </div>
    )
  }
  return (
    <div className={cn('[container-type:inline-size]', className)}>
      <dl className={cn(
        'grid grid-cols-1 gap-y-1 [&>div:first-child>dt]:pt-0',
        '[@container(min-width:36rem)]:grid-cols-[minmax(8rem,max-content)_minmax(0,1fr)]',
        '[@container(min-width:36rem)]:gap-x-4 [@container(min-width:36rem)]:gap-y-2',
      )}>
        {children}
      </dl>
    </div>
  )
}

/**
 * One fact of an `ApprovalFields` grid. `aside` is a quiet caption that belongs to the label - how long ago
 * a comment was written - drawn under it while the labels have a column of their own and after it once
 * they stack, so the label itself never grows. An empty `label` (the note's "Add note") keeps the value
 * on the values' edge with nothing beside it. `block` keeps the value's own line breaks.
 *
 * `marks` are badges that rate the value - a draft's confidence, "Revised" (2026-11-08, the maintainer: the
 * confidence "is about the reply draft. So it should be next to the reply writer's draft"): on the label's own
 * line once the labels stack, under the label while they have a column of their own.
 */
export function ApprovalField({ label, aside, marks, block, children }: {
  label: ReactNode
  aside?: ReactNode
  marks?: ReactNode
  block?: boolean
  children: ReactNode
}) {
  const stacked = useContext(Stacked)
  const unlabeled = label === '' || label === null || label === undefined
  if (stacked) {
    return (
      <div className="contents">
        {/* With marks the label's line is a row that wraps, so marks that do not fit start on the label's edge. */}
        <dt className={cn(TEXT.caption, 'min-w-0 break-words pt-3', unlabeled && 'hidden',
          marks && 'flex flex-wrap items-center gap-x-2 gap-y-1')}>
          {label}
          {aside && <span className={cn('data-readout', !marks && 'ml-2')}>{aside}</span>}
          {marks && <span className="inline-flex flex-wrap items-center gap-1.5">{marks}</span>}
        </dt>
        <dd className={cn('min-w-0 break-words text-xs text-ink-primary', unlabeled && 'pt-3', block && 'whitespace-pre-wrap')}>
          {children}
        </dd>
      </div>
    )
  }
  return (
    // `contents`: the pair is grouped for valid markup, and the grid still lays out its two cells.
    <div className="contents">
      <dt className={cn(
        TEXT.caption, 'min-w-0 break-words pt-3',
        '[@container(min-width:36rem)]:pt-0 [@container(min-width:36rem)]:max-w-48',
        unlabeled && 'hidden [@container(min-width:36rem)]:block',
      )}>
        {label}
        {aside && (
          <span className="data-readout ml-2 [@container(min-width:36rem)]:ml-0 [@container(min-width:36rem)]:block">
            {aside}
          </span>
        )}
        {marks && (
          <span className="ml-2 inline-flex flex-wrap items-center gap-1.5 align-middle [@container(min-width:36rem)]:ml-0 [@container(min-width:36rem)]:mt-1 [@container(min-width:36rem)]:flex">
            {marks}
          </span>
        )}
      </dt>
      <dd className={cn(
        'min-w-0 break-words text-xs text-ink-primary',
        unlabeled && 'pt-3 [@container(min-width:36rem)]:pt-0',
        block && 'whitespace-pre-wrap',
      )}>
        {children}
      </dd>
    </div>
  )
}

/**
 * THE CONVERSATION A REPLY IS PART OF, over its title (2026-11-10, the maintainer: "which comment belong to which
 * conversation"): "In reply to <who opened the thread>: <their words>" on one quiet line, cut short with the whole of
 * it in its tooltip, so the person sees whom a draft in that thread answers. Nothing for a comment that opens its own.
 */
export function ThreadLine({ card, className }: { card: CommentCard; className?: string }) {
  const { t } = useTranslation()
  if (!card.thread) return null
  const line = t('In reply to {name}: {text}', { name: card.thread.author, text: card.thread.text.replace(/\s+/g, ' ') })
  return (
    <p className={cn(TEXT.caption, 'flex min-w-0 items-center gap-1.5 [contain:inline-size]', className)}>
      <CornerLeftUp size={12} className="shrink-0" aria-hidden="true" />
      <Tooltip label={card.thread.text} className="min-w-0 flex">
        <span className="truncate">{line}</span>
      </Tooltip>
    </p>
  )
}

/**
 * A COMMENT'S TITLE: the viewer's words, whole, as the link to where they sit on the video - in label
 * weight, so a card reads as the comment first, and in the link ink with the link's hover and focus ring, as the
 * viewer and the video on the caption line under it are (2026-11-08, the maintainer: it was "currently white, so
 * it should have the accent color just like the hyperlink of the user ID and the video's link"). Wraps anywhere and keeps the viewer's own
 * line breaks; never clamped, since the kit has no "see it whole" pattern and a comment is what is being
 * answered. Exported so the Approvals page puts it on its card's header line.
 */
export function CommentTitle({ card, className }: { card: CommentCard; className?: string }) {
  const { t } = useTranslation()
  const words = <span className="whitespace-pre-line [overflow-wrap:anywhere]">{card.comment.text}</span>
  if (!card.comment.href) return <p className={cn(TEXT.label, 'min-w-0', className)}>{words}</p>
  return (
    <p className={cn(TEXT.label, 'min-w-0', className)}>
      <Tooltip label={t('View comment')} className="inline">
        {/* `inline`, not the link's own inline-flex, and its glyph inline too (the preflight makes an svg a
            block): a title of three lines wraps as text, its glyph after its last word. */}
        <ExternalLink href={card.comment.href} className="inline [&>svg]:inline" labelClassName="mr-1">
          {words}
        </ExternalLink>
      </Tooltip>
    </p>
  )
}

/**
 * A COMMENT'S CAPTION LINE, under its title: when it was written (in the reader's locale, its age in the
 * tooltip), the viewer as a link to their channel and the video as a link to it, joined as every caption's
 * facts are (MetaList). A long name or title is cut short with the whole of it in its tooltip; the line
 * wraps between facts on a narrow card. Exported so the Approvals page draws it under its header line, with its
 * own facts after the video (`extra`: the comment's tags and how long ago the draft was made, 2026-11-08).
 */
export function CommentFacts({ card, className, extra = [] }: { card: CommentCard; className?: string; extra?: ReactNode[] }) {
  const items: ReactNode[] = []
  if (card.written) {
    items.push(
      <Tooltip label={card.written.ago}>
        <Readout tone="caption"><time dateTime={card.written.iso}>{card.written.at}</time></Readout>
      </Tooltip>,
    )
  }
  if (card.viewer) items.push(<FactLink text={card.viewer.name} href={card.viewer.href} width="max-w-[24ch]" />)
  if (card.video) items.push(<FactLink text={card.video.title} href={card.video.href} width="max-w-[36ch]" />)
  items.push(...extra)
  if (items.filter(Boolean).length === 0) return null
  // Each fact at most the line's width, so a long title is cut short on a narrow card rather than running past it.
  return <MetaList className={cn(TEXT.caption, 'min-w-0 [&>span]:max-w-full', className)} items={items} />
}

// A fact of the caption line, cut to `width` with the whole of it in the tooltip; a link when it has an address.
function FactLink({ text, href, width }: { text: string; href: string | null; width: string }) {
  const label = cn('inline-block truncate align-bottom', width)
  return (
    <Tooltip label={text} className="inline-flex min-w-0 max-w-full">
      {href
        ? <ExternalLink href={href} className="min-w-0" labelClassName={label}>{text}</ExternalLink>
        : <span className={label}>{text}</span>}
    </Tooltip>
  )
}

/** A comment's title over its caption line - what a card holding no header of its own draws first. */
export function CommentHeading({ card, className }: { card: CommentCard; className?: string }) {
  return (
    <div className={cn('min-w-0 space-y-1', className)}>
      <ThreadLine card={card} />
      <CommentTitle card={card} />
      <CommentFacts card={card} />
    </div>
  )
}

/**
 * WHAT A WRITE WAITING FOR APPROVAL IS ABOUT, as the server described it (approvalContext.ts). Used by the
 * chat's approval card, the Constellations approvals inbox and a dashboard's comments. Presentational: the
 * context comes in as a prop. It has no fill or frame of its own: it is the content of whatever card holds it.
 *
 * A YOUTUBE COMMENT (2026-11-05, the maintainer: "redesign and simplify the comment proposal card"): its
 * TITLE is the comment, linked to it; ONE CAPTION LINE under it says when, who (linked to their channel) and
 * on which video (linked); then the agent's drafted reply, labeled with whose draft it is (`author`) and
 * quoted on the brand accent's line; then whatever the page adds (`children` - the note). There is no
 * "reply as" row: a reply is always the channel owner's, and the server refuses one for another channel.
 * That replaces the label/value grid of 2026-11-02/03 for a comment - the viewer with the video, their
 * comment and the posted reply folded beside them (foldContextLinks), the comment's age under its label
 * (foldCommentAge) - and with it the label column the alignment review sized, which a stacked card has no
 * use for. `heading={false}` leaves the title and caption line out, for a page that draws them in its own
 * header (CommentTitle, CommentFacts): the Approvals page does. A comment with no reply (a dashboard's) is
 * its title and caption line alone.
 *
 * ANY OTHER CONTEXT keeps the label/value card (ApprovalFields): the labels in a column, the reply in the
 * accent. A page drawing the reply itself - the inbox's editable one - does it through `renderRow` (given
 * the row and the line this card would draw; what it returns is drawn instead, one or more
 * `ApprovalField`s) in either layout, and adds its own lines as `children`, after the context's.
 * `draftMarks` are drawn beside the label of the row whose key it names (ApprovalField `marks`) - the draft's
 * confidence on the Approvals page; a page drawing that row itself draws them too.
 */
export function ApprovalContextCard({ context, className, renderRow, author, heading = true, draftMarks, children }: {
  context: ApprovalContext
  className?: string
  renderRow?: (row: ApprovalContextRow, standard: ReactNode) => ReactNode
  /** Whose draft a comment's reply is - the agent that wrote it; the reply's own label when not given. */
  author?: string | null
  /** Whether a comment's card draws its title and caption line (false where the page draws them itself). */
  heading?: boolean
  /** Badges beside one row's label, by that row's key - the draft's confidence. */
  draftMarks?: { key: string; marks: ReactNode } | null
  children?: ReactNode
}) {
  const { t, locale } = useTranslation()
  const draw = (row: ApprovalContextRow, standard: ReactNode) =>
    renderRow ? <Fragment key={row.key}>{renderRow(row, standard)}</Fragment> : standard
  const marksOf = (row: ApprovalContextRow) => (draftMarks && draftMarks.key === row.key ? draftMarks.marks : undefined)

  const comment = commentCard(context, t, locale, { author })
  if (comment) {
    const lines = [...(comment.reply ? [comment.reply] : []), ...comment.rest]
    const fields = lines.length > 0 || children
    if (!heading && !fields) return null
    return (
      <div className={cn('min-w-0', className)}>
        {heading && <CommentHeading card={comment} />}
        {fields && (
          <ApprovalFields stacked className={cn(heading && 'mt-3')}>
            {lines.map(row => draw(row, (
              <ApprovalField key={row.key} label={row.label} marks={marksOf(row)} block={row.kind === 'QUOTE'}>
                <ApprovalRowValue row={row} />
              </ApprovalField>
            )))}
            {children}
          </ApprovalFields>
        )}
      </div>
    )
  }

  const rows = foldCommentAge(foldContextLinks(approvalContextRows(context, t), t), locale)
  if (rows.length === 0) return null
  return (
    <ApprovalFields className={className}>
      {rows.map(row => draw(row, (
        <ApprovalField key={row.key} label={row.label} aside={row.age} marks={marksOf(row)} block={row.kind === 'QUOTE'}>
          <ApprovalRowValue row={row} />
        </ApprovalField>
      )))}
      {children}
    </ApprovalFields>
  )
}
