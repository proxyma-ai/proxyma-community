'use client'

import type { ComponentType, ReactNode } from 'react'
import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { ArrowLeft, ChevronRight } from 'lucide-react'
import { Button } from './Button'
import { cn } from './cn'
import { DialogShell, useDialogPage, type DiscardLabels } from './Dialog'
import { BACK_BUTTON, BACK_ICON_SIZE } from './iconButton'
import { usePhone } from './usePhone'
import { Presence } from './Presence'
import { PageTitle, SectionHeading } from './Text'
import { TEXT } from './typeScale'
import { ToggleSwitch } from './ToggleSwitch'
import { useWheelScrollX } from './useWheelScrollX'

/**
 * THE SETTINGS WINDOW, for the app and both consoles. The three drew the same window three times -
 * the same panel, the same `md:w-56` rail, the same entry button, the same save bar - and the
 * consoles' copies had drifted: no page title, an upper-case "SETTINGS" label, bare `hover:`, no
 * phone row, no short-screen handling, and Escape on a document listener with no focus trap.
 *
 * The kit owns what is INSIDE the dialog; each app opens it in its own dialog shell (the app's
 * `ModalShell`, with the translated discard guard) with `SETTINGS_PANEL` and `SETTINGS_OVERLAY`.
 */

/**
 * The panel: nearly full-screen on a phone, nearly full-height on a short screen, and from xl as wide
 * as the window allows - 90% of it, up to 100rem (2026-11-11, the maintainer: the width should follow
 * the app's, for a better look on a wide window; it was 64rem, 72rem from 2xl) - always with backdrop
 * round it, since no dialog has a close X.
 */
export const SETTINGS_PANEL =
  'relative surface-float border rounded-lg shadow-float w-full sm:max-w-5xl xl:max-w-[min(90vw,100rem)] ' +
  'h-[calc(100dvh-1.5rem)] sm:h-[min(760px,calc(100dvh-3rem))] 2xl:h-[min(880px,88dvh)] short:h-[calc(100dvh-1rem)] ' +
  // CLIPPED to its corners (not hidden: a hidden box is a scroll container a focused field can
  // move), so the rail's well and the save bar keep the panel's radius. Its lists are portalled.
  'sm:tall:h-[min(1000px,85dvh)] animate-slide-up flex flex-col overflow-clip'

/**
 * The overlay does NOT scroll (maintainer, 2026-10-03: the wheel over the rail moved the whole
 * window). CLIP, not hidden: a hidden overflow is still a scroll container that focusing a field
 * can move. Each column scrolls itself.
 */
export const SETTINGS_OVERLAY = 'fixed inset-0 z-dialog flex justify-center overflow-clip p-3 sm:p-6 short:p-2'

export interface RailItem<K extends string> {
  key: K
  label: string
  icon?: ComponentType<{ size?: number | string; className?: string }>
}
export interface RailGroup<K extends string> {
  label?: string
  items: RailItem<K>[]
}

/**
 * THE RAIL: a grouped column from `md`, a flat row scrolling sideways below `md` and on a short
 * screen (one line costs ~44px; the column on a phone ate a quarter of the screen for four entries
 * of nineteen). Two renders of one list, because the column is grouped and the row is not - they
 * differ in structure, not appearance. Only one is laid out, so only one is in the accessibility
 * tree. The active entry is kept in view on whichever axis is scrolling.
 *
 * It fits 1280x720 without scrolling: `py-1` entries, `space-y-4` groups (measured, frontend.md).
 */
export function SettingsRail<K extends string>({ groups, active, onChange, ariaLabel }: {
  groups: RailGroup<K>[]
  active: K
  onChange: (key: K) => void
  ariaLabel?: string
}) {
  const navRef = useRef<HTMLElement>(null)
  useWheelScrollX(navRef)
  // The rail's OWN box scrolls to the entry, never the page: `scrollIntoView` scrolls every
  // ancestor, so a rail drawn down a page (the landing site's settings scene, whose tabs change on
  // a timer) dragged the reader back up to it.
  useEffect(() => {
    const nav = navRef.current
    if (!nav) return
    nav.querySelectorAll<HTMLElement>('[aria-current="true"]').forEach(el => {
      if (!el.offsetParent) return // the render that is not laid out at this width
      const box = nav.getBoundingClientRect()
      const r = el.getBoundingClientRect()
      let dx = 0
      let dy = 0
      if (r.left < box.left) dx = r.left - box.left
      else if (r.right > box.right) dx = r.right - box.right
      if (r.top < box.top) dy = r.top - box.top
      else if (r.bottom > box.bottom) dy = r.bottom - box.bottom
      if (dx || dy) nav.scrollBy({ left: dx, top: dy, behavior: 'smooth' })
    })
  }, [active])

  const entry = (item: RailItem<K>, layout: 'row' | 'column') => {
    const Icon = item.icon
    const on = item.key === active
    return (
      <button
        key={item.key}
        type="button"
        onClick={() => onChange(item.key)}
        aria-current={on}
        // The rail's rows stay 12px on `py-1` - the rail has to fit 1280x720 without scrolling (six
        // pixels to spare on desktop, frontend.md), and a 14/20 row is 72px taller over eighteen.
        // The current tab is LIFTED out of the rail's well (since 2026-10-29, the depth review): the
        // floating plane with `shadow-card`, its glyph in the brand's ink - never a teal fill.
        // THE ROW (a phone, a short window) IS SubNavTabs' underlined words since 2026-10-28 (the UI
        // review: filled pills there, underlined tabs everywhere else - one idea, two components):
        // the words, the current one in primary ink over a brand rule, which eases with them.
        className={layout === 'row'
          ? cn(
            'relative shrink-0 flex items-center gap-2 h-9 touch:h-11 whitespace-nowrap text-sm font-medium transition-colors focus-ring',
            'after:absolute after:inset-x-0 after:bottom-0 after:h-0.5 after:rounded-full after:transition-colors after:duration-base',
            on ? 'text-ink-primary after:bg-brand-solid' : 'text-ink-caption after:bg-transparent mouse:hover:text-ink-primary',
          )
          : cn(
            // ROOMIER WHERE THERE IS HEIGHT (the maintainer, 2026-10-30: "too tight?"): from a 900px
            // tall window, 14px rows on 6px - the 720px fit is kept below that.
            'flex items-center gap-2 rounded-md text-xs whitespace-nowrap transition-colors focus-ring w-full px-3 py-1 touch:py-2 touch:min-h-tap text-left',
            '[@media(min-height:900px)]:text-sm [@media(min-height:900px)]:py-1.5',
            on ? 'bg-lift shadow-card text-ink-primary font-medium' : 'text-ink-caption mouse:hover:bg-lift/60 mouse:hover:text-ink-secondary',
          )}
      >
        {Icon && <Icon size={14} className={cn('shrink-0', layout === 'column' && '[@media(min-height:900px)]:w-4 [@media(min-height:900px)]:h-4', on && layout === 'column' && 'text-brand-legible')} />}
        {item.label}
      </button>
    )
  }

  return (
    <nav
      ref={navRef}
      aria-label={ariaLabel}
      className={cn(
        // 16rem from a 900px tall window, where its rows are 14px: a name never wraps (2026-10-30).
        'shrink-0 md:w-56 [@media(min-height:900px)]:md:w-64 touch:md:w-48 [@media(min-height:900px)]:touch:md:w-48 short:w-auto min-h-0 border-b md:border-b-0 md:border-r short:border-r-0 short:border-b border-line/60',
        // THE COLUMN IS A WELL from `md` (since 2026-10-29): the sunken plane, the current entry lifted
        // out of it. The row (a phone, a short window) is underlined words on the window's own plane.
        'md:bg-surface-alt md:shadow-well short:bg-transparent short:shadow-none',
        'overflow-x-auto md:overflow-x-visible md:overflow-y-auto short:overflow-x-auto short:overflow-y-visible scrollbar-hide',
        // The row's underline sits on the nav's own hairline, so no padding under it.
        'mx-4 md:mx-0 md:px-4 short:mx-4 short:px-0 pt-1 md:py-4 short:pt-0 short:pb-0',
      )}
    >
      <div className="flex md:hidden short:flex items-center gap-6 w-max">
        {groups.flatMap(g => g.items).map(i => entry(i, 'row'))}
      </div>
      <div className="hidden md:block short:hidden space-y-4 [@media(min-height:900px)]:space-y-6">
        {groups.map((g, gi) => (
          <div key={g.label ?? gi}>
            {g.label && <p className="px-3 mb-2 text-xs font-semibold text-ink-caption">{g.label}</p>}
            <div>{g.items.map(i => entry(i, 'column'))}</div>
          </div>
        ))}
      </div>
    </nav>
  )
}

/**
 * The window's inside: an optional banner across the top (a restart is needed), the rail, and the
 * content column - which scrolls itself, opens with the page's own TITLE every time (a tab opening
 * on a caption left nothing saying where the rail had gone), and re-fades when `contentKey`
 * changes. `footer` is the save bar. A single-section window (`hideRail`) shows no rail.
 *
 * THE COLUMN IS THE PAGE (since 2026-10-29, the depth review): the canvas plane, so each section
 * of a tab (`SettingsSections`) stands on it as a card, as the app's own pages do.
 */
export function SettingsLayout({ rail, title, titleActions, titleActionsRef, notice, banner, footer, contentKey, children, hideRail }: {
  rail?: ReactNode
  title: ReactNode
  titleActions?: ReactNode
  /** The title row's action slot, filled by portal from inside a tab. */
  titleActionsRef?: (el: HTMLDivElement | null) => void
  /** Above the content, under the title - a read-only notice. */
  notice?: ReactNode
  banner?: ReactNode
  footer?: ReactNode
  contentKey?: string
  children: ReactNode
  hideRail?: boolean
}) {
  return (
    <>
      {/* Eased in and out (Presence), so the window's content moves rather than jumps. */}
      <Presence show={!!banner} className="shrink-0"><div className="px-4 sm:px-6 pt-4">{banner}</div></Presence>
      {/* The body, by name: the save bar under it is a Presence now, which the dialog's min-size
          rule would otherwise take for the body (the last child) and grow. */}
      <div data-dialog-body="" className="flex-1 min-h-0 flex flex-col md:flex-row short:flex-col">
        {!hideRail && rail}
        {/* The window's PAGE: a shade of the canvas over the window's glass, so its cards stand on
            something (since 2026-10-29) - the canvas itself until then. */}
        <div key={contentKey} className="flex-1 min-w-0 min-h-0 overflow-y-auto bg-canvas/60 px-4 sm:px-6 pt-6 sm:pt-8 pb-8 short:pt-3 short:pb-4 animate-fade-in">
          <PageTitle as="h2" level="tab" actions={titleActions} actionsRef={titleActionsRef} className="mb-2 sm:mb-6 short:mb-3">{title}</PageTitle>
          <Presence show={!!notice}><div className="mb-6">{notice}</div></Presence>
          {children}
        </div>
      </div>
      {footer}
    </>
  )
}

/**
 * The pending-changes bar: a status line (saved, failed, saving which field), then Cancel and
 * Save - shown while anything is pending or there is something to say. The dialog footer's
 * geometry; the consoles' was `px-6 py-3`.
 */
export function SaveBar({ dirty, saving, status, progressText, onCancel, onSave, labels }: {
  dirty: boolean
  saving?: boolean
  status?: { tone: 'success' | 'danger'; text: ReactNode } | null
  progressText?: ReactNode
  onCancel: () => void
  onSave: () => void
  labels: { cancel: string; save: string; saving: string }
}) {
  // The bar eases up from the window's bottom edge when there is something to save or say, and
  // back down after; its parts fade in and out inside it.
  return (
    <Presence show={dirty || !!status} className="shrink-0">
      <div className="flex items-center justify-end gap-3 px-4 sm:px-6 py-4 short:py-2 border-t border-line">
        <Presence show={!!status} collapse={false} className="mr-auto">
          {status && <span role="status" className={cn('text-xs', status.tone === 'success' ? 'text-brand-legible' : 'text-danger')}>{status.text}</span>}
        </Presence>
        <Presence show={!!progressText} collapse={false}>
          <span className="text-xs text-ink-caption">{progressText}</span>
        </Presence>
        <Presence show={dirty} collapse={false} className="flex items-center gap-3">
          <Button variant="secondary" onClick={onCancel} disabled={saving}>{labels.cancel}</Button>
          <Button onClick={onSave} disabled={saving}>{saving ? labels.saving : labels.save}</Button>
        </Presence>
      </div>
    </Presence>
  )
}

/**
 * A page's sections, EACH A CARD on the page since 2026-10-29 (the depth review) - the card plane, its
 * line and `shadow-card`, 1.5rem apart; a hairline between them until then. The `.settings-sections`
 * rule, in the kit's CSS so the consoles have it. An `intro` paragraph opens the page, on the page.
 */
export function SettingsSections({ intro, children, className }: { intro?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <div className={cn('settings-sections', className)}>
      {/* About 70 characters a line at most (the UI review measured 114-120 at `max-w-3xl`). A
          `ch` is a zero's width, wider than the average letter, so 60 of them hold about 70. */}
      {intro && <p className={cn(TEXT.caption, 'max-w-[60ch]')}>{intro}</p>}
      {children}
    </div>
  )
}

/**
 * One section: its heading (15/600) with a caption, and an action at the right - or a `toggle`
 * that turns the whole section on, or a `locked` notice in its place. `gap` is the inner rhythm:
 * `fields` (`space-y-6`) for a stack of fields, `list` for a list that spaces itself.
 */
export function SettingsSection({ title, caption, action, toggle, locked, children, gap = 'fields', tone, className }: {
  title: ReactNode
  caption?: ReactNode
  action?: ReactNode
  toggle?: { checked: boolean; onChange: (v: boolean) => void; disabled?: boolean; ariaLabel?: string }
  locked?: ReactNode
  children?: ReactNode
  gap?: 'fields' | 'list'
  tone?: 'default' | 'danger'
  className?: string
}) {
  const right = toggle
    ? <ToggleSwitch checked={toggle.checked} onChange={toggle.onChange} disabled={toggle.disabled} ariaLabel={toggle.ariaLabel ?? (typeof title === 'string' ? title : undefined)} />
    : action
  return (
    <section className={className}>
      {/* The heading's 16px is the body's own top margin, and a body with nothing in it is not drawn: a section whose
          fields wait behind its switch (a Presence, empty while off) stood 16px taller below than above (2026-11-18). */}
      <SectionHeading caption={caption} action={right} tone={tone} flush>{title}</SectionHeading>
      {locked}
      {children && <div className={cn('mt-4 empty:hidden', gap === 'fields' ? 'space-y-6' : '')}>{children}</div>}
    </section>
  )
}

/**
 * THE WHOLE SETTINGS WINDOW in one part - the dialog shell at the settings panel's size, the rail
 * built from `groups` (or a `rail` of the caller's own), and the layout. Both consoles' windows
 * were the same thirty lines of assembly.
 *
 * Since 2026-10-14 it takes what the app's window adds round the same parts - a `banner` over it,
 * a read-only `notice`, `titleActions` and the portal slot `titleActionsRef`, `closable` while a
 * save runs, and a whole `rail` element - so the app's Settings can be this window too rather than
 * a second assembly of it.
 *
 * A single section hides the rail and its name becomes the title. The discard words come in
 * translated, as everywhere.
 */
/**
 * The phone's bar over the Settings window (registered with the dialog's page, so the shell draws none): on the list,
 * the dialog's own back arrow - which closes - and the window's name; on a section's page, an arrow and the window's
 * name that go back to the list, as a phone's back button names where it goes.
 */
function SettingsPhoneBar({ name, onBack, backLabel }: { name: string; onBack: (() => void) | null; backLabel: string }) {
  const page = useDialogPage()
  const register = page?.register
  useLayoutEffect(() => {
    if (!register) return
    register(true)
    return () => register(false)
  }, [register])
  return (
    // On the page's 16px edge, the way back's glyph on it (BACK_BUTTON) - as a dialog's page draws it.
    <div data-dialog-head="" className="sm:hidden flex items-center gap-3 px-4 py-2 border-b border-line/45 shrink-0">
      {onBack ? (
        <>
          <button type="button" onClick={onBack} aria-label={backLabel} className={BACK_BUTTON}>
            <ArrowLeft size={BACK_ICON_SIZE} aria-hidden="true" />
          </button>
          <span className={cn(TEXT.title, 'truncate')}>{name}</span>
        </>
      ) : (
        <>
          {page?.back}
          <span className={cn(TEXT.title, 'truncate')}>{name}</span>
        </>
      )}
    </div>
  )
}

/** The window's sections as a phone's grouped list: a row each - its glyph, its name and a chevron - that opens it. */
function SettingsPhoneList<K extends string>({ groups, onChoose }: { groups: RailGroup<K>[]; onChoose: (key: K) => void }) {
  return (
    <div data-dialog-body="" className="flex-1 min-h-0 overflow-y-auto bg-canvas/60 px-4 pb-8 animate-fade-in">
      {groups.map((g, gi) => (
        <div key={g.label ?? gi} className="pt-6">
          {g.label && <p className="mb-1 text-xs font-semibold text-ink-caption">{g.label}</p>}
          <ul className="divide-y divide-line/60">
            {g.items.map(item => {
              const Icon = item.icon
              return (
                <li key={item.key}>
                  <button type="button" onClick={() => onChoose(item.key)}
                    className={cn(TEXT.label, 'w-full min-h-tap flex items-center gap-3 py-2 text-left focus-ring rounded-sm')}>
                    {Icon && <Icon size={18} className="shrink-0 text-ink-caption" />}
                    <span className="flex-1 min-w-0 truncate">{item.label}</span>
                    <ChevronRight size={16} className="shrink-0 text-ink-caption" aria-hidden="true" />
                  </button>
                </li>
              )
            })}
          </ul>
        </div>
      ))}
    </div>
  )
}

export function SettingsWindow<K extends string>({
  onClose, dirty, discardLabels, ariaLabel, groups, active, onChange, title, footer, children,
  rail, banner, notice, titleActions, titleActionsRef, closable, startOnPage = false,
}: {
  onClose: () => void
  dirty?: boolean
  discardLabels: DiscardLabels
  ariaLabel: string
  groups: RailGroup<K>[]
  active: K
  onChange: (key: K) => void
  title: ReactNode
  footer?: ReactNode
  children: ReactNode
  /** A rail of the caller's own, in place of the one drawn from `groups`. */
  rail?: ReactNode
  /** Over the window's content - a restart needed, a license ending. */
  banner?: ReactNode
  /** Under the title - "Read only: only a Super admin changes this". */
  notice?: ReactNode
  titleActions?: ReactNode
  /** The title row's action slot, filled by portal from inside a tab. */
  titleActionsRef?: (el: HTMLDivElement | null) => void
  /** False while a close must be refused - a save in flight. */
  closable?: boolean
  /**
   * On a phone, open on the section (`active`) rather than on the list of sections - for a window opened to a given
   * tab ("Open Logs").
   */
  startOnPage?: boolean
}) {
  const single = groups.reduce((n, g) => n + g.items.length, 0) <= 1
  // ON A PHONE SETTINGS IS A LIST THAT OPENS PAGES (the mobile audit, 2026-11-16: a back bar, an 18-tab strip cut at the
  // edge and the tab's title again - three titles and a hidden list of places - where every phone's Settings is a
  // grouped list): the sections as rows, each opening its page under a bar that goes back to them. A window with one
  // section, or a rail of the caller's own, is its page as before.
  const phone = usePhone()
  const drill = phone && !single && !rail
  const [view, setView] = useState<'list' | 'page'>(startOnPage ? 'page' : 'list')
  const listing = drill && view === 'list'
  return (
    <DialogShell
      onClose={onClose}
      closable={closable}
      dirty={dirty}
      discardLabels={discardLabels}
      overlayClassName={SETTINGS_OVERLAY}
      panelClassName={SETTINGS_PANEL}
      phonePage
      ariaLabel={ariaLabel}
    >
      {drill && <SettingsPhoneBar name={ariaLabel} backLabel={discardLabels.back ?? 'Back'} onBack={listing ? null : () => setView('list')} />}
      {listing ? (
        <SettingsPhoneList groups={groups} onChoose={key => { onChange(key); setView('page') }} />
      ) : (
      <SettingsLayout
        hideRail={(single && !rail) || drill}
        rail={rail ?? <SettingsRail groups={groups} active={active} onChange={onChange} ariaLabel={ariaLabel} />}
        title={title}
        titleActions={titleActions}
        titleActionsRef={titleActionsRef}
        notice={notice}
        banner={banner}
        contentKey={active}
        footer={footer}
      >
        {children}
      </SettingsLayout>
      )}
    </DialogShell>
  )
}
