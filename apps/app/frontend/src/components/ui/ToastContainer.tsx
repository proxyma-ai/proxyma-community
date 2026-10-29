import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Link } from 'react-router-dom'
import { useToastStore } from '@/store/toastStore'
import { useTourStore } from '@/store/tourStore'
import { ExternalLink, MOTION_MS, Presence, TOAST_DOCK, TOAST_LAYER, TOAST_STACK, Toast, cn, prefersReducedMotion, textLink } from '@/components/ui/kit'
import { useTranslation } from '@/i18n'

// THE STACK, NOT THE TOAST, IS THIS APP'S. The card itself is the kit's `Toast` and the column the
// kit's `TOAST_STACK`, since the 2026-10 rebuild; what stays here is what no kit component may
// hold - a zustand store, react-router's `Link`, and the watch on open menus below. Neither console
// raises toasts.

// Whether a dropdown menu is open anywhere on the page: a trigger announcing an expanded popup.
// Every menu in the app says so - the kit's UserMenu, IconMenu and MobileMenu, AppMenu and the
// preference submenus all pair `aria-haspopup` with `aria-expanded` - so this needs no registry
// for a menu to join. Watched only while a toast is up.
const OPEN_MENU = '[aria-haspopup][aria-expanded="true"]'

function useMenuOpen(watch: boolean): boolean {
  const [open, setOpen] = useState(false)
  useEffect(() => {
    if (!watch) { setOpen(false); return }
    const check = () => setOpen(!!document.querySelector(OPEN_MENU))
    check()
    const observer = new MutationObserver(check)
    observer.observe(document.body, { subtree: true, childList: true, attributes: true, attributeFilter: ['aria-expanded'] })
    return () => observer.disconnect()
  }, [watch])
  return open
}

/**
 * The toasts to draw: the store's, plus any it has just dropped, kept where they stood for the
 * length of their exit (`leaving`). The store removes a toast at once; without this it vanished
 * and the ones under it jumped up in the same frame. Reduced motion keeps nothing.
 */
function useLeaving<T extends { id: string }>(items: T[]): Array<{ item: T; leaving: boolean }> {
  const [gone, setGone] = useState<Array<{ item: T; index: number }>>([])
  const previous = useRef(items)
  // Before paint, or the dropped toast would be missing for one frame and then come back to leave.
  useLayoutEffect(() => {
    const ids = new Set(items.map(i => i.id))
    const removed = previous.current.flatMap((item, index) => (ids.has(item.id) ? [] : [{ item, index }]))
    previous.current = items
    if (removed.length === 0 || prefersReducedMotion()) return
    setGone(g => [...g, ...removed])
    setTimeout(() => setGone(g => g.filter(x => !removed.some(r => r.item.id === x.item.id))), MOTION_MS.base)
  }, [items])
  const out = items.map(item => ({ item, leaving: false }))
  for (const { item, index } of gone) {
    if (!out.some(o => o.item.id === item.id)) out.splice(Math.min(index, out.length), 0, { item, leaving: true })
  }
  return out
}

export function ToastContainer() {
  const { t } = useTranslation()
  const toasts = useToastStore(s => s.toasts)
  const removeToast = useToastStore(s => s.removeToast)
  // HELD WHILE A MENU IS OPEN, since 2026-09-30 (the review's F4). The account menu drops from the
  // header's right edge, exactly where toasts stack, and a menu cannot be lifted over them: it
  // lives inside the header's own stacking context (z-50), while toasts portal to the body above
  // every dialog. So the toasts step aside instead - hidden, not dismissed, and back the moment
  // the menu closes. The MCP/API notice was the one that did it, being persistent.
  const menuOpen = useMenuOpen(toasts.length > 0)
  // Out of the way while the walkthrough runs: a toast in the top right sat over the Sources page's
  // Connect button, so the tour's highlight showed the toast instead of the control it was about.
  const tourActive = useTourStore(s => s.active)
  const hidden = menuOpen || tourActive
  const shown = useLeaving(toasts)
  // Every toast is in the window's top right corner since 2026-10-29 (the maintainer's review); the
  // chat page's dock above its composer is no longer used.
  const dock: HTMLElement | null = null

  if (shown.length === 0) return null

  const cards = shown.map(({ item: toast, leaving }) => {
    const card = renderToast(toast, leaving)
    // Docked, a toast is in the flow: its height eases in and out (Presence), so the thread above
    // it moves rather than jumping by a card's height.
    return dock ? <Presence key={toast.id} show={!leaving}><div className="pb-2">{card}</div></Presence> : card
  })

  if (dock) {
    return createPortal(
      <div aria-hidden={hidden || undefined} className={cn(TOAST_DOCK, 'transition-[opacity,visibility] duration-fast', hidden && 'invisible opacity-0')}>
        {cards}
      </div>,
      dock,
    )
  }

  return createPortal(
    // AT THE TOP, just under the header - never bottom-right, where it was until 2026-09-26. The
    // chat composer lives at the bottom, and on every viewport narrower than a wide desktop a
    // bottom-right toast sat on top of it: the history and send buttons could not be pressed
    // until the toast was dismissed. Full width with a margin on a phone; a column at the right
    // from `sm` up. That is the kit's TOAST_STACK.
    // Stepping aside fades: `visibility` is transitioned with the opacity, so it turns hidden at
    // the END of the fade out and visible at the START of the fade in.
    <div aria-hidden={hidden || undefined} className={cn(TOAST_LAYER, 'transition-[opacity,visibility] duration-fast', hidden && 'invisible opacity-0')}>
      <div className={TOAST_STACK}>{cards}</div>
    </div>,
    document.body,
  )

  // The parameter is `toast`, NOT `t`. It was `t`, and this component has a translation function
  // of that name in the enclosing scope - so the Dismiss button's `t('Dismiss')` resolved to the
  // Toast object and failed to compile. Third instance of this shadow in the SPA.
  function renderToast(toast: (typeof toasts)[number], leaving: boolean) {
    return (
        // The message, and UNDER it whatever the toast offers - the kit Toast's `actions`. The link
        // used to follow the message inline, so on a message long enough to wrap it started a
        // line of its own - three left edges in one card (maintainer's feedback, 2026-10-02). One
        // column now: the offer first and the way out after it.
        <Toast
          key={toast.id}
          tone={toast.type}
          leaving={leaving}
          onDismiss={() => { toast.onClose?.(); removeToast(toast.id) }}
          dismissLabel={t('Dismiss')}
          actions={(toast.link || toast.quietAction) && (
            <>
              {toast.link && (toast.link.action != null ? (
                // A button, not a link: it opens an overlay in place and navigates nowhere, so
                // giving it an href would be a lie to the browser and to assistive tech.
                <button
                  type="button"
                  onClick={() => { toast.link!.action!(); toast.onLinkClick?.() }}
                  className={textLink()}
                >
                  {toast.link.label}
                </button>
              ) : toast.link.to != null ? (
                <Link to={toast.link.to} onClick={() => toast.onLinkClick?.()} className={textLink()}>
                  {toast.link.label}
                </Link>
              ) : (
                <ExternalLink
                  href={toast.link.href!}
                  onClick={() => toast.onLinkClick?.()}
                  className={textLink()}
                  iconSize={11}
                >
                  {toast.link.label}
                </ExternalLink>
              ))}
              {toast.quietAction && (
                // Quiet: the way out of a notice that returns, kept quieter than the offer beside
                // it and well clear of the X, which only closes it.
                <button
                  type="button"
                  onClick={() => { toast.quietAction!.action(); removeToast(toast.id) }}
                  className={cn(textLink('quiet'), 'text-xs')}
                >
                  {toast.quietAction.label}
                </button>
              )}
            </>
          )}
        >
          {toast.message}
        </Toast>
    )
  }
}
