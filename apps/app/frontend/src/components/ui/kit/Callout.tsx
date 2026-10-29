/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/Callout.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import type { ComponentType, ReactNode } from 'react'
import { AlertTriangle, CheckCircle2, Info, X, XCircle } from 'lucide-react'
import { cn } from './cn'
import { iconButton } from './iconButton'
import { TEXT } from './typeScale'

export type CalloutTone = 'neutral' | 'info' | 'warning' | 'danger' | 'success'

const TONE: Record<CalloutTone, { box: string; ink: string; icon: ComponentType<{ size?: number | string; className?: string }> }> = {
  neutral: { box: 'bg-surface-alt border-line/60', ink: 'text-ink-caption', icon: Info },
  info: { box: 'surface-card border-brand-solid/40', ink: 'text-brand-legible', icon: Info },
  warning: { box: 'bg-warning/[0.07] border-warning/35', ink: 'text-warning', icon: AlertTriangle },
  danger: { box: 'bg-danger/5 border-danger/30', ink: 'text-danger', icon: XCircle },
  success: { box: 'bg-success/[0.07] border-success/35', ink: 'text-success', icon: CheckCircle2 },
}

/**
 * A BOX THAT SAYS SOMETHING ABOUT THE WHOLE SECTION - read-only mode, a token shown once, a
 * restart needed, a failed sign-in, a documentation note. Eight boxes had eight recipes in
 * Settings alone; the docs had a third shape with a left rule. One now: `rounded-lg border px-4
 * py-3`, a 7% tint of the tone with its border at a third - except `info`, the brand's, which is the
 * neutral surface with a brand border (the brand is never a tinted background, since 2026-10-28) -
 * a 16px glyph in the tone, the title the type scale's label (14/500) and the body 14/20 secondary. `icon={null}` drops the glyph.
 *
 * The docs' `.note`/`.warn` (proxyma-primitives.css, `.prose-doc`) are this same box for HTML that
 * cannot render a component.
 */
export function Callout({ tone = 'neutral', title, children, actions, icon, className, role }: {
  tone?: CalloutTone
  title?: ReactNode
  children?: ReactNode
  /** Buttons under the text, or at the right of a one-line callout. */
  actions?: ReactNode
  icon?: ComponentType<{ size?: number | string; className?: string }> | null
  className?: string
  role?: 'status' | 'alert' | 'note'
}) {
  const t = TONE[tone]
  const Icon = icon === null ? null : (icon ?? t.icon)
  return (
    // Fades in: a callout usually appears because something just happened (a restart is needed).
    <div role={role} className={cn('flex items-start gap-3 rounded-lg border px-4 py-3 animate-fade-in', t.box, className)}>
      {Icon && <Icon size={16} className={cn('shrink-0 mt-0.5', t.ink)} aria-hidden="true" />}
      <div className="flex-1 min-w-0">
        {title && <p className={TEXT.label}>{title}</p>}
        {children && <div className={cn('text-sm text-ink-secondary', title && 'mt-1')}>{children}</div>}
        {actions && <div className="mt-3 flex flex-wrap items-center gap-3">{actions}</div>}
      </div>
    </div>
  )
}

/**
 * THE STRIP ABOVE A PAGE - the guest, sandbox, maintenance, license and restart banners. A
 * full-width band in the tone's tint with a hairline under it, the text left, the action after it
 * on the left below `sm` (nothing to tap sits in the middle of a phone), and a dismiss at the
 * far right when it can be dismissed.
 */
type BannerDismiss =
  | { onDismiss?: undefined; dismissLabel?: undefined }
  /** A dismiss names itself: an X with no words is a button nobody can find by name. */
  | { onDismiss: () => void; dismissLabel: string }

export function Banner({ tone = 'info', title, children, action, onDismiss, dismissLabel, icon, className }: {
  tone?: CalloutTone
  /** The banner's lead, bold in primary ink before the sentence - "Public sandbox -". */
  title?: ReactNode
  children: ReactNode
  action?: ReactNode
  icon?: ComponentType<{ size?: number | string; className?: string }> | null
  className?: string
} & BannerDismiss) {
  const t = TONE[tone]
  const Icon = icon === null ? null : (icon ?? t.icon)
  return (
    <div role="status" className={cn('relative w-full border-b px-3 sm:px-6 py-2 text-xs animate-fade-in', t.box, 'border-x-0 border-t-0', className)}>
      <div className={cn('flex flex-col sm:flex-row sm:items-center sm:justify-center gap-x-3 gap-y-1', onDismiss && 'pr-8')}>
        <span className="flex items-start gap-2 text-ink-secondary">
          {Icon && <Icon size={14} className={cn('shrink-0 mt-0.5', t.ink)} aria-hidden="true" />}
          <span>
            {title && <span className="font-semibold text-ink-primary">{title} </span>}
            {children}
          </span>
        </span>
        {action && <span className="shrink-0">{action}</span>}
      </div>
      {onDismiss && (
        <button
          type="button"
          onClick={onDismiss}
          aria-label={dismissLabel}
          className={cn(iconButton('sm'), 'absolute right-2 top-1/2 -translate-y-1/2')}
        >
          <X size={14} aria-hidden="true" />
        </button>
      )}
    </div>
  )
}
