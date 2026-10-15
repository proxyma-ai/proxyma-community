/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/Text.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import type { ComponentType, ElementType, ReactNode } from 'react'
import { cn } from './cn'
import { TEXT } from './typeScale'

/**
 * The text ROLES of the type scale in `typeScale.ts`, as components - so a page, a dialog and a card
 * name things at the same size without anybody typing one. Each takes `as` for the element and
 * `className` for placement only (margins, truncation); a size passed through `className` is the
 * thing this file exists to stop.
 */

type RoleProps = { children: ReactNode; as?: ElementType; className?: string; id?: string }

/** A page's, a Settings tab's or a dialog's own name - 18/600. */
export function Title({ children, as: As = 'h2', className, id }: RoleProps) {
  return <As id={id} className={cn(TEXT.title, className)}>{children}</As>
}

/**
 * A title with the one action that belongs to the whole page on its line - "Add connection",
 * "Connect" - top-aligned with it, so the action reads as the page's rather than the first row's.
 * `meta` is the caption line under the title (a count, a last-synced time).
 */
export function PageTitle({ children, actions, meta, as = 'h1', className, actionsRef }: {
  children: ReactNode
  actions?: ReactNode
  meta?: ReactNode
  as?: ElementType
  className?: string
  /** A slot the caller fills by portal (Settings' `TitleAction`). Rendered even when empty. */
  actionsRef?: (el: HTMLDivElement | null) => void
}) {
  return (
    <div className={cn('flex items-start justify-between gap-3', className)}>
      <div className="min-w-0">
        <Title as={as}>{children}</Title>
        {meta && <div className={cn(TEXT.caption, 'mt-1')}>{meta}</div>}
      </div>
      {(actions || actionsRef) && (
        <div ref={actionsRef} className="shrink-0 flex items-center gap-2 empty:hidden">{actions}</div>
      )}
    </div>
  )
}

/**
 * A sub-section's heading - 15/600 - with a caption under it and an action beside it (a switch
 * that turns the section on, an Add button). `tone="danger"` is the heading of a danger zone.
 * `flush` drops the bottom margin, for a heading inside a `space-y-*` stack.
 */
export function SectionHeading({ children, caption, action, tone = 'default', flush, as: As = 'h3', className }: {
  children: ReactNode
  caption?: ReactNode
  action?: ReactNode
  tone?: 'default' | 'danger'
  flush?: boolean
  as?: ElementType
  className?: string
}) {
  return (
    <div className={cn('flex items-start justify-between gap-3', !flush && 'mb-4', className)}>
      <div className="flex-1 min-w-0">
        <As className={cn(TEXT.section, tone === 'danger' && 'text-danger')}>{children}</As>
        {caption && <div className={cn(TEXT.caption, 'mt-0.5')}>{caption}</div>}
      </div>
      {action && <div className="shrink-0 min-h-[22px] flex items-center gap-2">{action}</div>}
    </div>
  )
}

/**
 * A field's name, over its control - 13/500. A real `<label>`: the Settings one was a `<p>`, so
 * not one settings input was associated with its own name and a screen reader announced each as
 * "edit text". Pass `htmlFor` (or wrap the control in `Field`, which does).
 */
export function FieldLabel({ children, htmlFor, className, as }: {
  children: ReactNode
  htmlFor?: string
  className?: string
  /** `'p'` (or `'span'`) where the name labels a group rather than one control. */
  as?: 'label' | 'p' | 'span'
}) {
  const As = as ?? (htmlFor ? 'label' : 'p')
  return <As htmlFor={As === 'label' ? htmlFor : undefined} className={cn(TEXT.label, 'block mb-2', className)}>{children}</As>
}

export type CaptionTone = 'default' | 'warning' | 'danger' | 'success' | 'brand'

const CAPTION_TONE: Record<CaptionTone, string> = {
  default: 'text-ink-caption',
  warning: 'text-warning',
  danger: 'text-danger',
  success: 'text-success',
  brand: 'text-brand-legible',
}

/**
 * Small explanatory text - 13px, `leading-relaxed`. `tone` colours a warning or a verdict; never a
 * filled box (that is `Callout`). `icon` is a 14px glyph top-aligned with the first line.
 */
export function Caption({ children, tone = 'default', icon: Icon, as: As = 'p', className, role, id }: {
  children: ReactNode
  tone?: CaptionTone
  icon?: ComponentType<{ size?: number | string; className?: string }>
  as?: ElementType
  className?: string
  role?: string
  /** For a control's `aria-describedby` - the error line under a consent box. */
  id?: string
}) {
  return (
    <As id={id} role={role} className={cn('text-xs leading-relaxed', CAPTION_TONE[tone], Icon && 'flex items-start gap-1.5', className)}>
      {Icon && <Icon size={14} className="shrink-0 mt-0.5" aria-hidden="true" />}
      {Icon ? <span className="min-w-0">{children}</span> : children}
    </As>
  )
}

/**
 * The label inside a card naming what it shows ("Requests per day"), or over a block ("Input",
 * "Output"). Sentence case in caption ink since 2026-09-30 - an upper-case eyebrow on every card
 * made each one open with the same shout. `.label-upper` stays for a genuine badge-like eyebrow.
 */
export function Eyebrow({ children, action, className, as: As = 'p' }: {
  children: ReactNode
  action?: ReactNode
  className?: string
  as?: ElementType
}) {
  if (!action) return <As className={cn(TEXT.eyebrow, className)}>{children}</As>
  return (
    <div className={cn('flex items-center justify-between gap-3', className)}>
      <As className={TEXT.eyebrow}>{children}</As>
      {action}
    </div>
  )
}

/**
 * A code token inside prose - a command, a key, a file name, a placeholder. `font-mono` is for
 * CODE only; a figure, a date or an ID is a `Readout`. `rounded-md` like every other small box (it
 * was 4px, the one radius nothing else used). 0.9em and never under 12px: Consolas's x-height is the UI
 * face's own, so 0.85em only made a token read smaller than its sentence (11.9px in a 14px cell).
 */
export function InlineCode({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <code className={cn('font-mono text-[length:max(12px,0.9em)] text-ink-primary bg-surface border border-line/60 rounded-md px-1.5 py-0.5 break-words', className)}>
      {children}
    </code>
  )
}

export type ReadoutTone = 'default' | 'caption' | 'disabled' | 'warning' | 'danger' | 'brand' | 'inherit'

const READOUT_TONE: Record<ReadoutTone, string> = {
  default: 'text-ink-secondary',
  caption: 'text-ink-caption',
  disabled: 'text-ink-disabled',
  warning: 'text-warning',
  danger: 'text-danger',
  brand: 'text-brand-legible',
  inherit: '',
}

/**
 * A FIGURE - a count, a duration, a percent, a size, a date, an ID. `.data-readout` draws its
 * digits in the figures face and every other character in the UI face, so "12.3k tokens" is not a
 * phrase in a code font. It replaces ten `tabular-nums` spans, which kept the UI face's digits and
 * so did not line up with the readouts beside them.
 */
export function Readout({ children, tone = 'inherit', className, title }: {
  children: ReactNode
  tone?: ReadoutTone
  className?: string
  title?: string
}) {
  return <span title={title} className={cn('data-readout', READOUT_TONE[tone], className)}>{children}</span>
}

/**
 * THE GREETING OF AN EMPTY PAGE - a display-size line and a narrower one under it. The new chat
 * draws its Enterprise Server greeting ("Hi, {name}", or the question for a guest) with this,
 * through `ChatStartHero`'s `greeting`; on Community it opens on the logo instead.
 * Display size (29px, 37px from `sm`), bold, balanced, one line from `sm` - a long display name
 * wrapped at the old size. `leading-[1.35]` because a tighter leading clipped the descenders of
 * fonts deeper than the one it was tuned on. The subtitle is narrower and a step smaller, so it
 * reads as a light touch under the heading rather than a second headline.
 *
 * `as` for a picture of the page (the site's scenes are not the page's heading - `p` there), and
 * `centered` for the greeting the new-chat screen centres from `sm`.
 */
export function PageHero({ title, subtitle, as: As = 'h1', centered, className }: {
  title: ReactNode
  subtitle?: ReactNode
  as?: 'h1' | 'h2' | 'p'
  centered?: boolean
  className?: string
}) {
  return (
    <div className={cn(centered && 'text-center', className)}>
      <As className="text-3xl sm:text-4xl leading-[1.35] tracking-tight mb-4 pb-1 text-balance sm:whitespace-nowrap font-bold text-ink-primary">
        {title}
      </As>
      {subtitle && (
        <div className="max-w-2xl mx-auto text-sm text-ink-secondary leading-relaxed">{subtitle}</div>
      )}
    </div>
  )
}

/**
 * RUNNING TEXT - a paragraph at one of the scale's three reading sizes: `body` (14, the default),
 * `lead` (16, a page's or a section's opening paragraph) and `small` (12, a note under a block),
 * in primary, secondary (the default) or caption ink. The landing site set these sizes by hand on
 * sixty elements in six combinations; a page file should not name a text size at all.
 */
export function Paragraph({ children, size = 'body', tone = 'secondary', as: As = 'p', className, id }: {
  children: ReactNode
  size?: 'body' | 'lead' | 'small'
  tone?: 'primary' | 'secondary' | 'caption'
  as?: ElementType
  className?: string
  id?: string
}) {
  return (
    <As
      id={id}
      className={cn(
        size === 'lead' ? TEXT.lead : size === 'small' ? 'text-xs leading-relaxed' : TEXT.body,
        tone === 'primary' && 'text-ink-primary',
        tone === 'secondary' && 'text-ink-secondary',
        tone === 'caption' && 'text-ink-caption',
        className,
      )}
    >
      {children}
    </As>
  )
}
