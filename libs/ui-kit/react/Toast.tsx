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
      className={cn('pointer-events-auto w-full sm:w-96 rounded-lg border surface-float shadow-float px-4 py-4 flex items-start gap-3', leaving ? 'animate-slide-out-right' : 'animate-slide-in-right', className)}
    >
      <span className="mt-px">{ICON[tone]}</span>
      <div className="flex-1 min-w-0 text-sm text-ink-primary">
        <div>{children}</div>
        {actions && <div className="mt-2 flex flex-wrap items-baseline gap-x-4 gap-y-1">{actions}</div>}
      </div>
      <button type="button" onClick={onDismiss} aria-label={dismissLabel} className={cn(iconButton('sm'), '-mr-1')}>
        <X size={16} aria-hidden="true" />
      </button>
    </div>
  )
}

/**
 * WHERE TOASTS STACK, ON THE PAGE'S GRID (since 2026-10-28; the UI review found them at the
 * window's edge, 216px outside the header's column on a 1440px screen). `TOAST_LAYER` is the fixed
 * layer under the header, above dialogs (`z-toast`), letting the pointer through; `TOAST_STACK` is
 * the header's own column inside it, the toasts against its right edge from `sm` and across it on
 * a phone. A page whose content the layer would cover offers a `TOAST_DOCK` in its own flow
 * instead - the chat page, above its composer, so a toast never lies over the thread.
 */
// IN THE PAGE'S TOP RIGHT CORNER since 2026-10-29 (the maintainer's review: "all toast should be top
// right", then "inside the page"): every toast, on every page, the chat included. From `lg` the page
// is the glass shell's sheet: the toasts sit inside its top right corner, below the desktop window's
// own buttons (GlassFrame's `window` strip); below `lg`, under the header; across the top on a phone.
export const TOAST_LAYER = 'fixed top-16 right-3 left-3 sm:left-auto sm:right-6 lg:top-16 lg:right-8 z-toast pointer-events-none'
export const TOAST_STACK = 'flex flex-col items-stretch sm:items-end gap-2 sm:w-[24rem]'
export const TOAST_DOCK = 'flex flex-col items-stretch sm:items-end'
