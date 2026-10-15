import { SquareArrowOutUpRight } from 'lucide-react'
import type { ReactNode } from 'react'
import { cn } from './cn'
import { safeExternalHref } from './safeUrl'

// The one way to link OUT of Proxyma.
//
// Every link that leaves the application carries the same trailing glyph, so "this navigates away"
// is something the reader recognises rather than reads. Before this component there were fourteen
// hand-rolled `<a target="_blank">` sites across nine files, each picking its own colour, its own
// underline offset, and - three times - its own `rel`, including one that set `noreferrer` without
// `noopener`. That last one is not cosmetic: without `noopener` the opened page gets a handle on
// this one through `window.opener`.
//
// USE IT FOR EXTERNAL DESTINATIONS ONLY - proxyma.ai, a customer's Jira, a cited web page. A link
// to something this deployment itself serves (a feedback attachment, a file download off our own
// API) is not "leaving", and marking it as though it were tells the reader something untrue. Those
// stay as plain anchors.
//
// For in-app navigation use react-router's <Link>; an anchor to a route reloads the whole SPA.
//
// ONLY A WHOLE http, https OR mailto ADDRESS BECOMES A LINK (2026-10-14, security review swx-11).
// Anything else - `javascript:`, `data:`, a relative path, a blank - renders its label as plain
// text. React 18 still renders a `javascript:` href, and a source's stored URL, a citation or a
// key-account link all reach this from data; checking here makes every caller safe at once rather
// than trusting that each one validated upstream.

interface Props {
  href: string
  children: ReactNode
  // Replaces the default link colour/underline. The icon and the target/rel handling are not
  // overridable - they are the reason this component exists.
  className?: string
  // The glyph is sized against the text it follows: 12 suits the xs/2xs captions most of these
  // links live in, and callers in larger copy step it up rather than accepting a glyph that reads
  // as a different size to its own sentence.
  iconSize?: number
  onClick?: () => void
  title?: string
  // Set when the link sits inside another clickable thing (a card, a row) and the outer click
  // handler must not also fire.
  stopPropagation?: boolean
  // Classes for the LABEL span rather than the anchor. `truncate` is the reason this exists: it
  // has to land on the text, not on the flex container, or the ellipsis eats the icon instead of
  // the overlong name it was meant to shorten.
  labelClassName?: string
}

export function ExternalLink({
  href, children, className, iconSize = 12, onClick, title, stopPropagation, labelClassName,
}: Props) {
  const safeHref = safeExternalHref(href)
  // The label alone, with no glyph and nothing to follow. The caller's classes stay, so the layout
  // around it (a truncating row, a sentence) does not move.
  if (!safeHref) return <span title={title} className={className}><span className={labelClassName}>{children}</span></span>
  return (
    <a
      href={safeHref}
      target="_blank"
      rel="noopener noreferrer"
      title={title}
      onClick={e => {
        if (stopPropagation) e.stopPropagation()
        onClick?.()
      }}
      // `items-baseline` rather than `items-center`: these sit inside running text as often as
      // they sit alone, and centring the glyph against a line box floats it above the baseline of
      // the words beside it.
      className={cn(
        'inline-flex items-baseline gap-1 text-brand-legible mouse:hover:underline underline-offset-[3px] focus-ring rounded-sm',
        className,
      )}
    >
      <span className={labelClassName}>{children}</span>
      {/* Nudged down because a baseline-aligned icon sits on the baseline rather than across the
          x-height, which reads as floating. aria-hidden: the icon repeats what the link already
          says, and `target="_blank"` is what a screen reader announces. */}
      <SquareArrowOutUpRight
        size={iconSize}
        aria-hidden
        className="shrink-0 self-center translate-y-[0.05em]"
      />
    </a>
  )
}
