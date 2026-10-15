/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/Toast.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import type { ReactNode } from 'react'
import { AlertCircle, AlertTriangle, CheckCircle2, Info, X } from 'lucide-react'
import { cn } from './cn'
import { iconButton } from './iconButton'

/**
 * The kit's tone words (`danger`, not `error` - every other part says danger since 2026-10-14). An
 * app whose store says `error` maps it where it renders the toast.
 */
export type ToastTone = 'success' | 'danger' | 'warning' | 'info'

const ICON: Record<ToastTone, ReactNode> = {
  success: <CheckCircle2 size={16} className="text-success fill-success/20 shrink-0" aria-hidden="true" />,
  danger: <AlertCircle size={16} className="text-danger fill-danger/20 shrink-0" aria-hidden="true" />,
  warning: <AlertTriangle size={16} className="text-warning shrink-0" aria-hidden="true" />,
  info: <Info size={16} className="text-brand-legible shrink-0" aria-hidden="true" />,
}

/**
 * ONE TOAST, presentational - the app's store, timers and links stay the app's (`ToastContainer`).
 * The message, and UNDER it whatever the toast offers (a link, a quiet "Don't show again"), so a
 * wrapped message and its actions share one left edge (maintainer, 2026-10-02).
 */
export function Toast({ tone, children, actions, onDismiss, dismissLabel, leaving, className }: {
  tone: ToastTone
  children: ReactNode
  actions?: ReactNode
  onDismiss: () => void
  dismissLabel: string
  /** Dismissed and on its way out - the container keeps it for the exit (`usePresence`'s time). */
  leaving?: boolean
  className?: string
}) {
  return (
    <div
      role={tone === 'danger' ? 'alert' : 'status'}
      className={cn('rounded-lg border border-line/45 surface-float shadow-card px-4 sm:px-5 py-4 flex items-start gap-3 max-w-md', leaving ? 'animate-slide-out-right' : 'animate-slide-in-right', className)}
    >
      <span className="mt-px">{ICON[tone]}</span>
      <div className="flex-1 min-w-0 text-sm text-ink-primary leading-relaxed">
        <div>{children}</div>
        {actions && <div className="mt-1.5 flex flex-wrap items-baseline gap-x-4 gap-y-1">{actions}</div>}
      </div>
      <button type="button" onClick={onDismiss} aria-label={dismissLabel} className={cn(iconButton('sm'), '-mr-1')}>
        <X size={16} aria-hidden="true" />
      </button>
    </div>
  )
}

/** Where toasts stack: top right from `sm`, across the top on a phone, above dialogs (`z-toast`). */
export const TOAST_STACK = 'fixed top-16 short:top-12 left-3 right-3 sm:left-auto sm:right-4 sm:w-[min(24rem,calc(100vw-2rem))] z-toast flex flex-col gap-2'
