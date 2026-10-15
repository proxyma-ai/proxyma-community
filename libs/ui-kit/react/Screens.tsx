'use client'

import { useId, useRef, type ComponentType, type ReactNode } from 'react'
import { ArrowLeft } from 'lucide-react'
import { cardClass } from './Card'
import { cn } from './cn'
import { PAGE_COLUMN, PAGE_GUTTER } from './layout'
import { MODAL_SCRIM_DARK } from './Dialog'
import { Spinner } from './Spinner'
import { PageTitle } from './Text'
import { TEXT, textLink } from './typeScale'
import { useModalLayer } from './useModalLayer'

/**
 * WHOLE SCREENS - the page column, the sign-in card, the status screens - shared by the app and
 * both consoles. Three cards with three paddings signed people in; five boot screens and three
 * status screens each laid themselves out.
 */

/**
 * A page's content in THE PAGE COLUMN (the header's own width - the rule for every page) with the
 * spacing table's rhythm: `py-6 sm:py-8`, `space-y-6`, all of it collapsed on a short screen.
 */
export function Page({ children, className, as: As = 'div' }: { children: ReactNode; className?: string; as?: 'div' | 'main' }) {
  return (
    <As className={cn(PAGE_COLUMN, PAGE_GUTTER, 'py-6 sm:py-8 short:py-3 space-y-6 short:space-y-3', className)}>
      {children}
    </As>
  )
}

/**
 * A page's head: its title (18/600 - one size; Sources had a 30px title and Administration an
 * 18px one), the caption line under it, and the page's actions at the right. `back` is the way up
 * - a `BackLink` - above the title (a key account back to the list); it was drawn by hand twice.
 */
export function PageHeader({ title, meta, actions, back, className }: {
  title: ReactNode
  meta?: ReactNode
  actions?: ReactNode
  /** A `BackLink` above the title. */
  back?: ReactNode
  className?: string
}) {
  if (!back) return <PageTitle meta={meta} actions={actions} className={className}>{title}</PageTitle>
  return (
    <div className={className}>
      <div className="mb-2">{back}</div>
      <PageTitle meta={meta} actions={actions}>{title}</PageTitle>
    </div>
  )
}

/**
 * THE WAY BACK UP - "Back to sign in", "Key accounts": the quiet text link with a left arrow, 13px.
 * A function of its classes, like the router links it is drawn on (the kit cannot import a router):
 * `render` gets the class string and the content and returns the app's `Link`; `href` alone draws a
 * plain anchor (the site's legal pages). Four pages wrote it, at two sizes.
 */
export function BackLink({ children, href, render }: {
  children: ReactNode
  href?: string
  render?: (className: string, content: ReactNode) => ReactNode
}) {
  const className = cn(textLink('quiet'), 'text-xs')
  const content = <><ArrowLeft size={14} aria-hidden="true" />{children}</>
  if (render) return <>{render(className, content)}</>
  return <a href={href} className={className}>{content}</a>
}

/**
 * THE SIGN-IN CARD and the canvas it sits on - sign in, set up, forgot and reset in the app, and
 * the two consoles' sign-in. One card: `max-w-[420px]`, `p-6 sm:px-10 sm:py-8`; the mark, then the
 * one heading (the product's name is in the mark - showing it again as a word under it was removed
 * from the app in its F11 fix and survived in the consoles), then the form. `background` takes the
 * kit's `ProxymaBackground` where a surface wants it.
 */
export function AuthLayout({ logo, title, subtitle, description, back, children, footer, background }: {
  logo: ReactNode
  title: ReactNode
  subtitle?: ReactNode
  /** A smaller note under the subtitle, 13px - the first-run line on a console's sign-in. */
  description?: ReactNode
  /** A link back, above the mark ("Back to sign in"). */
  back?: ReactNode
  children: ReactNode
  /** Under the card's form, centred from `sm`: "No account? Create one", a legal line. */
  footer?: ReactNode
  background?: ReactNode
}) {
  return (
    <div className="relative h-full overflow-y-auto bg-canvas">
      {background}
      <div className="relative z-10 min-h-full flex items-center justify-center px-3 sm:px-4 py-6">
        <div className="w-full max-w-[420px]">
          <div className={cn(cardClass({ variant: 'solid', padding: 'none' }), 'shadow-card p-6 sm:px-10 sm:py-8')}>
            {back && <div className="mb-4">{back}</div>}
            <div className="flex items-center justify-center gap-2 mb-4">{logo}</div>
            <h1 className={cn(TEXT.title, 'text-center')}>{title}</h1>
            {subtitle && <p className="mt-1 text-sm text-ink-caption text-center">{subtitle}</p>}
            {description && <p className="mt-2 text-xs leading-relaxed text-ink-caption text-center">{description}</p>}
            <div className="mt-5">{children}</div>
            {footer && <div className="mt-5 text-left sm:text-center text-sm text-ink-caption">{footer}</div>}
          </div>
        </div>
      </div>
    </div>
  )
}

/**
 * A form's verdict above it - a failed sign-in, a sent reset mail: a tinted block, centred, 14px.
 * Seven of these were written in two styles.
 */
export function FormAlert({ tone = 'danger', children }: { tone?: 'danger' | 'success' | 'info'; children: ReactNode }) {
  return (
    <div
      role={tone === 'danger' ? 'alert' : 'status'}
      className={cn(
        'rounded-lg px-3.5 py-2.5 mb-5 text-sm leading-snug text-center animate-fade-in',
        tone === 'danger' && 'bg-danger/10 text-danger',
        tone === 'success' && 'bg-success/10 text-success',
        tone === 'info' && 'bg-brand-solid/10 text-brand-legible',
      )}
    >
      {children}
    </div>
  )
}

/** The line between two ways in - "or" over a hairline, between OAuth buttons and the form. */
export function OrDivider({ label }: { label: string }) {
  return (
    <div className="flex items-center gap-3 my-6" role="separator">
      <span className="flex-1 h-px bg-line" />
      <span className="text-xs font-medium text-ink-disabled">{label}</span>
      <span className="flex-1 h-px bg-line" />
    </div>
  )
}

/**
 * WHILE THE APP STARTS - the mark and a spinner in the middle of the canvas, nothing else. Five
 * places drew this, at three sizes.
 *
 * With `message` it is the same screen when starting has STOPPED - a server that cannot be
 * reached: the mark, the sentence in place of the spinner, and `action` (Retry) under it. The
 * consoles drew that as a status dialog over an empty page.
 */
export function BootScreen({ logo, label, message, action }: {
  logo?: ReactNode
  label?: string
  message?: ReactNode
  action?: ReactNode
}) {
  return (
    <div
      role={message ? 'alert' : 'status'}
      aria-label={message ? undefined : label}
      className="h-full w-full flex flex-col items-center justify-center gap-4 px-6 text-center bg-canvas"
    >
      {logo}
      {message ? <p className="max-w-sm text-sm text-ink-secondary leading-relaxed">{message}</p> : <Spinner size="md" />}
      {action}
    </div>
  )
}

/**
 * A SCREEN THAT TAKES THE APP AWAY FOR A MOMENT - restarting, quitting, under maintenance: a
 * status card centred over the darker scrim, a glyph or spinner, a title, a sentence, and at most
 * one action. Not a dialog: there is nothing to dismiss.
 */
export function StatusScreen({ icon: Icon, busy, title, children, action, scrim = true, layer = true, className }: {
  icon?: ComponentType<{ size?: number | string; className?: string }>
  busy?: boolean
  title: ReactNode
  children?: ReactNode
  action?: ReactNode
  scrim?: boolean
  /**
   * A LAYER of its own, like every dialog, at the top level (2): focus moves in and stays in, and
   * Escape is swallowed - nothing under a restart screen may close. `false` where the caller
   * already holds it in a dialog layer at a level of its choosing (the app's StatusLayer).
   */
  layer?: boolean
  className?: string
}) {
  const titleId = useId()
  const panelRef = useRef<HTMLDivElement>(null)
  return (
    <div role="alertdialog" aria-modal="true" aria-labelledby={titleId} className={cn('fixed inset-0 z-status flex items-center justify-center p-4', className)}>
      {/* The two parts an exit moves, when its dialog layer goes (useExitGhost). */}
      {scrim && <div data-scrim="" className={MODAL_SCRIM_DARK} />}
      {layer && <StatusLayerGuard panelRef={panelRef} />}
      <div ref={panelRef} tabIndex={-1} data-motion-panel="" className="relative surface-float border border-line/45 rounded-lg shadow-card w-full max-w-sm px-6 py-6 flex flex-col items-center text-center gap-3 animate-slide-up outline-none">
        {busy ? <Spinner size={22} /> : Icon && <Icon size={28} className="text-brand-legible" aria-hidden="true" />}
        <h2 id={titleId} className={TEXT.title}>{title}</h2>
        {children && <div className="text-sm text-ink-secondary leading-relaxed">{children}</div>}
        {action && <div className="mt-2">{action}</div>}
      </div>
    </div>
  )
}

/** Holds a status screen's dialog layer while it is shown (a component, so it can be left out). */
function StatusLayerGuard({ panelRef }: { panelRef: React.RefObject<HTMLDivElement> }) {
  useModalLayer(panelRef, { level: 2 })
  return null
}
