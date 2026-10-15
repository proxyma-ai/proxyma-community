import { create } from 'zustand'
import type { ToastTone } from '@/components/ui/kit'

/** The kit's tone words (`ToastTone`): `danger`, not `error`, as every other kit part says. */
export type ToastType = ToastTone

export interface Toast {
  id: string
  message: string
  type: ToastType
  // When true, the toast never auto-dismisses - the user must close it via the X button.
  persist?: boolean
  // Exactly one of `to` (an in-app route, rendered as a router Link), `href` (an external
  // URL, rendered as a plain anchor opening in a new tab), or `action` (a callback, rendered as
  // a button). `href` exists because the documentation moved to proxyma.ai - a toast that wants
  // to point at it has no in-app route to name any more. `action` exists because Settings is a
  // global MODAL, not a route: a toast that wants to send the user there has no path to link to,
  // and faking one with the current pathname would navigate for no reason.
  link?: { label: string; to: string; href?: never; action?: never }
      | { label: string; href: string; to?: never; action?: never }
      | { label: string; action: () => void; to?: never; href?: never }
  // Fired when the user closes the toast via the X button (not on auto-dismiss).
  onClose?: () => void
  // Fired when the user clicks `link` (navigation itself is handled by the link's `to`).
  onLinkClick?: () => void
  // A quiet second action under the message that also closes the toast - "Don't show again" on a
  // notice that would otherwise come back. Dimmed on purpose: it is the way out, not the offer.
  quietAction?: { label: string; action: () => void }
}

export interface AddToastOptions {
  persist?: boolean
  link?: Toast['link']
  onClose?: () => void
  onLinkClick?: () => void
  quietAction?: Toast['quietAction']
}

interface ToastState {
  toasts: Toast[]
  /**
   * Returns the new toast's id.
   *
   * It returned void until 2026-09-08, which meant a PERSISTENT toast could never be taken back
   * by the code that raised it - and a persistent toast is exactly the kind that describes a
   * standing condition which can later stop being true. The provider warning is the case: it
   * says chat will not work until a provider is reachable, and once one is, nothing could
   * remove it but the user.
   */
  addToast: (message: string, type?: ToastType, options?: AddToastOptions) => string
  removeToast: (id: string) => void
}

let counter = 0

export const useToastStore = create<ToastState>((set, get) => ({
  toasts: [],
  addToast: (message, type = 'info', options) => {
    const id = String(++counter)
    set({
      toasts: [...get().toasts, {
        id, message, type,
        persist: options?.persist,
        link: options?.link,
        onClose: options?.onClose,
        onLinkClick: options?.onLinkClick,
        quietAction: options?.quietAction,
      }],
    })
    if (!options?.persist) {
      setTimeout(() => get().removeToast(id), 4000)
    }
    return id
  },
  removeToast: (id) => set({ toasts: get().toasts.filter(t => t.id !== id) }),
}))
