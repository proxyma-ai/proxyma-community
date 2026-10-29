import type { ComponentType, ReactNode } from 'react'
import { Check, SquareArrowOutUpRight } from 'lucide-react'
import { cardClass } from './Card'
import { cn } from './cn'
import { FadeSwap } from './FadeSwap'
import { PAGE_COLUMN, PAGE_GUTTER, PROSE_MEASURE } from './layout'
import { TEXT } from './typeScale'

/**
 * THE MARKETING SITE'S PATTERNS - sections, their headings, plan cards, the FAQ, a link card, the
 * scene frame the product pictures sit in. In the kit rather than the site so the landing page is
 * built from the same parts as everything else, and so a product scene can be built from the
 * product's own components (`SceneFrame` takes kit chat, source and settings parts).
 *
 * No `'use client'` here: these are server-renderable, so a page can pass them an icon component.
 * The two that hold state - `FaqItem` and `SceneFrame` - are in MarketingClient.tsx.
 */

/**
 * A home-page section: the page column, a rule above it, `py-16 sm:py-24`. `compact` is the band
 * between two large sections (the trust strip); `first` drops the rule.
 */
export function PageSection({ id, children, size = 'default', first, tone = 'page', ruled = true, className, innerClassName }: {
  id?: string
  children: ReactNode
  size?: 'default' | 'compact'
  first?: boolean
  /**
   * `page` (the default): on the page's own plane. `sheet`: a band of the card plane across the
   * window, lined above and below - a sheet laid on the page, its depth - and never ruled.
   */
  tone?: 'page' | 'sheet'
  /** The rule above it (the default); `false` where the spacing, or a sheet beside it, separates. */
  ruled?: boolean
  className?: string
  innerClassName?: string
}) {
  return (
    <section id={id} className={cn(
      size === 'default' ? 'py-16 sm:py-24 short:py-8' : 'py-12 sm:py-16 short:py-6',
      tone === 'sheet' ? 'bg-surface border-y border-line' : !first && ruled && 'border-t border-line',
      className,
    )}>
      <div className={cn(PAGE_COLUMN, PAGE_GUTTER, innerClassName)}>{children}</div>
    </section>
  )
}

// Steps of the preset's scale (since 2026-10-28; they were 36/52, 32/44 and 26/34px, off it), each
// with that step's own leading.
const DISPLAY_SIZE = {
  hero: 'text-4xl sm:text-5xl',
  section: 'text-3xl sm:text-4xl',
  sub: 'text-2xl sm:text-3xl',
}

/**
 * A section's heading - the `.display` face (the UI face at its heaviest, tight tracking),
 * LEFT-aligned, NO eyebrow (a small upper-case label over a centred heading is the most
 * recognisable pattern of a generated landing page), ending on the prose measure. Three NAMED
 * sizes in place of four arbitrary ones.
 */
export function DisplayHeading({ title, lead, as: Tag = 'h2', size, className }: {
  title: ReactNode
  lead?: ReactNode
  as?: 'h1' | 'h2' | 'h3'
  size?: keyof typeof DISPLAY_SIZE
  className?: string
}) {
  const s = size ?? (Tag === 'h1' ? 'hero' : Tag === 'h2' ? 'section' : 'sub')
  return (
    <div className={cn(PROSE_MEASURE, className)}>
      <Tag className={cn('display text-ink-primary', DISPLAY_SIZE[s])}>{title}</Tag>
      {lead && <p className={cn(TEXT.lead, 'mt-4')}>{lead}</p>}
    </div>
  )
}

/**
 * The heading beside its content: 4 columns of 12 for the heading (sticky while the content is
 * read), 8 for the body. The FAQ, the maker section and the download section were three copies.
 */
export function SplitSection({ heading, children, sticky = true, id, className }: {
  heading: ReactNode
  /** An anchor to link to (#self-hosting). */
  id?: string
  children: ReactNode
  sticky?: boolean
  className?: string
}) {
  return (
    <div id={id} className={cn('grid grid-cols-1 gap-8 lg:grid-cols-12 lg:gap-12', className)}>
      <div className="lg:col-span-4"><div className={cn(sticky && 'lg:sticky lg:top-24')}>{heading}</div></div>
      <div className="lg:col-span-8 min-w-0">{children}</div>
    </div>
  )
}

/**
 * One numbered feature: the numeral as a brand-ink readout (the UI face - Orbitron is the wordmark's
 * alone, since 2026-10-28), a title, a sentence, and its picture.
 */
export function FeatureRow({ index, title, sentence, extra, media }: {
  index: number
  title: ReactNode
  sentence: ReactNode
  extra?: ReactNode
  media: ReactNode
}) {
  return (
    <div className="grid grid-cols-1 gap-6 lg:grid-cols-12 lg:gap-x-12 lg:items-start">
      <div className="lg:col-span-5 min-w-0">
        <p aria-hidden="true" className="data-readout font-semibold text-3xl leading-none text-brand-legible">{String(index).padStart(2, '0')}</p>
        <h3 className="mt-3 text-xl sm:text-2xl font-semibold text-ink-primary">{title}</h3>
        <p className={cn(TEXT.body, 'mt-3')}>{sentence}</p>
        {extra}
      </div>
      <div className="lg:col-span-7 min-w-0">{media}</div>
    </div>
  )
}

/**
 * Terms side by side, ruled between: `fact` puts the rule on the left of each from `sm` (the
 * "why not ChatGPT" panel), `point` puts it on top (the guardrail points). `boxed` frames them in
 * an outline card.
 */
export function RuledColumns({ items, variant = 'fact', columns = 3, boxed, title }: {
  items: { term: ReactNode; detail: ReactNode }[]
  variant?: 'fact' | 'point'
  columns?: 2 | 3 | 4
  boxed?: boolean
  title?: ReactNode
}) {
  const grid = (
    <dl className={cn('grid gap-6', columns === 2 && 'sm:grid-cols-2', columns === 3 && 'sm:grid-cols-3', columns === 4 && 'sm:grid-cols-2 lg:grid-cols-4', title && 'mt-6')}>
      {items.map((it, i) => (
        <div key={i} className={cn(
          variant === 'fact' && columns !== 4 && 'sm:border-l sm:border-line sm:pl-6 sm:first:border-l-0 sm:first:pl-0',
          // Four across only from lg (two at sm), so a rule at sm would open the second row.
          variant === 'fact' && columns === 4 && 'lg:border-l lg:border-line lg:pl-6 lg:first:border-l-0 lg:first:pl-0',
          variant === 'point' && 'border-t border-line pt-4',
        )}>
          <dt className="text-sm font-semibold text-ink-primary">{it.term}</dt>
          <dd className="mt-2 text-sm text-ink-secondary">{it.detail}</dd>
        </div>
      ))}
    </dl>
  )
  if (!boxed) return grid
  return (
    <div className={cardClass({ variant: 'outline', padding: 'lg' })}>
      {title && <h3 className="text-xl sm:text-2xl font-semibold text-ink-primary">{title}</h3>}
      {grid}
    </div>
  )
}

/**
 * A list of what is included - one check, ONE tone (the brand ink; it was brand in pricing and
 * green under the privacy row), 14px, top-aligned with the first line.
 */
export function IncludedList({ items, size = 'sm', className }: { items: ReactNode[]; size?: 'xs' | 'sm' | 'md'; className?: string }) {
  return (
    <ul className={cn('flex flex-col gap-2', className)}>
      {items.map((it, i) => (
        <li key={i} className={cn('flex items-start gap-2 text-ink-secondary', size === 'xs' ? 'text-xs' : size === 'sm' ? 'text-sm' : 'text-base')}>
          <Check size={14} className={cn('shrink-0 text-brand-legible', size === 'xs' ? 'mt-0.5' : 'mt-1')} aria-hidden="true" />
          <span>{it}</span>
        </li>
      ))}
    </ul>
  )
}

/**
 * A plan: its name (and a badge), a tagline on two reserved lines so three prices sit on one line,
 * the price in the display face, its note, a rule, what is included, and the one action. The
 * featured plan is a heavier brand RULE, never a second colour or a shadow.
 */
export function PlanCard({ name, badge, tagline, price, priceSize = 'figure', wasPrice, priceNote, features, action, featured }: {
  name: ReactNode
  badge?: ReactNode
  tagline: ReactNode
  price: ReactNode
  /** `figure` (48px) for a number, `words` (30px) for a price written in words ("Free", "From $1,490 a year"). */
  priceSize?: 'figure' | 'words'
  /** The list price while a sale runs, struck through beside the sale price. */
  wasPrice?: ReactNode
  priceNote?: ReactNode
  features: ReactNode[]
  action: ReactNode
  featured?: boolean
}) {
  return (
    <div className={cn('relative flex flex-col rounded-lg px-4 py-6 sm:p-8', featured ? 'border-2 border-brand-solid' : 'border border-line')}>
      <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1 mb-1">
        <h3 className="text-lg font-semibold text-ink-primary">{name}</h3>
        {badge}
      </div>
      <p className="text-sm text-ink-secondary mb-4">{tagline}</p>
      {/* A sale's price arrives after the page (it is fetched), and fades in over the list price. */}
      <FadeSwap swapKey={wasPrice ? 'sale' : 'list'}>
        <div className="mb-0.5 flex flex-wrap items-baseline gap-x-3">
          {wasPrice && <s className="display text-2xl text-ink-caption">{wasPrice}</s>}
          <span className={cn('display text-ink-primary', priceSize === 'words' ? 'text-3xl' : 'text-5xl')}>{price}</span>
        </div>
        <p className="text-xs text-ink-caption mb-6">{priceNote}</p>
      </FadeSwap>
      <div className="h-px mb-6 bg-line" />
      <IncludedList items={features} className="mb-8 flex-1" />
      {action}
    </div>
  )
}

/**
 * A card that is a link - a document in the docs index: a glyph, a title, a summary. An outline
 * card whose border turns brand under a mouse; NO shadow (the kit card's shadow was why the site
 * never used it).
 */
export function LinkCard({ href, icon: Icon, title, summary, external, render }: {
  href: string
  icon?: ComponentType<{ size?: number | string; className?: string }>
  title: ReactNode
  summary?: ReactNode
  external?: boolean
  /** For a framework link (Next.js `Link`): receives the classes and the body. */
  render?: (className: string, body: ReactNode) => ReactNode
}) {
  const cls = cn(cardClass({ variant: 'outline', padding: 'lg' }), 'block h-full no-underline transition-colors mouse:hover:border-brand-solid focus-ring')
  const body = (
    <>
      {Icon && <Icon size={20} className="text-brand-legible mb-3" aria-hidden="true" />}
      <p className="text-base font-semibold text-ink-primary inline-flex items-center gap-2">
        {title}
        {external && <SquareArrowOutUpRight size={14} className="text-ink-caption" aria-hidden="true" />}
      </p>
      {summary && <p className="mt-2 text-sm text-ink-secondary">{summary}</p>}
    </>
  )
  if (render) return <>{render(cls, body)}</>
  return <a href={href} className={cls} {...(external ? { target: '_blank', rel: 'noopener noreferrer' } : {})}>{body}</a>
}


/**
 * A PLAN THAT IS NOT A FOURTH CARD - Enterprise, the server edition, under three per-installation
 * plans: a filled band (the solid card) with its name, tagline and what is included on the left,
 * and its actions stacked on the right from `lg`. A band and not a card, so a per-deployment
 * conversation is not read as one more price in the same row.
 */
export function PlanBand({ name, tagline, children, actions, className }: {
  name: ReactNode
  tagline: ReactNode
  /** What is included, and anything else the band says. */
  children: ReactNode
  actions: ReactNode
  className?: string
}) {
  return (
    <div className={cn(cardClass({ variant: 'solid', padding: 'lg' }), 'grid grid-cols-1 gap-6 lg:grid-cols-12 lg:items-center', className)}>
      <div className="lg:col-span-8">
        <h3 className="text-lg font-semibold text-ink-primary">{name}</h3>
        <p className="text-sm text-ink-secondary mb-4">{tagline}</p>
        {children}
      </div>
      <div className="lg:col-span-4 flex flex-col gap-3">{actions}</div>
    </div>
  )
}

/**
 * STEPS ON A LINE - what happens on which day, in order. From `md` a row of columns hanging from
 * one rule, each step's marker a dot on it; below `md` the same steps stacked down a rule on the
 * left. No icons and no numbers in tiles: the marker is a word or a figure ("Day 30"), set as a
 * readout. Server-rendered and static.
 */
export function Timeline({ items, className }: {
  items: { marker: ReactNode; title: ReactNode; body?: ReactNode }[]
  className?: string
}) {
  return (
    <ol className={cn(
      'grid md:gap-6',
      items.length === 3 && 'md:grid-cols-3',
      items.length === 4 && 'md:grid-cols-4',
      items.length === 5 && 'md:grid-cols-5',
      className,
    )}>
      {items.map((it, i) => (
        <li key={i} className="relative border-l border-line pl-6 pb-6 last:pb-0 md:border-l-0 md:border-t md:pl-0 md:pb-0 md:pt-6">
          <span aria-hidden="true" className="absolute -left-[4px] top-1 h-2 w-2 rounded-full bg-brand-solid md:left-0 md:-top-[4px]" />
          <p className="data-readout text-xs font-semibold text-brand-legible">{it.marker}</p>
          <p className="mt-2 text-sm font-semibold text-ink-primary">{it.title}</p>
          {it.body && <p className="mt-1 text-sm text-ink-secondary">{it.body}</p>}
        </li>
      ))}
    </ol>
  )
}

/**
 * One choice on a download page: what it is and who it is for, what it needs, and its action - in
 * three columns from `md`, stacked on a phone, ruled above. No platform icon: a generic glyph
 * standing in for a vendor's product is what the site's rules forbid, and the vendors' own marks
 * are not licensed for this use.
 */
export function DownloadRow({ id, name, audience, needs, action, note, className }: {
  id?: string
  name: ReactNode
  audience: ReactNode
  /** What it runs on - a sentence or a short list. */
  needs: ReactNode
  /** The button or command. */
  action: ReactNode
  /** A line under the action ("Starts a 30-day trial on first start"). */
  note?: ReactNode
  className?: string
}) {
  return (
    <div id={id} className={cn('grid grid-cols-1 gap-3 border-t border-line py-6 scroll-mt-24 md:grid-cols-12 md:gap-8', className)}>
      <div className="min-w-0 md:col-span-4">
        <h3 className="text-lg font-semibold text-ink-primary">{name}</h3>
        <p className="mt-1 text-sm text-ink-secondary">{audience}</p>
      </div>
      <div className="min-w-0 text-sm text-ink-secondary md:col-span-5">{needs}</div>
      <div className="flex min-w-0 flex-col items-start gap-2 md:col-span-3 md:items-stretch">
        {action}
        {note && <p className="text-xs text-ink-caption">{note}</p>}
      </div>
    </div>
  )
}

/**
 * A COMPARISON in rows: a label per row and one cell per column - `true` a check, `false` a dash,
 * anything else written as it is - under group headings. `.table-stack`, so below `sm` each row
 * stacks into lines labelled with `columnLabels`. `yes` and `no` are what a screen reader says for
 * the two marks. The kit's check, in the brand ink, as everywhere a list says what is included.
 */
export function CompareTable({ columns, columnLabels, groups, yes, no, caption, className }: {
  columns: ReactNode[]
  /** Plain-text column names: the phone layout's labels. */
  columnLabels: string[]
  groups: { heading: ReactNode; rows: { label: ReactNode; values: (boolean | ReactNode)[] }[] }[]
  yes: string
  no: string
  /** For a screen reader: what the table compares. */
  caption?: ReactNode
  className?: string
}) {
  return (
    <div className={cn('overflow-x-auto', className)}>
      <table className="table-stack w-full text-sm">
        {caption && <caption className="sr-only">{caption}</caption>}
        <thead>
          <tr className="border-b border-line">
            <td className="py-3" />
            {columns.map((c, i) => (
              <th key={i} scope="col" className="w-32 px-3 py-3 text-left text-sm font-semibold text-ink-primary sm:w-40">{c}</th>
            ))}
          </tr>
        </thead>
        {groups.map((g, gi) => (
          <tbody key={gi}>
            <tr>
              {/* A cell, not a `th`: `.table-stack` restyles cells, and a group heading has to stack
                  with the rows under it on a phone. */}
              <td colSpan={columns.length + 1} className="pt-8 pb-2 text-left text-sm font-semibold text-ink-primary">{g.heading}</td>
            </tr>
            {g.rows.map((r, ri) => (
              <tr key={ri} className="border-b border-line/40">
                <td className="py-3 pr-4 text-ink-secondary">{r.label}</td>
                {r.values.map((v, vi) => (
                  <td key={vi} data-label={columnLabels[vi]} className="px-3 py-3 text-ink-secondary">
                    {v === true
                      ? <><Check size={16} className="inline text-brand-legible" aria-hidden="true" /><span className="sr-only">{yes}</span></>
                      : v === false
                        ? <><span aria-hidden="true" className="text-ink-disabled">-</span><span className="sr-only">{no}</span></>
                        : v}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        ))}
      </table>
    </div>
  )
}

/** A link in a footer column: caption ink, primary under a mouse only, one per line. */
export const FOOTER_LINK = 'block text-sm mb-2 text-ink-caption mouse:hover:text-ink-primary transition-colors focus-ring rounded-sm'

/** A footer column: its sentence-case heading over its links (`FOOTER_LINK` on each). */
export function LinkColumn({ heading, children }: { heading: ReactNode; children: ReactNode }) {
  return (
    <div>
      <h4 className="text-sm font-semibold text-ink-primary mb-3">{heading}</h4>
      {children}
    </div>
  )
}
