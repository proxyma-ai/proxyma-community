/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/List.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import type { ComponentType, ReactNode } from 'react'
import { ChevronRight } from 'lucide-react'
import { Button } from './Button'
import { ExternalLink } from './ExternalLink'
import { cn } from './cn'
import { TEXT } from './typeScale'
import { ToggleSwitch } from './ToggleSwitch'

/**
 * THE LIST: items separated by a hairline (`divide-line/30`), nothing round the outside. Every tab,
 * dialog and page that lists things uses it - the Settings `SETTINGS_LIST` constant was retyped in
 * four places and drawn at three opacities.
 */
export function List({ children, className, as: As = 'div', ariaLabel }: {
  children: ReactNode
  className?: string
  as?: 'div' | 'ul'
  ariaLabel?: string
}) {
  return <As aria-label={ariaLabel} className={cn('divide-y divide-line/30', className)}>{children}</As>
}

/**
 * THE LIST ROW: a title and a caption on the left, the row's action on the right.
 *
 * TOP-ALIGNED (maintainer, 2026-10-01): the action and the leading mark sit on the TITLE's line,
 * not halfway down a two-line caption; the text column is `flex-1 min-w-0`, so a caption runs the
 * full width left of the action however many lines it takes. `gap-3 py-3`.
 *
 * A row that OPENS something is a `<button>` (`onClick`) - the history list's rows were a `div`
 * with a click handler, which a keyboard could not reach - with colour-only hover and, with
 * `chevron`, the affordance of a row that opens a dialog. `selected` is the row you are on: the
 * NEUTRAL selected fill with a 2px brand bar at its left edge (`SELECTED_ROW`, since 2026-10-28 -
 * it was a teal-tinted fill, which in Dark failed contrast under a caption).
 * `meta` sits beside the title (badges, chips); `trailing` beside the action (a readout).
 *
 * A row that opens AND has its own action (a skill with Delete, a conversation with pin and
 * delete) cannot be one button - a button inside a button is invalid and the inner one is
 * unreachable. Its text becomes the button and the action sits beside it, while the whole row
 * still takes the hover fill, so it reads as one row.
 */
/**
 * A SELECTED ROW: the neutral selected fill (surface-alt) and a 2px bar of the brand at its left
 * edge - the brand marks the choice, it does not fill it. For a row of any list that is not a
 * `ListRow` (a table row, a picker's line); the element must not be `static`-only - this sets
 * `relative` for the bar.
 */
export const SELECTED_ROW =
  'relative bg-surface-alt before:absolute before:left-0 before:top-2 before:bottom-2 before:w-0.5 before:rounded-full before:bg-brand-solid'

export function ListRow({ title, caption, meta, leading, action, trailing, onClick, selected, chevron, disabled, titleClamp, inset, className, ariaLabel }: {
  title?: ReactNode
  caption?: ReactNode
  meta?: ReactNode
  leading?: ReactNode
  action?: ReactNode
  trailing?: ReactNode
  onClick?: () => void
  selected?: boolean
  chevron?: boolean
  disabled?: boolean
  /** Cut the title at one or two lines (a conversation's first message) instead of wrapping it all. */
  titleClamp?: 1 | 2
  /**
   * The row's hover and selection drawn INSIDE the list's edges rather than bleeding 8px past them
   * - for a list that is itself the full width of a dialog (the conversation history, `px-5`).
   */
  inset?: boolean
  className?: string
  ariaLabel?: string
}) {
  const text = (
    <div className="flex-1 min-w-0 flex items-start gap-3">
      {leading && <div className="shrink-0 min-h-5 flex items-center">{leading}</div>}
      <div className="flex-1 min-w-0">
        {(title || meta) && (
          <div className="flex items-center gap-2 min-w-0 flex-wrap">
            {title && <span className={cn(TEXT.label, 'min-w-0 break-words', titleClamp === 1 && 'truncate', titleClamp === 2 && 'line-clamp-2')}>{title}</span>}
            {meta}
          </div>
        )}
        {caption && <div className={cn(TEXT.caption, title && 'mt-0.5')}>{caption}</div>}
      </div>
    </div>
  )
  const right = (trailing || action || chevron) && (
    <div className="shrink-0 min-h-5 flex items-center gap-2">
      {trailing}
      {action}
      {chevron && <ChevronRight size={16} className="text-ink-caption" aria-hidden="true" />}
    </div>
  )
  const base = cn('flex items-start justify-between gap-3 py-3', className)

  if (onClick && action) {
    return (
      <div className={cn(
        base,
        'group rounded-md transition-colors', inset ? 'px-2' : '-mx-2 px-2',
        selected ? SELECTED_ROW : 'mouse:hover:bg-ink-caption/5 focus-within:bg-ink-caption/5',
        disabled && 'opacity-50',
      )}>
        <button
          type="button"
          onClick={onClick}
          disabled={disabled}
          aria-label={ariaLabel}
          aria-current={selected || undefined}
          className="flex-1 min-w-0 flex text-left focus-ring rounded-sm"
        >
          {text}
        </button>
        {right}
      </div>
    )
  }
  if (onClick) {
    return (
      <button
        type="button"
        onClick={onClick}
        disabled={disabled}
        aria-label={ariaLabel}
        aria-current={selected || undefined}
        className={cn(
          base,
          // Not inset, the row bleeds 8px past the list on BOTH sides: `w-full` with `-mx-2` only moved
          // it left, so its chevron sat 16px short of the right edge every other row ends on.
          'text-left transition-colors focus-ring rounded-md disabled:opacity-50', inset ? 'w-full px-2' : 'w-[calc(100%+1rem)] -mx-2 px-2',
          selected ? SELECTED_ROW : 'mouse:hover:bg-ink-caption/5',
        )}
      >
        {text}
        {right}
      </button>
    )
  }
  return <div className={cn(base, selected && SELECTED_ROW, disabled && 'opacity-50')}>{text}{right}</div>
}

/**
 * A row whose action is a switch - on the title's line, the `ListRow` geometry, its caption directly
 * under the title at 4px (the field rule of 2026-10-29: a setting's description sits under its
 * name, a step apart, never under the control). `size="sm"` is the
 * inline one over a list ("Show shared sources from other users"): caption-sized words, the small
 * switch, no row padding.
 */
export function SwitchRow({ title, caption, checked, onChange, disabled, footnote, size = 'md', className, id }: {
  title: ReactNode
  caption?: ReactNode
  checked: boolean
  onChange: (v: boolean) => void
  disabled?: boolean
  /** Under the caption: a lock notice, a link, a status. */
  footnote?: ReactNode
  size?: 'md' | 'sm'
  className?: string
  id?: string
}) {
  if (size === 'sm') {
    return (
      // The kit's ONE switch size and 14px words (the maintainer, 2026-10-30: the small switch was
      // "too small, not following the UI kit"); only the row's frame is inline.
      <label className={cn('inline-flex items-center gap-2 text-sm text-ink-secondary cursor-pointer select-none animate-fade-in', disabled && 'opacity-50 cursor-not-allowed', className)}>
        <ToggleSwitch id={id} checked={checked} onChange={onChange} disabled={disabled} />
        <span>{title}</span>
      </label>
    )
  }
  return (
    // Fades in where it mounts - usually in place of a SwitchRowSkeleton.
    <div className={cn('py-3 animate-fade-in', className)}>
      <div className="flex items-start justify-between gap-3">
        <div className="flex-1 min-w-0">
          <label htmlFor={id} className={cn(TEXT.label, 'block')}>{title}</label>
          {caption && <div className={cn(TEXT.caption, 'mt-1 max-w-[60ch]')}>{caption}</div>}
        </div>
        <ToggleSwitch id={id} checked={checked} onChange={onChange} disabled={disabled} ariaLabel={typeof title === 'string' ? title : undefined} />
      </div>
      {footnote && <div className="mt-2">{footnote}</div>}
    </div>
  )
}

/**
 * A row's action cluster, `gap-2`, with the destructive one LAST and set apart (`ml-2`) - a text
 * `danger-quiet` button on a row that carries a name, by the Settings rule. Icon actions belong
 * only to dense sub-lists (a price list, a header list, a step list).
 *
 * `icons` is that sub-list's cluster (0.28.0 feedback: the price list's actions floated with odd
 * gaps): 24px `iconButton('sm')` targets one even `gap-1` apart, the destructive one included -
 * a glyph is set apart by its danger hover, not by a wider gap - and pulled up by the 2px the 24px
 * target is taller than the row's 20px title line, so the icons sit ON that line rather than
 * pushing the row taller under it.
 */
export function RowActions({ children, destructive, icons, className }: { children?: ReactNode; destructive?: ReactNode; icons?: boolean; className?: string }) {
  return (
    <div className={cn('flex items-center', icons ? 'gap-1 -my-0.5 touch:-my-3' : 'gap-2', className)}>
      {children}
      {destructive && <span className={cn('inline-flex', !icons && 'ml-2')}>{destructive}</span>}
    </div>
  )
}

/**
 * Actions that show while the pointer or keyboard focus is on their row - and ALWAYS on a touch
 * screen (`touch:`), where there is no hover. The history list's pin and delete had no fallback
 * and could not be reached on a phone at all. Needs a `group` on the row.
 */
export function HoverActions({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <div className={cn(
      'flex items-center gap-1 transition-opacity',
      'mouse:opacity-0 mouse:group-hover:opacity-100 mouse:group-focus-within:opacity-100',
      className,
    )}>
      {children}
    </div>
  )
}

/**
 * Cancel and a red Confirm in place of a destructive action - for a row that confirms where it
 * stands rather than in a dialog (a conversation in the history, a license being released).
 */
export function InlineConfirm({ cancelLabel, confirmLabel, pendingLabel, pending, onCancel, onConfirm, className }: {
  cancelLabel: string
  confirmLabel: string
  pendingLabel?: string
  pending?: boolean
  onCancel: () => void
  onConfirm: () => void
  className?: string
}) {
  return (
    <div className={cn('flex items-center gap-3 animate-fade-in', className)}>
      <Button variant="quiet" onClick={onCancel} disabled={pending}>{cancelLabel}</Button>
      <Button variant="danger-quiet" onClick={onConfirm} disabled={pending} data-autofocus>{pending && pendingLabel ? pendingLabel : confirmLabel}</Button>
    </div>
  )
}

/** An outbound document: a glyph, the linked title, a one-line description - About's privacy rows. */
export function LinkRow({ icon: Icon, title, description, href }: {
  icon?: ComponentType<{ size?: number | string; className?: string }>
  title: ReactNode
  description?: ReactNode
  href: string
}) {
  return (
    <div className="flex items-start gap-3 py-3">
      {Icon && <Icon size={16} className="shrink-0 mt-0.5 text-ink-caption" aria-hidden="true" />}
      <div className="min-w-0">
        {/* The kit's one link out (ExternalLink): its glyph, its rel, its URL guard. */}
        <ExternalLink href={href} className={cn(TEXT.label, 'mouse:hover:text-brand-legible')}>{title}</ExternalLink>
        {description && <div className={cn(TEXT.caption, 'mt-0.5')}>{description}</div>}
      </div>
    </div>
  )
}

/**
 * The bar shown while rows are selected: how many, and what can be done to them. A brand border
 * on the neutral selected fill, `px-4 py-2` - Memory's and the telemetry console's feedback list.
 */
export function BulkActionBar({ countText, children, ariaLabel, className }: {
  countText: ReactNode
  children: ReactNode
  /** The region's name ("Bulk actions"); without it the count names it. */
  ariaLabel?: string
  className?: string
}) {
  return (
    <div role="region" aria-label={ariaLabel ?? (typeof countText === 'string' ? countText : undefined)} className={cn('flex flex-wrap items-center gap-3 rounded-lg border border-brand-solid/40 bg-surface-alt px-4 py-2 animate-fade-in', className)}>
      <span className="text-xs font-medium text-ink-primary data-readout">{countText}</span>
      <div className="ml-auto flex items-center gap-3">{children}</div>
    </div>
  )
}
